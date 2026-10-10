package com.hivecore.mesh

import com.hivecore.core.SqlDb
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** One pinned peer: the node key is the trust root; `approvalPubkey` and `holds` follow the last verified HELLO. */
data class PinnedPeer(
    val nodeId: String,
    val name: String,
    val pubkey: String,
    val approvalPubkey: String?,
    val pinnedAt: Long,
    val lastSeen: Long?,
    val lastTransport: String?,
    val lastEndpoint: String?,
    val holds: List<String>
)

/**
 * Plan 10 `_peers(nodeId PK, name, pubkey, approvalPubkey, pinnedAt, lastSeen, lastTransport, lastEndpoint)`
 * plus `holds` (JSON array) so the NoNode screen can list what a silent node holds after a restart.
 * Pinning is explicit (pairing mode); "forget" unpins. Safe to construct on any [SqlDb] (creates the table).
 */
class PeerStore(private val db: SqlDb) {
    init {
        db.exec(
            """CREATE TABLE IF NOT EXISTS _peers(
                 nodeId TEXT PRIMARY KEY, name TEXT NOT NULL, pubkey TEXT NOT NULL, approvalPubkey TEXT,
                 pinnedAt INTEGER NOT NULL, lastSeen INTEGER, lastTransport TEXT, lastEndpoint TEXT, holds TEXT NOT NULL DEFAULT '[]')"""
        )
    }

    /** Pins (or re-pins, replacing every field) a node key. */
    fun pin(nodeId: String, name: String, pubkey: String, approvalPubkey: String?, holds: List<String>, now: Long,
            transport: String? = null, endpoint: String? = null) {
        db.exec(
            "INSERT OR REPLACE INTO _peers(nodeId, name, pubkey, approvalPubkey, pinnedAt, lastSeen, lastTransport, lastEndpoint, holds) VALUES (?,?,?,?,?,?,?,?,?)",
            listOf(nodeId, name, pubkey, approvalPubkey, now, now, transport, endpoint, encodeHolds(holds))
        )
    }

    fun forget(nodeId: String) = db.exec("DELETE FROM _peers WHERE nodeId = ?", listOf(nodeId))

    fun isPinned(nodeId: String): Boolean = get(nodeId) != null

    fun get(nodeId: String): PinnedPeer? =
        db.query("SELECT * FROM _peers WHERE nodeId = ?", listOf(nodeId)).firstOrNull()?.let(::row)

    fun byEndpoint(transport: String, endpointId: String): PinnedPeer? =
        db.query("SELECT * FROM _peers WHERE lastTransport = ? AND lastEndpoint = ? ORDER BY lastSeen DESC LIMIT 1",
            listOf(transport, endpointId)).firstOrNull()?.let(::row)

    fun all(): List<PinnedPeer> = db.query("SELECT * FROM _peers ORDER BY name, nodeId").map(::row)

    /**
     * A verified HELLO from an already-pinned node refreshes name, `approvalPubkey` (may become null after a
     * fingerprint re-enrolment until the new key exists), `holds` and the last-seen endpoint. The node key is
     * never changed here; a HELLO with a different key is rejected by the router.
     */
    fun updateFromHello(nodeId: String, name: String, approvalPubkey: String?, holds: List<String>, lastSeen: Long,
                        transport: String?, endpoint: String?) {
        db.exec(
            "UPDATE _peers SET name = ?, approvalPubkey = ?, holds = ?, lastSeen = ?, lastTransport = ?, lastEndpoint = ? WHERE nodeId = ?",
            listOf(name, approvalPubkey, encodeHolds(holds), lastSeen, transport, endpoint, nodeId)
        )
    }

    /** Any verified message from the node refreshes lastSeen and the endpoint it came from. */
    fun touch(nodeId: String, lastSeen: Long, transport: String?, endpoint: String?) {
        db.exec("UPDATE _peers SET lastSeen = ?, lastTransport = ?, lastEndpoint = ? WHERE nodeId = ?",
            listOf(lastSeen, transport, endpoint, nodeId))
    }

    private fun row(r: Map<String, Any?>): PinnedPeer = PinnedPeer(
        nodeId = r["nodeId"] as String,
        name = r["name"] as String,
        pubkey = r["pubkey"] as String,
        approvalPubkey = r["approvalPubkey"] as String?,
        pinnedAt = (r["pinnedAt"] as Number).toLong(),
        lastSeen = (r["lastSeen"] as Number?)?.toLong(),
        lastTransport = r["lastTransport"] as String?,
        lastEndpoint = r["lastEndpoint"] as String?,
        holds = decodeHolds(r["holds"] as String?)
    )

    private fun encodeHolds(holds: List<String>): String =
        MeshJson.json.encodeToString(ListSerializer(String.serializer()), holds)

    private fun decodeHolds(s: String?): List<String> = try {
        if (s.isNullOrBlank()) emptyList() else MeshJson.json.decodeFromString(ListSerializer(String.serializer()), s)
    } catch (_: Exception) {
        emptyList()
    }
}
