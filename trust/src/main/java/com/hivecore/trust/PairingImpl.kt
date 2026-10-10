package com.hivecore.trust

import com.hivecore.core.PeerEndpoint
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.mesh.MeshJson
import com.hivecore.mesh.MeshRouter
import com.hivecore.mesh.PeerStore
import com.hivecore.mesh.SecureTransport
import com.hivecore.mesh.Session
import com.hivecore.mesh.SessionListener
import com.hivecore.mesh.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/** Pairing control messages, carried as CONTROL frames inside the not-yet-trusted session (never as envelopes). */
@Serializable data class PairingMsg(val kind: String, val groupId: String? = null, val token: String? = null, val name: String? = null, val reason: String? = null) {
    companion object {
        const val JOIN = "JOIN"; const val JOIN_OK = "JOIN_OK"; const val CONFIRM = "CONFIRM"; const val ABORT = "ABORT"
    }
}

sealed class PairingEvent {
    data class Paired(val nodeId: String, val name: String, val pubkey: String, val groupId: String) : PairingEvent()
    data class Aborted(val peerNodeId: String?, val reason: String) : PairingEvent()
}

/**
 * Spec 6.2 pairing over the session layer. The inviter shows a QR ([Invite.qrPayload]) or sends
 * `swarm://join?...` ([Invite.link]); the joiner connects (LAN, Nearby or relay: [locate] finds the inviter's
 * endpoint), both run the SecureTransport handshake, the joiner presents the token, and both phones show the
 * 6-digit SAS derived from the handshake hash ([Session.sas]). Keys are pinned only when both users confirmed; a
 * mismatch, an unknown, used or expired token, or a lost link aborts before any key is stored.
 *
 * Until the pins exist the session is admitted as control-only (SecureTransport.allowUnpinned): it carries these
 * messages and nothing else, so the router never sees an envelope from an unpaired phone.
 *
 * @param router    the mesh router: its session layers carry the control frames and expose the session once pinned
 * @param locate    where the joiner finds the inviter: nodeId (and the invite) → an endpoint on the selected
 *                  transport, e.g. the LAN peer whose service name carries the inviter's nodeId prefix
 * @param remote    the remote leg (spec 6.2 invite link over the relay) when [locate] finds nothing: builds the
 *                  guest relay transport (`RelayPairingChannel.joiner`), registers its session layer under
 *                  [guestSecure] and returns the inviter's endpoint on it; null = LAN / Nearby only
 * @param guestSecure the session layer of a transport outside the router by endpoint transport name (the guest leg)
 * @param onPaired  (nodeId, pubkey, name, groupId) after both confirmed; the roster admin adds the member here
 */
