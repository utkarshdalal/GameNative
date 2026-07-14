package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            synchronized(lock) {
                var result = stored
                for ((appId, favourite) in pendingEdits) {
                    result = FavouritesUtils.apply(result, appId, favourite)
                }
                val hadPendingEdits = pendingEdits.isNotEmpty()
                pendingEdits.clear()
                loaded = true
                _favourites.value = result
                // Persist inside the lock so a concurrent toggle cannot be overwritten by a stale
                // snapshot written after the lock is released.
                if (hadPendingEdits) {
                    PrefManager.favouriteAppIds = result
                }
            }
        }
    }

    fun isFavourite(appId: String): Boolean = _favourites.value.contains(appId)

    /** Adds the game if it is not a favourite yet, or removes it if it already is. */
    fun toggle(appId: String) = setFavourite(appId, !isFavourite(appId))

    fun setFavourite(appId: String, favourite: Boolean) {
        synchronized(lock) {
            val updated = FavouritesUtils.apply(_favourites.value, appId, favourite)
            if (updated == _favourites.value) return
            _favourites.value = updated
            if (loaded) {
                PrefManager.favouriteAppIds = updated
            } else {
                // Still loading: record the intent so the load replays it on top of the saved set.
                pendingEdits[appId] = favourite
            }
        }
    }
}
