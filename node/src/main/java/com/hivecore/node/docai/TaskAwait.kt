package com.hivecore.node.docai

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Suspends until a Play Services [Task] completes (ML Kit returns Tasks). Written here so the node does not pull in
 * kotlinx-coroutines-play-services for one call. A cancelled Task cancels the coroutine; a failed one rethrows.
 */
internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        when {
            e != null -> cont.resumeWithException(e)
            t.isCanceled -> cont.cancel(CancellationException("task cancelled"))
            else -> cont.resume(t.result)
        }
    }
}
