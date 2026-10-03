package app.gamenative.utils

import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Six hours fresh, at most 24 hours last-known. UI reads never decode the disk cache. */
object GameCompatibilityCache {
    private const val CACHE_SCHEMA_VERSION = 3
    internal val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val inMemoryCache = mutableMapOf<String, GameCompatibilityResponse>()
    private val timestamps = mutableMapOf<String, Long>()
    private var cacheLoaded = false
    private var initializationRequested = false
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()
    private val persistence: CoalescingCacheWriter<Map<String, CachedCompatibilityResponse>> = CoalescingCacheWriter(
        scope = scope,
        snapshot = {
            initialize()
            synchronized(this) {
                CompatibilityCachePolicy.pruneExpired(inMemoryCache, timestamps, System.currentTimeMillis())
                inMemoryCache.mapValues { (name, response) ->
                    CachedCompatibilityResponse(response, timestamps.getValue(name), CACHE_SCHEMA_VERSION, BuildConfig.MODERN_ANDROID)
                }
            }
        },
        write = { snapshot -> PrefManager.persistGameCompatibilityCache(json.encodeToString(snapshot)) },
        onError = { Timber.tag("GameCompatibilityCache").e(it, "Failed to persist compatibility") },
    )

    @Serializable
    data class CachedCompatibilityResponse(
        val response: GameCompatibilityResponse,
        val timestamp: Long,
        val schemaVersion: Int = 1,
        val modernBuild: Boolean = false,
    )

    /** Decode off the reader lock, then briefly publish immutable responses without duplicate DTOs. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (synchronized(this@GameCompatibilityCache) { cacheLoaded }) return@withLock
            val entries = runCatching {
                json.decodeFromString<Map<String, CachedCompatibilityResponse>>(PrefManager.gameCompatibilityCache)
            }.getOrElse {
                Timber.tag("GameCompatibilityCache").w(it, "Failed to load compatibility cache")
                emptyMap()
            }
            val now = System.currentTimeMillis()
            val usable = entries.filterValues {
                it.schemaVersion == CACHE_SCHEMA_VERSION &&
                    it.modernBuild == BuildConfig.MODERN_ANDROID &&
                    CompatibilityCachePolicy.canDisplay(it.timestamp, now)
            }
            synchronized(this@GameCompatibilityCache) {
                if (!cacheLoaded) {
                    usable.forEach { (name, cached) ->
                        // A newer in-memory result always wins over an in-flight disk load.
                        if (name !in inMemoryCache) {
                            inMemoryCache[name] = cached.response
                            timestamps[name] = cached.timestamp
                        }
                    }
                    cacheLoaded = true
                    changes.value++
                    if (usable.size != entries.size) persistence.schedule()
                }
            }
        }
    }

    /** Caller holds the cache monitor. Initialization never blocks that caller. */
    private fun requestInitialization() {
        if (!cacheLoaded && !initializationRequested) {
            initializationRequested = true
            scope.launch { initialize() }
        }
    }

    @Synchronized
    fun getCached(gameName: String): GameCompatibilityResponse? {
        requestInitialization()
        val timestamp = timestamps[gameName] ?: return null
        val now = System.currentTimeMillis()
        if (discardIfExpired(gameName, timestamp, now)) return null
        if (!CompatibilityCachePolicy.isFresh(timestamp, now)) return null
        return inMemoryCache[gameName]
    }

    @Synchronized
    fun getLastKnown(gameName: String): GameCompatibilityResponse? {
        requestInitialization()
        val timestamp = timestamps[gameName] ?: return null
        if (discardIfExpired(gameName, timestamp, System.currentTimeMillis())) return null
        return inMemoryCache[gameName]
    }

    private fun discardIfExpired(gameName: String, timestamp: Long, now: Long): Boolean {
        if (CompatibilityCachePolicy.canDisplay(timestamp, now)) return false
        inMemoryCache.remove(gameName)
        timestamps.remove(gameName)
        persistence.schedule()
        return true
    }

    @Synchronized
    fun cacheAll(responses: Map<String, GameCompatibilityResponse>) {
        val now = System.currentTimeMillis()
        inMemoryCache.putAll(responses)
        responses.keys.forEach { name ->
            timestamps[name] = now
        }
        persistence.schedule()
        changes.value++
    }
}
