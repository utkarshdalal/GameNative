package app.gamenative.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

fun <T> CoroutineScope.asyncIsolated(block: suspend CoroutineScope.() -> T): Deferred<T> {
    val deferred = CompletableDeferred<T>()
    val job = launch {
        try {
            deferred.complete(block())
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
        }
    }
    deferred.invokeOnCompletion { if (deferred.isCancelled) job.cancel() }
    return deferred
}
