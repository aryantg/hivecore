package com.hivecore.mesh

import com.hivecore.core.Canon
import com.hivecore.core.SLog
import com.hivecore.core.Signer
import com.hivecore.core.SqlDb
import com.hivecore.core.Verify
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * Spec 6.4 / 18.3 `RosterStore`: the admin-signed membership a phone holds. A `ROSTER` applies only when its version is
 * higher than the held one and `adminSig` verifies against the admin's key; a `REVOKE` is a one-removal bump on top of
 * the held roster. Admin handover is a roster naming a successor, signed by the admin who held the group before it.
 * The signed bodies are kept as received so they can be gossiped verbatim.
 */
interface RosterStore {
    /** The effective roster of [groupId]: the last `ROSTER` with every later `REVOKE` applied (members minus revoked). */
    fun current(groupId: String): RosterBody?
    /** The effective roster with the highest version across groups (a phone belongs to one swarm in v1). */
    fun latest(): RosterBody?
    /** Applies a roster when its version is higher than the held one and `adminSig` verifies against the admin's key. */
    fun apply(roster: RosterBody): Boolean
    /** Applies a revocation when its version is higher than the held one and `adminSig` verifies against the admin's key. */
    fun applyRevoke(revoke: RevokeBody): Boolean
    fun isMember(nodeId: String): Boolean
    fun isRevoked(nodeId: String): Boolean
    /** The last applied `ROSTER` exactly as signed (for gossip); null when none. */
    fun signedRoster(groupId: String): RosterBody?
    /** The applied `REVOKE`s with a version above [version], oldest first (for gossip after [signedRoster]). */
    fun revokesAfter(groupId: String, version: Long): List<RevokeBody>
}

/** Spec 16.1: `adminSig` is the admin's node key over the canonical JSON ([Canon]) of the body without `adminSig`. */
object RosterSigning {
    private const val SIG = "adminSig"

    fun bytes(r: RosterBody): ByteArray = canonicalWithoutSig(MeshJson.json.encodeToJsonElement(RosterBody.serializer(), r))
    fun bytes(r: RevokeBody): ByteArray = canonicalWithoutSig(MeshJson.json.encodeToJsonElement(RevokeBody.serializer(), r))

    fun sign(r: RosterBody, admin: Signer): RosterBody = r.copy(adminSig = b64(admin.sign(bytes(r))))
    fun sign(r: RevokeBody, admin: Signer): RevokeBody = r.copy(adminSig = b64(admin.sign(bytes(r))))

    fun verify(r: RosterBody, adminKeyB64: String): Boolean = verify(bytes(r), r.adminSig, adminKeyB64)
    fun verify(r: RevokeBody, adminKeyB64: String): Boolean = verify(bytes(r), r.adminSig, adminKeyB64)

    private fun canonicalWithoutSig(e: kotlinx.serialization.json.JsonElement): ByteArray =
        Canon.canonical(JsonObject((e as JsonObject).filterKeys { it != SIG })).toByteArray(Charsets.UTF_8)

