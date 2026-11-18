package app.gamenative.data

import app.gamenative.PrefManager
import app.gamenative.steam.SteamCollectionParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

object SteamCollectionRepository {
    private val json = Json { ignoreUnknownKeys = true }

    // null = not yet loaded (show all); empty = loaded but none; non-empty = loaded
    private val _collections = MutableStateFlow<List<SteamCollection>?>(null)
    val collections: StateFlow<List<SteamCollection>?> = _collections.asStateFlow()

    private val _skippedDynamic = MutableStateFlow(false)
    val skippedDynamic: StateFlow<Boolean> = _skippedDynamic.asStateFlow()

    /** Populate from the persisted JSON snapshot so the filter works offline / before fetch. */
    fun loadFromCache() {
        val raw = PrefManager.librarySteamCollectionsCache
        if (raw.isEmpty()) return
        try {
            _collections.value = json.decodeFromString<List<SteamCollection>>(raw)
            _skippedDynamic.value = PrefManager.librarySteamCollectionsSkippedDynamic
        } catch (t: Throwable) {
            Timber.tag("SteamCollectionRepo").w(t, "Failed to load cached collections; clearing corrupt cache")
            _collections.value = null
            PrefManager.librarySteamCollectionsCache = ""
            PrefManager.librarySteamCollectionsSkippedDynamic = false
            _skippedDynamic.value = false
        }
    }

    /** Set the freshly-fetched collections and persist them. */
    fun update(result: SteamCollectionParser.ParseResult) {
        _collections.value = result.collections
        _skippedDynamic.value = result.skippedDynamicCount > 0
        PrefManager.librarySteamCollectionsSkippedDynamic = _skippedDynamic.value
        try {
            PrefManager.librarySteamCollectionsCache = json.encodeToString(result.collections)
        } catch (t: Throwable) {
            Timber.tag("SteamCollectionRepo").w(t, "Failed to persist collections")
        }
    }

    fun clear() {
        _collections.value = null
        _skippedDynamic.value = false
        PrefManager.librarySteamCollectionsCache = ""
        PrefManager.librarySteamCollectionsSkippedDynamic = false
    }
}
