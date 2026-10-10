package com.hivecore.engine.journal

import com.hivecore.core.LinkKind
import com.hivecore.core.Profile

/**
 * Picks the deputy (§13.4): highest uptime, LAN preferred. LAN-class links come first (LAN, then Wi-Fi Direct; a
 * profile with no measured link counts as LAN, as the profile documents), then the highest uptime, then nodeId for a
 * stable choice. Streaming every commit is cheap on a local link, so a local phone beats a slightly longer-lived
 * remote one.
 */
object DeputySelector {
    fun pick(profiles: List<Profile>, uptimeMs: Map<String, Long>, selfId: String, exclude: Set<String> = emptySet()): String? =
        profiles.asSequence()
            .filter { it.nodeId != selfId && it.nodeId !in exclude }
            .distinctBy { it.nodeId }
            .sortedWith(compareBy<Profile> { linkRank(it) }.thenByDescending { uptimeMs[it.nodeId] ?: 0L }.thenBy { it.nodeId })
            .firstOrNull()?.nodeId

    private fun linkRank(p: Profile): Int = when (p.link?.kind ?: LinkKind.LAN) {
        LinkKind.LAN -> 0
        LinkKind.P2P -> 1
        else -> 2
    }
}