    private fun verify(bytes: ByteArray, sigB64: String, key: String): Boolean {
        val sig = try { Base64.getDecoder().decode(sigB64) } catch (_: Exception) { return false }
        return sig.isNotEmpty() && Verify.verify(key, bytes, sig)
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
}

/**
 * [RosterStore] over [SqlDb]: `_roster(groupId PK, version, body)` holds the last applied `ROSTER` as signed and
 * `_roster_revokes(groupId, version, body)` the later `REVOKE`s. Safe to construct on any db (creates the tables).
 *
 * Which key verifies `adminSig`:
 * - a group already held: the key of the current admin, taken from the held roster's member list (so a handover
 *   roster is checked against the admin who signed it, and the next one against the successor);
 * - the first roster of a group: the key listed for `admin` in that roster, which must hash to `admin` and must be
 *   this phone's own key or the key pinned for `admin` at pairing (trust on first use, bound to the pairing).
 *
 * @param selfId      this phone's nodeId (its own rosters need no pin)
 * @param selfPubkey  this phone's node key (base64 SPKI)
 * @param pinnedKeyOf nodeId → pinned node key (PeerStore), null when not pinned
 */
class RosterStoreImpl(
    private val db: SqlDb,
    private val selfId: String? = null,
    private val selfPubkey: String? = null,
    private val pinnedKeyOf: (String) -> String? = { null },
) : RosterStore {
    private val lock = Any()

    init {
        db.exec("CREATE TABLE IF NOT EXISTS _roster(groupId TEXT PRIMARY KEY, version INTEGER NOT NULL, body TEXT NOT NULL)")
        db.exec("CREATE TABLE IF NOT EXISTS _roster_revokes(groupId TEXT NOT NULL, version INTEGER NOT NULL, body TEXT NOT NULL, PRIMARY KEY(groupId, version))")
    }

    override fun signedRoster(groupId: String): RosterBody? = synchronized(lock) {
        db.query("SELECT body FROM _roster WHERE groupId = ?", listOf(groupId)).firstOrNull()?.let { decodeRoster(it["body"] as String) }
    }

    override fun revokesAfter(groupId: String, version: Long): List<RevokeBody> = synchronized(lock) {
        db.query("SELECT body FROM _roster_revokes WHERE groupId = ? AND version > ? ORDER BY version", listOf(groupId, version))
            .mapNotNull { runCatching { MeshJson.decode<RevokeBody>(it["body"] as String) }.getOrNull() }
    }

    override fun current(groupId: String): RosterBody? = synchronized(lock) {
        val base = signedRoster(groupId) ?: return null
        val revokes = revokesAfter(groupId, base.version)
        if (revokes.isEmpty()) return base
        val revoked = (base.revoked + revokes.map { it.nodeId }).distinct()
        base.copy(version = revokes.last().version, members = base.members.filter { it.nodeId !in revoked }, revoked = revoked)
    }

    override fun latest(): RosterBody? = synchronized(lock) {
        db.query("SELECT groupId FROM _roster").mapNotNull { current(it["groupId"] as String) }.maxByOrNull { it.version }
    }

    override fun apply(roster: RosterBody): Boolean = synchronized(lock) {
        if (roster.members.any { Wire.nodeIdOf(it.pubkey) != it.nodeId }) return reject(roster.groupId, roster.version, "a member key does not hash to its nodeId")
        val held = current(roster.groupId)
        val adminKey: String? = if (held != null) {
            if (roster.version <= held.version) return reject(roster.groupId, roster.version, "not newer than v${held.version}")
            held.members.firstOrNull { it.nodeId == held.admin }?.pubkey
        } else {
            val k = roster.members.firstOrNull { it.nodeId == roster.admin }?.pubkey
            when {
                k == null -> null
                roster.admin == selfId && k == selfPubkey -> k
                pinnedKeyOf(roster.admin) == k -> k
                else -> return reject(roster.groupId, roster.version, "first roster: admin ${roster.admin.take(8)} is neither this phone nor a pinned peer")
            }
        }
        if (adminKey == null) return reject(roster.groupId, roster.version, "no key for admin ${roster.admin.take(8)}")
        if (!RosterSigning.verify(roster, adminKey)) return reject(roster.groupId, roster.version, "adminSig does not verify")
        db.tx {
            db.exec("INSERT OR REPLACE INTO _roster(groupId, version, body) VALUES (?,?,?)",
                listOf(roster.groupId, roster.version, MeshJson.encode(roster)))
            db.exec("DELETE FROM _roster_revokes WHERE groupId = ?", listOf(roster.groupId))
        }
        SLog.d(TAG, "roster ${roster.groupId} v${roster.version}: ${roster.members.size} members, ${roster.revoked.size} revoked, admin ${roster.admin.take(8)}")
        true
    }

    override fun applyRevoke(revoke: RevokeBody): Boolean = synchronized(lock) {
        val held = current(revoke.groupId) ?: return reject(revoke.groupId, revoke.version, "REVOKE without a roster")
        if (revoke.version <= held.version) return reject(revoke.groupId, revoke.version, "not newer than v${held.version}")
        val adminKey = held.members.firstOrNull { it.nodeId == held.admin }?.pubkey
            ?: return reject(revoke.groupId, revoke.version, "no key for admin ${held.admin.take(8)}")
        if (!RosterSigning.verify(revoke, adminKey)) return reject(revoke.groupId, revoke.version, "adminSig does not verify")
        db.exec("INSERT OR REPLACE INTO _roster_revokes(groupId, version, body) VALUES (?,?,?)",
            listOf(revoke.groupId, revoke.version, MeshJson.encode(revoke)))
        SLog.d(TAG, "revoke ${revoke.groupId} v${revoke.version}: ${revoke.nodeId.take(8)}")
        true
    }

    override fun isMember(nodeId: String): Boolean = synchronized(lock) {
        all().any { r -> r.members.any { it.nodeId == nodeId } && nodeId !in r.revoked }
    }

    override fun isRevoked(nodeId: String): Boolean = synchronized(lock) { all().any { nodeId in it.revoked } }

    private fun all(): List<RosterBody> = db.query("SELECT groupId FROM _roster").mapNotNull { current(it["groupId"] as String) }

    private fun decodeRoster(s: String): RosterBody? = runCatching { MeshJson.decode<RosterBody>(s) }.getOrNull()

    private fun reject(groupId: String, version: Long, why: String): Boolean {
        SLog.w(TAG, "roster $groupId v$version rejected: $why")
        return false
    }

    companion object { private const val TAG = "RosterStore" }
}
