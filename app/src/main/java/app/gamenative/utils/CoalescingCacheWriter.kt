package app.gamenative.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coalesce bursts without delaying in-memory publication. One writer awaits disk writes in
 * order; serialization and IO never run in the caller's cache lock. A fixed window (rather
 * than trailing debounce) still persists progress during a continuous multi-batch refresh.
 */
internal class CoalescingCacheWriter<T>(
    scope: CoroutineScope,
    private val snapshot: suspend () -> T,
    private val write: suspend (T) -> Unit,
    private val onError: (Exception) -> Unit,
    private val wait: suspend () -> Unit = { delay(2_000L) },
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                wait()
                // All requests up to the snapshot are covered by this write.
                requests.tryReceive()
                try {
                    write(snapshot())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    onError(error)
                }
            }
        }
    }

    fun schedule() {
        requests.trySend(Unit)
    }
}
