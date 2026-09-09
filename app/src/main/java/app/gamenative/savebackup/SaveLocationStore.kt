package app.gamenative.savebackup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gamenative.PrefManager
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
 * Serialization DTO for [SaveLocation]. [SaveLocation] is a hand-written class (not `@Serializable`),
 * so this DTO carries the wire shape `{ "pathType": ..., "relativeSubpath": ... }` and maps to/from
 * the model. [pathType] is serialized by its [PathType] enum name.
 */
@Serializable
private data class SaveLocationDto(
    val pathType: String,
    val relativeSubpath: String,
) {
    /** Map back to a [SaveLocation]; throws if [pathType] is not a known [PathType] name. */
    fun toSaveLocation(): SaveLocation = SaveLocation(
        pathType = PathType.valueOf(pathType),
        relativeSubpath = relativeSubpath,
    )

    companion object {
        fun from(location: SaveLocation): SaveLocationDto = SaveLocationDto(
            pathType = location.pathType.name,
            relativeSubpath = location.relativeSubpath,
        )
    }
}
