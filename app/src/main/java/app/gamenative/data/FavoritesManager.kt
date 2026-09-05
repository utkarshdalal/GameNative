package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps track of which games the user has marked as favorite.
 *
 * Favorites are stored as a set of [LibraryItem.appId] values, so they work across every source
 * (Steam, GOG, Epic, Amazon and custom games) without needing an account. The current set is
 * exposed as a [StateFlow] so the library list and the game cards update as soon as it changes,
 * while [PrefManager] keeps the values on disk between sessions.
 *
 * The saved set is loaded off the main thread, so building this singleton (which happens the first
 * time a card or the detail menu is drawn) never blocks the UI on a disk read. Until the load
 * finishes the set is simply empty and [toggle] returns null (the tap is ignored), so an early
 * toggle can never overwrite previously saved favorites with a partial set.
 */
object FavoritesManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())

    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * Whether the saved set has finished loading from disk. Observe this to tell a genuinely empty
     * favorites set apart from one that simply hasn't loaded yet, so the UI doesn't flash an
     * "empty" state before the stored favorites arrive.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val lock = Any()

    init {
        scope.launch {
            try {
                val stored = try {
                    PrefManager.favoriteAppIds
                } catch (e: Exception) {
                    Timber.tag("FavoritesManager").e(e, "Failed to load favorite app ids")
                    emptySet()
                }
                synchronized(lock) {
                    // Publish the loaded set before flipping the loaded flag, so an observer that reacts
                    // to `loaded` never sees `true` while `favorites` is still the initial empty set
                    // (which would briefly render the "no favorites yet" empty state).
                    _favorites.value = stored
                }
            } catch (e: Exception) {
                Timber.tag("FavoritesManager").e(e, "Failed to initialize favorite app ids")
                synchronized(lock) {
                    _favorites.value = emptySet()
                }
            } finally {
                _loaded.value = true
            }
        }
    }

    /** Returns the new favorite state, or null if the toggle was ignored (set not loaded yet). */
    internal fun toggle(appId: String): Boolean? {
        synchronized(lock) {
            if (!_loaded.value) return null
            val favorite = appId !in _favorites.value
            val updated = FavoritesUtils.apply(_favorites.value, appId, favorite)
            if (updated == _favorites.value) return null
            _favorites.value = updated
            PrefManager.favoriteAppIds = updated
            return favorite
        }
    }
}
