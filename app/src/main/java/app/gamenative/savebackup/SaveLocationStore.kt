package app.gamenative.savebackup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gamenative.PrefManager
import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Persists exactly one [SaveLocation] per game, keyed by the source-prefixed `LibraryItem.appId`
 * (e.g. `STEAM_440`, `GOG_1207658924`).
 *
 * A persisted [SaveLocation] is stored as compact JSON and never contains an absolute path
 * (Requirement 1.4). Writing a location for a key replaces any previously persisted value for that
 * key (Requirement 1.2). Reading a key with no persisted value returns `null` (Requirement 1.6).
 */
interface SaveLocationStore {
    /** The persisted [SaveLocation] for [appId], or `null` when unset (Requirement 1.6). */
    suspend fun get(appId: String): SaveLocation?

    /**
     * Persist [location] for [appId], replacing any previously persisted value (Requirement 1.2).
     *
     * This awaits durable persistence and throws if the write fails; on failure the previously
     * persisted value is left intact (Requirement 3.11).
     */
    suspend fun put(appId: String, location: SaveLocation)

    /**
     * Forget any persisted [SaveLocation] for [appId] so the next resolution runs the source
     * strategy / container browser from scratch. A no-op when nothing is persisted.
     *
     * Awaits durable persistence and throws if the write fails, mirroring [put].
     */
    suspend fun remove(appId: String)
}

/**
 * [SaveLocationStore] backed by the same [DataStore] that [PrefManager] owns.
 *
 * **Write path (Requirement 3.11, Property 1).** Unlike `PrefManager.setPref` — which launches
 * `dataStore.edit { ... }` fire-and-forget on a detached IO scope, swallowing both the failure
 * signal and any ordering guarantee — [put] **awaits** `dataStore.edit` directly. `DataStore.edit`
 * completes only after the change is durably persisted and throws if the transform or disk write
 * fails, so a failed [put] is observable to the caller. Because `edit` aborts its transaction on
 * failure, the prior value is left byte-for-byte unchanged. Awaiting the write also guarantees
 * read-after-write consistency, which Property 1 relies on: a subsequent [get] observes exactly the
 * most recently persisted value.
 *
 * The [DataStore] is injected so tests can substitute an in-memory store; production callers use
 * [default], which reuses [PrefManager]'s single preference store keyed by `save_location_<appId>`.
 */
class DataStoreSaveLocationStore(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = COMPACT_JSON,
) : SaveLocationStore {

    override suspend fun get(appId: String): SaveLocation? {
        val raw = dataStore.data.first()[keyFor(appId)] ?: return null
        return try {
            json.decodeFromString<SaveLocationDto>(raw).toSaveLocation()
        } catch (e: Exception) {
            // A corrupt or unknown-PathType value is treated as unset. The resolver's Unresolved
            // path covers genuinely-persisted-but-unresolvable values; a value we cannot even
            // parse is not a valid persisted location, so report it as absent rather than crash.
            Timber.w(e, "Failed to deserialize persisted SaveLocation for appId=$appId; treating as unset")
            null
        }
    }

    override suspend fun put(appId: String, location: SaveLocation) {
        val serialized = json.encodeToString(SaveLocationDto.from(location))
        // Awaits durable persistence; throws on failure and leaves the prior value intact.
        dataStore.edit { prefs -> prefs[keyFor(appId)] = serialized }
    }

    override suspend fun remove(appId: String) {
        // Awaits durable persistence; throws on failure. A no-op when the key is absent.
        dataStore.edit { prefs -> prefs.remove(keyFor(appId)) }
    }

    companion object {
        /** Compact JSON: no pretty-printing, and unknown keys ignored on read for forward-compat. */
        val COMPACT_JSON: Json = Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** DataStore key for a game's persisted save location (`save_location_<appId>`). */
        internal fun keyFor(appId: String) = stringPreferencesKey("save_location_$appId")

        /**
         * A store backed by [PrefManager]'s single preference store. Must only be called after
         * `PrefManager.init`.
         */
        fun default(): DataStoreSaveLocationStore =
            DataStoreSaveLocationStore(PrefManager.getDataStore())
    }
}

/**
 * Serialization DTO for a persisted [SaveLocation] (a set of [SaveRoot]s).
 *
 * **Wire shape and back-compat.** The current shape carries an ordered `roots` array. The
 * pre-revision model persisted a single root inline as `{ "pathType", "relativeSubpath" }` with no
 * `roots` field. Both fields are optional here so either shape deserializes: a value with `roots`
 * uses it; a legacy value with no `roots` but a top-level `pathType` is read as a one-element set.
 * New writes always emit `roots`.
 */
@Serializable
private data class SaveLocationDto(
    val roots: List<SaveRootDto>? = null,
    // Legacy single-root fields (pre-multi-root model). Read-only back-compat; never written.
    val pathType: String? = null,
    val relativeSubpath: String? = null,
) {
    /** Map back to a [SaveLocation]; throws if any `pathType` is not a known [PathType] name. */
    fun toSaveLocation(): SaveLocation {
        val rootDtos = when {
            !roots.isNullOrEmpty() -> roots
            pathType != null -> listOf(SaveRootDto(pathType, relativeSubpath ?: ""))
            else -> throw IllegalArgumentException("Persisted SaveLocation has no roots")
        }
        return SaveLocation.of(rootDtos.map { it.toSaveRoot() })
    }

    companion object {
        fun from(location: SaveLocation): SaveLocationDto =
            SaveLocationDto(roots = location.roots.map { SaveRootDto.from(it) })
    }
}

/** Serialization DTO for a single [SaveRoot]. [pattern] is `@Serializable` and persisted as-is. */
@Serializable
private data class SaveRootDto(
    val pathType: String,
    val relativeSubpath: String,
    val pattern: SaveFilePattern? = null,
) {
    fun toSaveRoot(): SaveRoot = SaveRoot(
        pathType = PathType.valueOf(pathType),
        relativeSubpath = relativeSubpath,
        pattern = pattern,
    )

    companion object {
        fun from(root: SaveRoot): SaveRootDto = SaveRootDto(
            pathType = root.pathType.name,
            relativeSubpath = root.relativeSubpath,
            pattern = root.pattern,
        )
    }
}
