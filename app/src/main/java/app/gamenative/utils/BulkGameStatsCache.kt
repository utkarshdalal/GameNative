package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** UI reads never wait for disk, JSON decoding, or a writer. Snapshots are immutable. */
internal class BulkGameStatsCache(
    private val read: () -> String,
    private val write: suspend (String) -> Unit,
    private val scope: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retryDelay: () -> Long = { CompatibilityRetryPolicy.withJitter(60_000L) },
) {
    private val refreshMutex = Mutex()

    @Volatile private var loaded = false

    @Volatile private var snapshot: Snapshot? = null
    private var retryAt = 0L
    private var serverRetryAt = 0L

    @Serializable
    private data class Snapshot(
        val schema: Int = 0,
        val scope: String = "",
        val timestamp: Long,
        val stats: Map<GameSource, Map<String, DeviceGameStats>>,
    )

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        refreshMutex.withLock {
            if (!loaded) {
                snapshot = runCatching { Json.decodeFromString<Snapshot>(read()) }.getOrNull()
                    ?.takeIf { it.schema == 2 && it.scope == scope && it.timestamp <= clock() }
                loaded = true
            }
        }
    }

    fun getAll(): Map<GameSource, Map<String, DeviceGameStats>> = snapshot?.stats.orEmpty()
    fun isAvailable(): Boolean = snapshot != null

    suspend fun refresh(force: Boolean = false, fetch: suspend () -> Map<GameSource, Map<String, DeviceGameStats>>?): Boolean =
        withContext(Dispatchers.IO) {
            initialize()
            refreshMutex.withLock {
                val current = snapshot
                if (!force && current != null && clock() - current.timestamp in 0 until TTL_MS) return@withLock true
                // Explicit refresh bypasses ordinary failure cooldown, never a server deadline.
                if (clock() < serverRetryAt || (!force && clock() < retryAt)) return@withLock false
                val result = try {
                    fetch()
                } catch (error: GameCompatibilityService.RateLimited) {
                    serverRetryAt = CompatibilityRetryPolicy.deadline(clock(), error.retryAfterMillis)
                    null
                }
                if (result == null) {
                    retryAt = CompatibilityRetryPolicy.deadline(clock(), retryDelay())
                    return@withLock false
                }
                retryAt = 0L
                serverRetryAt = 0L
                val fresh = Snapshot(schema = 2, scope = scope, timestamp = clock(), stats = result)
                snapshot = fresh
                persist(fresh)
                true
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            retryAt = 0L
            snapshot = null
            loaded = true
            persist(null)
        }
    }

    private suspend fun persist(value: Snapshot?) {
        try {
            // The refresh mutex orders writes but is never acquired by readers.
            write(value?.let { Json.encodeToString(it) } ?: "{}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Timber.w(error, "Failed to persist compatibility stats")
        }
    }

    companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
    }
}
