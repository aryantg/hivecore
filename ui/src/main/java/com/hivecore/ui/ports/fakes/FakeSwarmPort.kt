package com.hivecore.ui.ports.fakes

import com.hivecore.core.Link
import com.hivecore.core.LinkKind
import com.hivecore.core.LlmBackend
import com.hivecore.core.Profile
import com.hivecore.ui.ports.InviteView
import com.hivecore.ui.ports.JoinRequestView
import com.hivecore.ui.ports.MemberView
import com.hivecore.ui.ports.RosterView
import com.hivecore.ui.ports.SasView
import com.hivecore.ui.ports.SwarmPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * An in-memory [SwarmPort]: a roster the test mutates, scripted peer profiles, and a pairing flow that produces a
 * SAS for [join] and pins on [confirmSas]. [createInvite] mints `swarm://join?...` links with the given clock.
 */
class FakeSwarmPort(
    override val selfId: String = "self",
    roster: RosterView = RosterView("g1", 1, "self", listOf(MemberView("self", "This phone", admin = true))),
    peers: List<Profile> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SwarmPort {
    val rosterState = MutableStateFlow(roster)
    override val roster: StateFlow<RosterView> = rosterState
    val peersState = MutableStateFlow(peers)
    override val peers: StateFlow<List<Profile>> = peersState
    val joinsState = MutableStateFlow<List<JoinRequestView>>(emptyList())
    override val pendingJoins: StateFlow<List<JoinRequestView>> = joinsState
    val sasState = MutableStateFlow<List<SasView>>(emptyList())
    override val sasPrompts: StateFlow<List<SasView>> = sasState

    val confirmed = mutableListOf<Pair<String, Boolean>>()
    val approved = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    val handedOver = mutableListOf<String>()
    var failNext: Throwable? = null
    private var inviteSeq = 0

    private fun <T> maybeFail(block: () -> T): Result<T> {
        val f = failNext
        if (f != null) { failNext = null; return Result.failure(f) }
        return Result.success(block())
    }

    override suspend fun createInvite(): Result<InviteView> = maybeFail {
        inviteSeq++
        InviteView("swarm://join?g=${rosterState.value.groupId}&k=MCowBQYDK2VwAyEAfakePubKey$inviteSeq&t=tok$inviteSeq", clock() + 10 * 60_000L)
    }

    override suspend fun join(link: String): Result<SasView> {
        if (!link.startsWith("swarm://join?")) return Result.failure(IllegalArgumentException("not an invite link"))
        return maybeFail {
            val sas = SasView("hs-${sasState.value.size + 1}", "Sales", "482 913")
            sasState.update { it + sas }
            sas
        }
    }

    override suspend fun confirmSas(handshakeId: String, matches: Boolean): Result<Unit> = maybeFail {
        confirmed += handshakeId to matches
        val prompt = sasState.value.firstOrNull { it.handshakeId == handshakeId }
        sasState.update { list -> list.filterNot { it.handshakeId == handshakeId } }
        if (matches && prompt != null) rosterState.update { r ->
            if (r.members.any { it.name == prompt.peerName }) r
            else r.copy(version = r.version + 1, members = r.members + MemberView("peer-${prompt.handshakeId}", prompt.peerName))
        }
    }

    override suspend fun approve(nodeId: String): Result<Unit> = maybeFail {
        approved += nodeId
        val j = joinsState.value.firstOrNull { it.nodeId == nodeId }
        joinsState.update { list -> list.filterNot { it.nodeId == nodeId } }
        if (j != null) rosterState.update { r -> r.copy(version = r.version + 1, members = r.members + MemberView(j.nodeId, j.name)) }
    }

    override suspend fun revoke(nodeId: String): Result<Unit> = maybeFail {
        revoked += nodeId
        rosterState.update { r -> r.copy(version = r.version + 1, members = r.members.filterNot { it.nodeId == nodeId }) }
        peersState.update { list -> list.filterNot { it.nodeId == nodeId } }
    }

    override suspend fun handOver(nodeId: String): Result<Unit> = maybeFail {
        handedOver += nodeId
        rosterState.update { r -> r.copy(version = r.version + 1, adminId = nodeId, members = r.members.map { it.copy(admin = it.nodeId == nodeId) }) }
    }

    companion object {
        /** Three phones for previews: this phone plus Sales on LAN and Accounts over the relay. */
        fun sample(now: Long = System.currentTimeMillis()): FakeSwarmPort = FakeSwarmPort(
            roster = RosterView("g1", 3, "self", listOf(MemberView("self", "This phone", admin = true), MemberView("sales", "Sales"), MemberView("accounts", "Accounts"))),
            peers = listOf(
                Profile("sales", now - 4_000, LlmBackend.NPU, 18.0, idle = true, charging = true, batteryPct = 64, batteryTempC = 31f, thermal = 0,
                    workerOptIn = true, eligible = true, heavyOk = true, queueDepth = 2, big = "llm", warm = listOf("whisper-base", "yolo11n"),
                    link = Link.defaultFor(LinkKind.LAN)),
                Profile("accounts", now - 40_000, LlmBackend.NPU, 11.0, idle = false, batteryPct = 31, batteryTempC = 36f, thermal = 2,
                    workerOptIn = true, eligible = false, queueDepth = 0, big = "none", link = Link.defaultFor(LinkKind.WAN_RELAY),
                    cooldowns = listOf(com.hivecore.core.Cooldown("llm.json", now + 6 * 60_000L))),
            ),
            clock = { now },
        ).also { it.joinsState.value = listOf(JoinRequestView("hr", "HR", now - 30_000)) }
    }
}
