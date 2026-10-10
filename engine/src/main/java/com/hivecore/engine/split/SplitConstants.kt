package com.hivecore.engine.split

/** Spec section 20.2 constants used by the split engine, placement and the queue (one place, named). */
object SplitConstants {
    /** Split deadline: `max(4 s, 3 × expected + 2 × rtt)` (section 11.2 step 3). */
    const val MIN_DEADLINE_MS = 4_000L
    const val DEADLINE_FACTOR = 3.0
    /** Straggler threshold: 1.5 × expected, only once the queue is empty (section 11.2 step 5). */
    const val STRAGGLER_FACTOR = 1.5
    /** Slots per phone: one running + one prefetched (section 11.2 step 1). */
    const val SLOTS = 2
    /** Max attempts per chunk (section 14). */
    const val MAX_ATTEMPTS = 6
    /** A chunk that failed on this many different phones is re-cut once (section 14). */
    const val RECUT_AFTER_PHONES = 2
    /** Capability cooldown after MODEL_FAIL or a TASK_RESULT of class oom / model_load / thermal (section 14). */
    const val CAPABILITY_COOLDOWN_MS = 10 * 60_000L
    /** WAN node wake grace before removal (section 14 "Node asleep"). */
    const val WAN_WAKE_GRACE_MS = 20_000L
    /** Relay outage: WAN attempts wait this long before they fault (section 14 "Relay down"). */
    const val RELAY_OUTAGE_WAIT_MS = 60_000L
    /** Missed deadlines in one job that drop a phone (section 11.4 "drop the phone after 3 misses"). */
    const val MISSES_TO_DROP = 3
    /** Output hash mismatches in one job that remove the phone (section 12.1). */
    const val MAX_HASH_MISMATCHES = 3
    /** Schema validation failures (across at least [SCHEMA_FAIL_MIN_PHONES] phones) that trigger the Escalation Gate (section 12.1). */
    const val SCHEMA_FAILS_TO_ESCALATE = 3
    const val SCHEMA_FAIL_MIN_PHONES = 2
    /** Replicas of an LLM / VLM chunk when the job does not say (section 12.1): 2, and a 3rd on disagreement. */
    const val DEFAULT_LLM_REPLICAS = 2
    /** The third phone that decides a replica disagreement (section 12.1). */
    const val MAX_REPLICAS = 3
    /** VOTE confidence below which the fan-out step goes to the Escalation Gate (section 15.1). */
    const val VOTE_MIN_CONFIDENCE = 0.6
    /** How long the orchestrator waits for MODEL_READY / MODEL_FAIL (the MODEL_REQ `deadlineMs`). */
    const val MODEL_REQ_DEADLINE_MS = 60_000L
    /** Chunk sizing (section 10.5): target work per chunk and the minimum chunks per eligible phone. */
    const val TARGET_CHUNK_MS = 30_000.0
    const val MIN_CHUNKS_PER_PHONE = 2
    /** TREE: fixed sentences per chunk and picks (section 10.5, 20.2). */
    const val TREE_SENTENCES = 40
    const val TREE_PICKS = 5
    /** Audio window cap in seconds (section 10.2). */
    const val MAX_AUDIO_S = 28
    /** Default per-unit costs when neither the job, the profile nor the catalog says (section 20.2). */
    const val UNIT_MS_ROW = 0.05
    const val UNIT_MS_AUDIO_S = 100.0
    const val UNIT_MS_TILE = 50.0
    const val UNIT_MS_LLM = 4_000.0
    const val UNIT_MS_VLM = 1_500.0
    const val UNIT_MS_HEAVY = 3_000.0
    const val UNIT_MS_OTHER = 200.0
    /** TASK_RESULT error classes (section 14). */
    const val ERR_OOM = "oom"
    const val ERR_MODEL_LOAD = "model_load"
    const val ERR_THERMAL = "thermal"
    const val ERR_SCHEMA = "schema"
    const val ERR_RUNTIME = "runtime"
    const val ERR_YIELDED = "yielded"
    const val ERR_BLOBS_MISSING = "blobs_missing"
    val ERROR_CLASSES = setOf(ERR_OOM, ERR_MODEL_LOAD, ERR_THERMAL, ERR_SCHEMA, ERR_RUNTIME, ERR_YIELDED, ERR_BLOBS_MISSING)
    /** TASK_RESULT error classes that start a capability cooldown (section 14). */
    val COOLDOWN_CLASSES = setOf(ERR_OOM, ERR_MODEL_LOAD, ERR_THERMAL)

    /**
     * The error class of a TASK_RESULT that carries only the free-text `error` (phase 0 nodes): the class name itself,
     * or "<class>: detail"; "released items are missing" is [ERR_BLOBS_MISSING]; anything else is [ERR_RUNTIME].
     */
    fun classify(error: String?): String {
        if (error == null) return ERR_RUNTIME
        val head = error.substringBefore(':').trim().lowercase()
        if (head in ERROR_CLASSES) return head
        if (error.contains("released items are missing")) return ERR_BLOBS_MISSING
        return ERR_RUNTIME
    }
}
