package app.gamenative.utils

import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Serialize batches across library, details and recommendations, not entire library passes. */
internal class CompatibilityBatchLoader(
    private val cached: (String) -> GameCompatibilityResponse?,
    private val publish: (Map<String, GameCompatibilityResponse>) -> Unit,
    private val fetch: suspend (List<String>, String) -> Map<String, GameCompatibilityResponse>?,
    private val changed: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val timeoutMillis: Long = 45_000L,
    private val retryDelay: (Long) -> Long = { CompatibilityRetryPolicy.withJitter(it) },
) {
    private val mutex = Mutex()
    private val failures = ConcurrentHashMap<String, Long>()
    private val loading = ConcurrentHashMap<String, Int>()

    @Volatile private var serverRetryAt = 0L
    fun hasFailed(name: String) = failures.containsKey(name)
    fun isLoading(name: String) = loading.containsKey(name)
    fun isRefreshing() = loading.isNotEmpty()

    suspend fun refresh(gameNames: List<String>, gpu: String, force: Boolean = false): Boolean {
        val names = gameNames.filter { it.isNotBlank() }.distinct()
        val missing = names.filter { force || cached(it) == null }
        val pending = missing.filter { name -> force || failures[name]?.let { clock() >= it } != false }
        if (pending.isEmpty()) return missing.isEmpty()
        if (gpu.isBlank() || gpu == "Unknown GPU" || clock() < serverRetryAt) {
            markFailed(pending)
            changed()
            return false
        }
        pending.forEach { name -> loading.compute(name) { _, count -> (count ?: 0) + 1 } }
        changed()
        var complete = true
        try {
            for ((index, batch) in pending.chunked(100).withIndex()) {
                val succeeded = mutex.withLock {
                    val uncached = batch.filter { force || cached(it) == null }
                    // Another consumer may have failed these names while we waited for the lock.
                    val required = uncached.filter { force || failures[it]?.let { deadline -> clock() >= deadline } != false }
                    if (required.size != uncached.size) complete = false
                    if (required.isEmpty()) return@withLock true
                    fun failRemaining() {
                        markFailed(pending.drop(index * 100).filter { force || cached(it) == null })
                    }
                    // Another consumer may have received a 429 while we waited for the mutex.
                    if (clock() < serverRetryAt) {
                        failRemaining()
                        return@withLock false
                    }
                    val fetched = fetchWithRetry(required, gpu) ?: run {
                        // Publish the cooldown before releasing the lock to a queued caller.
                        failRemaining()
                        return@withLock false
                    }
                    val results = required.mapNotNull { name ->
                        fetched[name]?.takeIf { !it.state.isNullOrBlank() }?.let { name to it }
                    }.toMap()
                    required.forEach { name ->
                        if (name in results) failures.remove(name) else markFailed(listOf(name))
                    }
                    if (results.size != required.size) complete = false
                    if (results.isNotEmpty()) publish(results)
                    changed()
                    true
                }
                if (!succeeded) {
                    complete = false
                    break
                }
            }
        } finally {
            pending.forEach { name -> loading.computeIfPresent(name) { _, count -> (count - 1).takeIf { it > 0 } } }
            changed()
        }
        return complete && names.all { cached(it) != null }
    }

    private fun markFailed(names: List<String>) {
        val deadline = maxOf(serverRetryAt, CompatibilityRetryPolicy.deadline(clock(), 60_000L))
        names.forEach { failures[it] = deadline }
    }

    private suspend fun fetchWithRetry(names: List<String>, gpu: String): Map<String, GameCompatibilityResponse>? =
        withTimeoutOrNull(timeoutMillis) {
            val started = clock()
            repeat(3) { attempt ->
                try {
                    return@withTimeoutOrNull fetch(names, gpu).also { if (it != null) serverRetryAt = 0L }
                } catch (error: GameCompatibilityService.RateLimited) {
                    val delayMillis = retryDelay(error.retryAfterMillis)
                    serverRetryAt = CompatibilityRetryPolicy.deadline(clock(), delayMillis)
                    if (attempt == 2 || delayMillis >= timeoutMillis - (clock() - started)) return@withTimeoutOrNull null
                    wait(delayMillis)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@withTimeoutOrNull null
                }
            }
            null
        }
}
