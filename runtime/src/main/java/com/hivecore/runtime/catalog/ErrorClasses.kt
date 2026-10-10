package com.hivecore.runtime.catalog

/**
 * Error classes a node reports (spec 14 "Classes of error", 8.3 `MODEL_FAIL`): `MODEL_FAIL.class` is one of
 * {oom, model_load, thermal, unsupported, timeout} plus `runtime` for a missed load deadline; `TASK_RESULT.error.class`
 * adds schema, yielded and blobs_missing. oom, model_load and thermal start the 10-minute capability cooldown.
 */
object ErrorClasses {
    const val OOM = "oom"
    const val MODEL_LOAD = "model_load"
    const val THERMAL = "thermal"
    const val UNSUPPORTED = "unsupported"
    const val TIMEOUT = "timeout"
    /** A MODEL_REQ whose deadline passed without a reply (spec 8.3). */
    const val RUNTIME = "runtime"
    const val SCHEMA = "schema"
    const val YIELDED = "yielded"
    const val BLOBS_MISSING = "blobs_missing"

    /** What [com.hivecore.runtime.models.CapabilityPool.ensure] may fail with. */
    val MODEL_FAIL = setOf(OOM, MODEL_LOAD, THERMAL, UNSUPPORTED, TIMEOUT)

    /** Classes that put `(node, capability)` on cooldown (spec 14, "Model won't load, OOM, thermal shutdown"). */
    val COOLDOWN = setOf(OOM, MODEL_LOAD, THERMAL)

    /** Classifies a throwable; [ModelLoadException] carries its class, anything else is read from the message. */
    fun of(t: Throwable): String = when (t) {
        is ModelLoadException -> t.errorClass
        is OutOfMemoryError -> OOM
        is kotlinx.coroutines.TimeoutCancellationException -> TIMEOUT
        else -> of(t.message ?: t.javaClass.simpleName)
    }

    /** Conservative message classification: unknown → `model_load` (the load did fail). */
    fun of(message: String): String {
        val m = message.lowercase()
        return when {
            m == YIELDED || m.startsWith("yielded") -> YIELDED
            "out of memory" in m || "outofmemory" in m || Regex("\\boom\\b").containsMatchIn(m) || "8003" in m || "map error" in m -> OOM
            "thermal" in m || "too hot" in m || "overheat" in m -> THERMAL
            "schema" in m -> SCHEMA
            "timed out" in m || "timeout" in m || "deadline" in m -> TIMEOUT
            "not installed" in m || "no runtime" in m || "unsupported" in m || "not in" in m && "catalog" in m -> UNSUPPORTED
            else -> MODEL_LOAD
        }
    }
}

/** A failed model load with its class already decided (what [com.hivecore.runtime.models.CapabilityPool.ensure] throws). */
class ModelLoadException(val errorClass: String, message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    init { require(errorClass in ErrorClasses.MODEL_FAIL) { "not a MODEL_FAIL class: $errorClass" } }
}
