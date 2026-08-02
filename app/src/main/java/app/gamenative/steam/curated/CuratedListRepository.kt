package app.gamenative.steam.curated

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.gamenative.PrefManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

internal object CuratedListRepository {

    private const val CACHE_VERSION = 1
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    private val refreshMutex = Mutex()
    private val _curatedLists = MutableStateFlow<Map<String, Set<Int>>?>(null)
    val curatedLists: StateFlow<Map<String, Set<Int>>?> = _curatedLists.asStateFlow()

    @Volatile private var lastRefreshMs = 0L
    @Volatile private var lastAttemptMs = 0L

    suspend fun loadFromCache(context: Context) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val cached = decodeCache(PrefManager.libraryCuratedListsCache)
            val seeds = loadAllSeeds(context)
            val refreshedCache = cached?.takeIf { it.refreshedAtMs > 0L }
            val lists = seeds + refreshedCache?.lists.orEmpty()

            lastRefreshMs = cached?.refreshedAtMs ?: 0L
            _curatedLists.value = lists

            if (cached == null || cached.lists != lists) {
                persist(lists, lastRefreshMs)
            }
        }
    }

    suspend fun refreshFourThreeIfNeeded() {
        refreshFourThreeIfNeeded(
            fetch = FourThreeCuratorSource::fetch,
            nowMs = System::currentTimeMillis,
        )
    }

    @VisibleForTesting
    internal suspend fun refreshFourThreeIfNeeded(
        fetch: suspend () -> Set<Int>?,
        nowMs: () -> Long,
    ) {
        refreshMutex.lock()
        try {
            val now = nowMs()
            if (!isRefreshDue(now)) return
            lastAttemptMs = now

            val fetched = try {
                fetch()
            } catch (e: CancellationException) {
                lastAttemptMs = 0L
                throw e
            } ?: return
            val lists = (_curatedLists.value ?: emptyMap()) +
                (CuratedListDescriptor.FOUR_THREE.id to fetched)

            _curatedLists.value = lists
            lastRefreshMs = now
            persist(lists, now)
            Timber.tag("CuratedListRepo").i("Refreshed 4:3 curator list: %d app IDs", fetched.size)
        } finally {
            refreshMutex.unlock()
        }
    }

    @VisibleForTesting
    internal fun isRefreshDue(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastCheckMs = maxOf(lastRefreshMs, lastAttemptMs)
        if (lastCheckMs <= 0L || lastCheckMs > nowMs) return true
        return nowMs - lastCheckMs >= CACHE_TTL_MS
    }

    @VisibleForTesting
    internal suspend fun resetForTesting() {
        refreshMutex.withLock {
            _curatedLists.value = null
            lastRefreshMs = 0L
            lastAttemptMs = 0L
            PrefManager.libraryCuratedListsCache = ""
        }
    }

    private fun loadAllSeeds(context: Context): Map<String, Set<Int>> {
        return buildMap {
            CuratedListDescriptor.entries.forEach { descriptor ->
                loadSeed(context, descriptor)?.let { put(descriptor.id, it) }
            }
        }
    }

    private fun loadSeed(context: Context, descriptor: CuratedListDescriptor): Set<Int>? {
        return try {
            val body = context.assets.open(descriptor.seedAsset).bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(body).jsonObject
            root["appIds"]
                ?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { id -> id > 0 } }
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.tag("CuratedListRepo").w(e, "Bundled seed '%s' unavailable", descriptor.seedAsset)
            null
        }
    }

    private fun persist(lists: Map<String, Set<Int>>, refreshedAtMs: Long) {
        try {
            PrefManager.libraryCuratedListsCache = encodeCache(lists, refreshedAtMs)
        } catch (e: Exception) {
            Timber.tag("CuratedListRepo").w(e, "Failed to persist curated lists")
        }
    }

    @VisibleForTesting
    internal data class CachedLists(
        val refreshedAtMs: Long,
        val lists: Map<String, Set<Int>>,
    )

    @VisibleForTesting
    internal fun encodeCache(lists: Map<String, Set<Int>>, refreshedAtMs: Long): String {
        return buildJsonObject {
            put("version", JsonPrimitive(CACHE_VERSION))
            put("refreshedAtMs", JsonPrimitive(refreshedAtMs))
            put(
                "lists",
                buildJsonObject {
                    lists.forEach { (id, appIds) ->
                        put(id, JsonArray(appIds.sorted().map(::JsonPrimitive)))
                    }
                },
            )
        }.toString()
    }

    @VisibleForTesting
    internal fun decodeCache(raw: String): CachedLists? {
        if (raw.isEmpty()) return null
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != CACHE_VERSION) return null
            val refreshedAtMs = root["refreshedAtMs"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= 0L }
                ?: return null
            val lists = LinkedHashMap<String, Set<Int>>()
            for ((id, value) in root["lists"]?.jsonObject.orEmpty()) {
                if (id !in CuratedListDescriptor.byId) continue
                val appIds = (value as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.takeIf { appId -> appId > 0 } }
                    ?.toSet()
                if (!appIds.isNullOrEmpty()) lists[id] = appIds
            }
            if (lists.isEmpty()) return null
            CachedLists(refreshedAtMs, lists)
        } catch (e: Exception) {
            Timber.tag("CuratedListRepo").w(e, "Ignoring invalid curated-list cache")
            null
        }
    }
}
