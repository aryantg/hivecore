package com.hivecore.engine.split

/** v2 plan §17.3 constants used by the split engine, placement and the queue (one place, named). */
object SplitConstants {
    /** Split deadline floor: `max(4 s, 3 × expected + 2 × rtt)` (§9.3 rule 1). */
    const val MIN_DEADLINE_MS = 4_000L
    const val DEADLINE_FACTOR = 3.0
    /** Straggler threshold: 1.5 × expected, only once the queue is empty (§9.3 rule 2). */
    const val STRAGGLER_FACTOR = 1.5
    /** Max attempts per chunk (§9.3 rule 3). */
    const val MAX_ATTEMPTS = 6
    /** A chunk that failed on this many different phones is re-cut once (§9.3 rule 3). */
    const val RECUT_AFTER_PHONES = 2
    /** Capability cooldown after MODEL_FAIL or a TASK_RESULT of class oom / model_load / thermal (§9.3 rule 4). */
    const val CAPABILITY_COOLDOWN_MS = 10 * 60_000L
    /** WAN node wake grace before removal (§10 "Node asleep"). */
    const val WAN_WAKE_GRACE_MS = 20_000L
    /** Relay outage: WAN attempts wait this long before they fault (§10 "Relay down"). */
    const val RELAY_OUTAGE_WAIT_MS = 60_000L
    /** Output hash mismatches in one job that remove the phone (§10). */
    const val MAX_HASH_MISMATCHES = 3
    /** Schema validation failures (across at least [SCHEMA_FAIL_MIN_PHONES] phones) that trigger the Escalation Gate (§7.1). */
    const val SCHEMA_FAILS_TO_ESCALATE = 3
    const val SCHEMA_FAIL_MIN_PHONES = 2
    /** Replicas of an LLM / VLM chunk when the job does not say (§6.2): 2, and a 3rd on disagreement. */
    const val DEFAULT_LLM_REPLICAS = 2
    /** How long the orchestrator waits for MODEL_READY / MODEL_FAIL (the MODEL_REQ `deadlineMs`). */
    const val MODEL_REQ_DEADLINE_MS = 60_000L
    /** TASK_RESULT error classes that start a capability cooldown (§9.3 rule 4). */
    val COOLDOWN_CLASSES = setOf("oom", "model_load", "thermal")
}