class PairingImpl(
    private val router: MeshRouter,
    private val signer: Signer,
    private val peerStore: PeerStore,
    private val localName: () -> String,
    private val locate: suspend (inviterNodeId: String, invite: Invite) -> PeerEndpoint?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: SecureRandom = SecureRandom(),
    private val replyTimeoutMs: Long = 15_000L,
    private val remote: (suspend (Invite) -> PeerEndpoint?)? = null,
    private val guestSecure: (String) -> SecureTransport? = { null },
) : Pairing, SessionListener {

    /** The session layer an endpoint's transport name resolves to: the router's, else a guest leg's. */
    private fun secureFor(transport: String): SecureTransport? = router.secure(transport) ?: guestSecure(transport)

    val nodeId: String = Wire.nodeIdOf(signer.publicKeyB64) ?: error("signer public key is not base64")
    var onPaired: (nodeId: String, pubkey: String, name: String, groupId: String) -> Unit = { _, _, _, _ -> }

    private val promptsFlow = MutableSharedFlow<SasPrompt>(replay = 0, extraBufferCapacity = 16)
    /** The SAS to show, on both phones (the joiner also gets it from [join]). */
    val prompts: SharedFlow<SasPrompt> = promptsFlow
    private val eventsFlow = MutableSharedFlow<PairingEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<PairingEvent> = eventsFlow

    private class Pend(val session: Session, val prompt: SasPrompt, val groupId: String, val inviter: Boolean, val invite: Invite?) {
        var localConfirmed = false
        var peerConfirmed = false
    }

    private val lock = Any()
    private val invites = HashMap<String, Invite>()                 // token → outstanding invite (inviter)
    private val usedTokens = HashSet<String>()
    private val expecting = HashMap<String, Invite>()               // inviter nodeId → invite being joined (joiner)
    private val pending = HashMap<String, Pend>()                   // handshakeId → pairing awaiting confirmation
    private val joinReplies = HashMap<String, CompletableDeferred<PairingMsg>>()   // session key → JOIN reply waiter

    init {
        router.addSessionListener(this)
        router.secureTransports().forEach { it.allowUnpinned = { n, k -> admit(n, k) } }
    }

    /** An unpinned handshake is admitted (control-only) for the inviter we are joining, a pending pairing, or while an invite is live. */
    private fun admit(peerNodeId: String, pubkey: String): Boolean = synchronized(lock) {
        pruneInvites()
        expecting[peerNodeId]?.pubkey == pubkey || pending.values.any { it.session.peerNodeId == peerNodeId && it.session.peerPubkey == pubkey } || invites.isNotEmpty()
    }

    // ------------------------------------------------------------------ Pairing

    override fun startInvite(groupId: String): Invite {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val invite = Invite(groupId, signer.publicKeyB64, token, clock())
        synchronized(lock) { pruneInvites(); invites[token] = invite }
        SLog.d(TAG, "invite for $groupId issued (valid ${Invite.TOKEN_TTL_MS / 60_000} min)")
        return invite
    }

    override suspend fun join(invite: Invite): Result<SasPrompt> {
        val inviterId = Wire.nodeIdOf(invite.pubkey) ?: return Result.failure(IllegalArgumentException("the invite key is not valid"))
        if (invite.issuedTs > 0 && clock() - invite.issuedTs > Invite.TOKEN_TTL_MS) return Result.failure(IllegalStateException("the invite expired"))
        if (inviterId == nodeId) return Result.failure(IllegalArgumentException("this is our own invite"))
        peerStore.get(inviterId)?.let { if (it.pubkey == invite.pubkey) return Result.failure(IllegalStateException("already paired with ${it.name}")) }
        synchronized(lock) { expecting[inviterId] = invite }
        try {
            val ep = locate(inviterId, invite) ?: remote?.invoke(invite) ?: return Result.failure(IllegalStateException("the inviting phone is not reachable"))
            val secure = secureFor(ep.transport) ?: return Result.failure(IllegalStateException("no transport ${ep.transport}"))
            val session = try { secure.open(ep) } catch (e: Exception) { return Result.failure(SecurityException("handshake failed: ${e.message}")) }
            if (session.peerNodeId != inviterId || session.peerPubkey != invite.pubkey) {
                secure.disconnect(ep)
                return Result.failure(SecurityException("the phone that answered is not the inviter"))
            }
            val waiter = CompletableDeferred<PairingMsg>()
            synchronized(lock) { joinReplies[key(session)] = waiter }
            val sent = secure.sendControl(session, encode(PairingMsg(PairingMsg.JOIN, invite.groupId, invite.token, localName())))
            if (sent.isFailure) return Result.failure(sent.exceptionOrNull()!!)
            val reply = withTimeoutOrNull(replyTimeoutMs) { waiter.await() }
            synchronized(lock) { joinReplies.remove(key(session)) }
            if (reply == null) { secure.disconnect(ep); return Result.failure(IllegalStateException("no answer from the inviting phone")) }
            if (reply.kind != PairingMsg.JOIN_OK) { secure.disconnect(ep); return Result.failure(SecurityException(reply.reason ?: "refused by the inviting phone")) }
            val prompt = SasPrompt(inviterId, reply.name ?: inviterId.take(8), session.sas(), handshakeId(session))
            synchronized(lock) { pending[prompt.handshakeId] = Pend(session, prompt, invite.groupId, inviter = false, invite = invite) }
            promptsFlow.tryEmit(prompt)
            return Result.success(prompt)
        } finally {
            synchronized(lock) { expecting.remove(inviterId) }
        }
    }

    override suspend fun confirm(prompt: SasPrompt, matches: Boolean): Result<Unit> {
        val p = synchronized(lock) { pending[prompt.handshakeId] } ?: return Result.failure(IllegalStateException("no pairing in progress for ${prompt.peerName}"))
        val secure = secureFor(p.session.endpoint.transport) ?: return Result.failure(IllegalStateException("no transport"))
        if (!matches) {
            secure.sendControl(p.session, encode(PairingMsg(PairingMsg.ABORT, reason = "SAS mismatch")))
            abort(p, "SAS mismatch")
            return Result.success(Unit)
        }
        val invite = p.invite
        if (p.inviter && invite != null && clock() - invite.issuedTs > Invite.TOKEN_TTL_MS) {
            secure.sendControl(p.session, encode(PairingMsg(PairingMsg.ABORT, reason = "the invite expired")))
            abort(p, "the invite expired")
            return Result.failure(IllegalStateException("the invite expired"))
        }
        val finish: Boolean
        synchronized(lock) { p.localConfirmed = true; finish = p.peerConfirmed }
        val r = secure.sendControl(p.session, encode(PairingMsg(PairingMsg.CONFIRM)))
        if (r.isFailure) { abort(p, "link lost"); return Result.failure(r.exceptionOrNull()!!) }
        if (finish) finish(p)
        return Result.success(Unit)
    }

    /** Pairings awaiting a decision (for the UI after a restart of the screen). */
    fun pendingPrompts(): List<SasPrompt> = synchronized(lock) { pending.values.map { it.prompt } }

    // ------------------------------------------------------------------ SessionListener

    override fun onControl(session: Session, bytes: ByteArray) {
        val msg = try { MeshJson.decode<PairingMsg>(String(bytes, Charsets.UTF_8)) } catch (e: Exception) {
            SLog.w(TAG, "bad pairing message from ${session.peerNodeId.take(8)}: ${e.message}"); return
        }
        when (msg.kind) {
            PairingMsg.JOIN -> scope.launch { onJoin(session, msg) }
            PairingMsg.JOIN_OK -> synchronized(lock) { joinReplies[key(session)] }?.complete(msg)
            PairingMsg.CONFIRM -> {
                val p = pendingFor(session) ?: return
                val finish: Boolean
                synchronized(lock) { p.peerConfirmed = true; finish = p.localConfirmed }
                if (finish) finish(p)
            }
            PairingMsg.ABORT -> {
                synchronized(lock) { joinReplies[key(session)] }?.let { it.complete(msg); return }
                pendingFor(session)?.let { abort(it, msg.reason ?: "aborted by ${it.prompt.peerName}") }
            }
            else -> SLog.w(TAG, "unknown pairing message '${msg.kind}' from ${session.peerNodeId.take(8)}")
        }
    }

    override fun onClose(session: Session) {
        pendingFor(session)?.let { abort(it, "link lost", disconnect = false) }
        synchronized(lock) { joinReplies.remove(key(session)) }?.complete(PairingMsg(PairingMsg.ABORT, reason = "link lost"))
    }

    // ------------------------------------------------------------------ inviter side

    private suspend fun onJoin(session: Session, msg: PairingMsg) {
        val secure = secureFor(session.endpoint.transport) ?: return
        val token = msg.token ?: ""
        val invite = synchronized(lock) { pruneInvites(); invites[token] }
        val why = when {
            token in usedTokens -> "the invite was already used"
            invite == null -> "unknown or expired invite"
            invite.groupId != msg.groupId -> "the invite is for another swarm"
            clock() - invite.issuedTs > Invite.TOKEN_TTL_MS -> "the invite expired"
            peerStore.get(session.peerNodeId)?.pubkey == session.peerPubkey -> "already paired"
            else -> null
        }
        if (why != null) {
            SLog.w(TAG, "join from ${session.peerNodeId.take(8)} refused: $why")
            secure.sendControl(session, encode(PairingMsg(PairingMsg.ABORT, reason = why)))
            secure.disconnect(session.endpoint)
            eventsFlow.tryEmit(PairingEvent.Aborted(session.peerNodeId, why))
            return
        }
        invite!!
        synchronized(lock) { usedTokens += token }   // stays listed (admitted) until pruned so a second joiner hears "already used"
        val name = msg.name?.takeIf { it.isNotBlank() } ?: session.peerNodeId.take(8)
        val prompt = SasPrompt(session.peerNodeId, name, session.sas(), handshakeId(session))
        val pend = Pend(session, prompt, invite.groupId, inviter = true, invite = invite)
        synchronized(lock) { pending[prompt.handshakeId] = pend }
        val r = secure.sendControl(session, encode(PairingMsg(PairingMsg.JOIN_OK, invite.groupId, name = localName())))
        if (r.isFailure) { abort(pend, "link lost"); return }
        SLog.d(TAG, "join from $name: showing SAS")
        promptsFlow.tryEmit(prompt)
    }

    // ------------------------------------------------------------------ outcome

    /** Both confirmed: pin the peer's key, expose the session (the router's HELLO follows) and tell the roster admin. */
    private fun finish(p: Pend) {
        synchronized(lock) { if (pending.remove(p.prompt.handshakeId) == null) return }
        if (p.session.closed) { eventsFlow.tryEmit(PairingEvent.Aborted(p.session.peerNodeId, "link lost")); return }
        val ep = p.session.endpoint
        peerStore.pin(p.session.peerNodeId, p.prompt.peerName, p.session.peerPubkey, null, emptyList(), clock(), ep.transport, ep.endpointId)
        SLog.d(TAG, "paired with ${p.prompt.peerName} (${p.session.peerNodeId.take(8)}) in ${p.groupId}")
        router.refreshSessions()
        eventsFlow.tryEmit(PairingEvent.Paired(p.session.peerNodeId, p.prompt.peerName, p.session.peerPubkey, p.groupId))
        runCatching { onPaired(p.session.peerNodeId, p.session.peerPubkey, p.prompt.peerName, p.groupId) }
            .onFailure { SLog.w(TAG, "onPaired: ${it.message}") }
    }

    private fun abort(p: Pend, reason: String, disconnect: Boolean = true) {
        synchronized(lock) { if (pending.remove(p.prompt.handshakeId) == null) return }
        SLog.w(TAG, "pairing with ${p.prompt.peerName} aborted: $reason (no key stored)")
        if (disconnect) secureFor(p.session.endpoint.transport)?.disconnect(p.session.endpoint)
        eventsFlow.tryEmit(PairingEvent.Aborted(p.session.peerNodeId, reason))
    }

    private fun pendingFor(session: Session): Pend? = synchronized(lock) { pending.values.firstOrNull { it.session === session } }

    /**
     * Caller holds [lock]. An expired invite stays listed for [EXPIRED_GRACE_MS] so a late joiner's handshake is still
     * admitted (control-only) and refused with "the invite expired" instead of a bare handshake failure; [onJoin]
     * checks the real expiry.
     */
    private fun pruneInvites() {
        val now = clock()
        val gone = invites.filterValues { now - it.issuedTs > Invite.TOKEN_TTL_MS + EXPIRED_GRACE_MS }.keys
        gone.forEach { invites.remove(it); usedTokens.remove(it) }
    }

    private fun key(s: Session) = "${s.endpoint.transport}/${s.endpoint.endpointId}"
    private fun handshakeId(s: Session): String = s.handshakeHash.take(8).joinToString("") { "%02x".format(it) }
    private fun encode(m: PairingMsg): ByteArray = MeshJson.encode(m).toByteArray(Charsets.UTF_8)

    companion object {
        private const val TAG = "Pairing"
        const val EXPIRED_GRACE_MS = 10 * 60_000L
    }
}
