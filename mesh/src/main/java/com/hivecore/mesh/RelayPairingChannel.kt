package com.hivecore.mesh

import com.hivecore.core.PeerEndpoint
import com.hivecore.core.Signer
import kotlinx.coroutines.CoroutineScope

/**
 * The relay leg of a remote pairing (spec 6.2, "Remote" row): how a joiner holding an invite link
 * `swarm://join?g=<groupId>&k=<pubkey>&t=<token>` reaches the inviter through `relayd` before any key is pinned,
 * and how the inviter lets it in. The pairing flow itself (SAS, confirm, pin) belongs to the mesh+trust track; this
 * object only hands it a [com.hivecore.core.Transport] on each side.
 *
 * Sequence the `Pairing` implementation runs:
 *
 * 1. Inviter (`startInvite`): [host] registers `sha256(token)` with the relay on its ordinary member
 *    [RelayTransport]; the relay then admits one guest presenting the raw token for 10 minutes, reachable by the
 *    inviter only (no presence, no hold, a tight rate limit).
 * 2. Joiner (`join`): [joiner] builds a guest-mode [RelayTransport] whose `connected` list holds exactly the inviter;
 *    the trust layer wraps it in a `SecureTransport(..., allowUnpinned = [allowOnly](k))` so the handshake accepts the
 *    key from the link and nothing else, calls `open(`[inviterEndpoint]`(k))`, and shows the 6-digit SAS derived from
 *    `Session.handshakeHash`.
 * 3. Inviter: its `SecureTransport.allowUnpinned` returns true for an unpinned peer while an invite is open (the
 *    guest arrives as inbound frames from `RelayTransport.endpointOf(joinerId)`), and it shows the same SAS.
 * 4. Both confirm (`confirm`): keys are pinned, the admin publishes a roster version naming the joiner and uploads it
 *    (`RelayTransport.uploadRoster`), the joiner stops the guest transport and starts a member one.
 *
 * A mismatched SAS or an expired token aborts before any key is stored; the relay closes the guest socket when the
 * invite expires.
 */
object RelayPairingChannel {
    /** The inviter's nodeId from the `k` of the link. */
    fun inviterNodeId(inviterPubkeyB64: String): String =
        Wire.nodeIdOf(inviterPubkeyB64) ?: throw IllegalArgumentException("invite pubkey is not base64")

    /** The endpoint the joiner opens its secure session on. */
    fun inviterEndpoint(inviterPubkeyB64: String): PeerEndpoint = RelayTransport.endpointOf(inviterNodeId(inviterPubkeyB64))

    /**
     * Joiner side: a guest [RelayTransport] authenticated with the invite [token] instead of roster membership.
     * Start it, wait for `authenticated`, then run the handshake on [inviterEndpoint]. Stop it once paired.
     */
    fun joiner(
        url: String,
        signer: Signer,
        groupId: String,
        inviterPubkeyB64: String,
        token: String,
        scope: CoroutineScope,
        sockets: RelaySocketFactory = OkHttpRelaySockets(),
        clock: RelayClock = RelayClock(),
        localClock: () -> Long = { System.currentTimeMillis() },
    ): RelayTransport = RelayTransport(url, signer, groupId, { 0L }, scope, sockets, clock,
        guest = GuestInvite(inviterNodeId(inviterPubkeyB64), token), localClock = localClock)

    /** Inviter side: opens the invite with the relay; returns its lifetime in seconds (10 min). */
    suspend fun host(relay: RelayTransport, token: String): Result<Int> = relay.openInvite(token)

    /** `SecureTransport.allowUnpinned` for the joiner: only the key printed in the link, and only under its own id. */
    fun allowOnly(inviterPubkeyB64: String): (nodeId: String, pubkey: String) -> Boolean =
        { nodeId, pubkey -> pubkey == inviterPubkeyB64 && nodeId == Wire.nodeIdOf(pubkey) }
}
