package com.hivecore.ui.ports.fakes

import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.SettingsPort

/** An in-memory [SettingsPort]: the key is held in a private field and only its last four characters ever come back. */
class FakeSettingsPort(peers: List<MemberView> = emptyList()) : SettingsPort {
    private var key: String? = null
    var peers: List<MemberView> = peers
    val forgotten = mutableListOf<String>()
    var exports = 0
    var failNext: Throwable? = null

    /** Tests only: whether the stored key equals [k] (the UI never sees this). */
    fun keyEquals(k: String) = key == k

    private fun <T> maybeFail(block: () -> T): Result<T> {
        val f = failNext
        if (f != null) { failNext = null; return Result.failure(f) }
        return Result.success(block())
    }

    override fun hasKey(): Boolean = key != null
    override fun keyHint(): String? = key?.let { "…" + it.takeLast(4) }
    override fun setKey(key: String): Result<Unit> {
        val k = key.trim()
        if (k.length < 8) return Result.failure(IllegalArgumentException("the key is too short"))
        return maybeFail { this.key = k }
    }
    override fun clearKey() { key = null }

    override suspend fun exportLedger(): Result<String> = maybeFail { exports++; "/files/ledger-export-$exports.json" }

    override fun knownPeers(): List<MemberView> = peers
    override suspend fun forgetPeer(nodeId: String): Result<Unit> = maybeFail { forgotten += nodeId; peers = peers.filterNot { it.nodeId == nodeId } }
}
