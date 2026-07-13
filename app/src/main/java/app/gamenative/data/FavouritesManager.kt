package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

/**
 * Keeps track of which games the user has marked as favourite.
 *
 * Favourites are stored as a set of [LibraryItem.appId] values, so they work across every source
 * (Steam, GOG, Epic, Amazon and custom games) without needing an account. The current set is
 * exposed as a [StateFlow] so the library list and the game cards update as soon as it changes,
 * while [PrefManager] keeps the values on disk between sessions.
 *
 * The saved set is loaded off the main thread, so building this singleton (which happens the first
 * time a card or the detail menu is drawn) never blocks the UI on a disk read. Until the load
 * finishes the set is simply empty. If the user stars a game in that short window, the edit is
 * recorded and replayed on top of the loaded set, so an early toggle can never drop previously
 * saved favourites.
 */
object FavouritesManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _favourites = MutableStateFlow<Set<String>>(emptySet())

    /** The set of favourited app ids. Observe this to react to changes. */
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    private val lock = Any()
    private var loaded = false

    /** Edits made before the saved set finished loading, kept so they can be replayed on top of it. */
    private val pendingEdits = LinkedHashMap<String, Boolean>()

    init {
        scope.launch {
            val stored = PrefManager.favouriteAppIds
            val merged: Set<String>
            val hadPendingEdits: Boolean
            synchronized(lock) {
                hadPendingEdits = pendingEdits.isNotEmpty()
                var result = stored
                for ((appId, favourite) in pendingEdits) {
                    result = FavouritesUtils.apply(result, appId, favourite)
                }
                pendingEdits.clear()
                loaded = true
                merged = result
                _favourites.value = result
            }
            if (hadPendingEdits && PrefManager.favouriteAppIds != merged) {
                PrefManager.favouriteAppIds = merged
            }
        }
    }

    fun isFavourite(appId: String): Boolean = _favourites.value.contains(appId)

    /** Adds the game if it is not a favourite yet, or removes it if it already is. */
    fun toggle(appId: String) = setFavourite(appId, !isFavourite(appId))

    fun setFavourite(appId: String, favourite: Boolean) {
        synchronized(lock) {
            if (!loaded) {
                // The saved set has not loaded yet. Remember the intent and reflect it in the flow
                // now for a responsive UI; persistence happens once the load merges it in.
                pendingEdits[appId] = favourite
                _favourites.value = FavouritesUtils.apply(_favourites.value, appId, favourite)
                return
            }
        }
        val updated = _favourites.updateAndGet { current ->
            FavouritesUtils.apply(current, appId, favourite)
        }
        if (PrefManager.favouriteAppIds != updated) {
            PrefManager.favouriteAppIds = updated
        }
    }
}
