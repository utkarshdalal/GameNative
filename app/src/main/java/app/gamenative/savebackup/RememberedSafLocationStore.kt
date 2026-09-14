package app.gamenative.savebackup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gamenative.PrefManager
import kotlinx.coroutines.flow.first

/**
 * Persists the remembered external-location SAF tree URI per game, keyed by the source-prefixed
 * `LibraryItem.appId` (e.g. `STEAM_440`, `GOG_1207658924`).
 *
 * This stores only the tree URI string that a previously granted persistable permission points at
 * so that [SafLocationManager.rememberedLocation] can look it up and reuse it without re-prompting
 * (Requirement 10.2). A remembered URI is only written after a persistable grant was successfully
 * obtained (Requirement 10.1); if persisting the grant fails the URI is never remembered so the
 * next operation reopens the picker (Requirement 10.3).
 *
 * The remembered URI is intentionally kept separate from the persisted [SaveLocation] (see
 * [SaveLocationStore]): the [SaveLocation] describes the *container* side, while this describes the
 * *external* side.
 */
interface RememberedSafLocationStore {
    /** The remembered SAF tree URI string for [appId], or `null` when none is remembered. */
    suspend fun get(appId: String): String?

    /** Remember [treeUri] for [appId], replacing any previously remembered value. */
    suspend fun put(appId: String, treeUri: String)

    /** Forget any remembered URI for [appId] (e.g. after a grant is found to be invalid). */
    suspend fun remove(appId: String)
}

/**
 * [RememberedSafLocationStore] backed by the same [DataStore] that [PrefManager] owns.
 *
 * Like [DataStoreSaveLocationStore], writes await `dataStore.edit { ... }` directly so a failed
 * write throws and reads observe the most recently persisted value. The [DataStore] is injected so
 * tests can substitute an in-memory store; production callers use [default].
 */
class DataStoreRememberedSafLocationStore(
    private val dataStore: DataStore<Preferences>,
) : RememberedSafLocationStore {

    override suspend fun get(appId: String): String? =
        dataStore.data.first()[keyFor(appId)]

    override suspend fun put(appId: String, treeUri: String) {
        dataStore.edit { prefs -> prefs[keyFor(appId)] = treeUri }
    }

    override suspend fun remove(appId: String) {
        dataStore.edit { prefs -> prefs.remove(keyFor(appId)) }
    }

    companion object {
        /** DataStore key for a game's remembered external SAF location (`saf_location_<appId>`). */
        internal fun keyFor(appId: String) = stringPreferencesKey("saf_location_$appId")

        /**
         * A store backed by [PrefManager]'s single preference store. Must only be called after
         * `PrefManager.init`.
         */
        fun default(): DataStoreRememberedSafLocationStore =
            DataStoreRememberedSafLocationStore(PrefManager.getDataStore())
    }
}
