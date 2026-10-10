package com.hivecore.engine.placement

import com.hivecore.core.Cooldown
import com.hivecore.core.Profile
import com.hivecore.engine.split.SplitConstants

/**
 * Capability cooldowns (spec 8.3, 14, 20.2): `(nodeId, capability) → until`, 10 minutes. Fed on the orchestrator by
 * `MODEL_FAIL` (a missed load deadline included) and by `TASK_RESULT` errors of class oom / model_load / thermal; on
 * a node by its own failed loads. A node publishes its own entries in `Profile.cooldowns` ([of]); [isDown] also
 * honours the cooldowns a peer published in its profile, so the solver skips a phone either way.
 */
class CooldownTable(private val clock: () -> Long = System::currentTimeMillis, val durationMs: Long = SplitConstants.CAPABILITY_COOLDOWN_MS) {
    private val until = HashMap<Pair<String, String>, Long>()

    fun mark(nodeId: String, capability: String, untilTs: Long = clock() + durationMs) = synchronized(until) {
        val k = nodeId to capability
        until[k] = maxOf(until[k] ?: 0L, untilTs)
    }

    fun clear(nodeId: String, capability: String) = synchronized(until) { until.remove(nodeId to capability) }

    /** Whether [nodeId] is cooling down for [capability] now, by this table or by the cooldowns in its [profile]. */
    fun isDown(nodeId: String, capability: String, profile: Profile? = null): Boolean {
        val now = clock()
        val local = synchronized(until) { until[nodeId to capability] }?.let { it > now } ?: false
        return local || profile?.cooldowns.orEmpty().any { it.capability == capability && it.untilTs > now }
    }

    /** [nodeId]'s live cooldowns, sorted by capability (what that node publishes in its PROFILE). */
    fun of(nodeId: String): List<Cooldown> {
        val now = clock()
        return synchronized(until) {
            until.entries.filter { it.key.first == nodeId && it.value > now }.map { Cooldown(it.key.second, it.value) }
        }.sortedBy { it.capability }
    }

    /** Every live cooldown (for the Swarm screen). */
    fun all(): Map<Pair<String, String>, Long> {
        val now = clock()
        return synchronized(until) { until.filterValues { it > now } }
    }
}
