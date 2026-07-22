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
 * Keeps track of which games the user has marked as favorite.
 *
 * Favorites are stored as a set of [LibraryItem.appId] values, so they work across every source
 * (Steam, GOG, Epic, Amazon and custom games) without needing an account. The current set is
 * exposed as a [StateFlow] so the library list and the game cards update as soon as it changes,
 * while [PrefManager] keeps the values on disk between sessions.
 *
 * The saved set is loaded off the main thread, so building this singleton (which happens the first
 * time a card or the detail menu is drawn) never blocks the UI on a disk read. Until the load
 * finishes the set is simply empty. If the user stars a game in that short window, the edit is
 * recorded and replayed on top of the loaded set, so an early toggle can never drop previously
 * saved favorites.
 */
object FavoritesManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())

    /** The set of favorited app ids. Observe this to react to changes. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /**
     * Whether the saved set has finished loading from disk. Observe this to tell a genuinely empty
     * favorites set apart from one that simply hasn't loaded yet, so the UI doesn't flash an
     * "empty" state before the stored favorites arrive.
     */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val lock = Any()

    /** Edits made before the saved set finished loading, kept so they can be replayed on top of it. */
    private val pendingEdits = LinkedHashMap<String, Boolean>()

    init {
        scope.launch {
            val stored = PrefManager.favoriteAppIds
            synchronized(lock) {
                var result = stored
                for ((appId, favorite) in pendingEdits) {
                    result = FavoritesUtils.apply(result, appId, favorite)
                }
                val hadPendingEdits = pendingEdits.isNotEmpty()
                pendingEdits.clear()
                // Publish the loaded set before flipping the loaded flag, so an observer that reacts
                // to `loaded` never sees `true` while `favorites` is still the initial empty set
                // (which would briefly render the "no favorites yet" empty state).
                _favorites.value = result
                _loaded.value = true
                // Persist inside the lock so a concurrent toggle cannot be overwritten by a stale
                // snapshot written after the lock is released.
                if (hadPendingEdits) {
                    PrefManager.favoriteAppIds = result
                }
            }
        }
    }

    fun isFavorite(appId: String): Boolean = _favorites.value.contains(appId)

    /** Adds the game if it is not a favorite yet, or removes it if it already is. */
    fun toggle(appId: String) = setFavorite(appId, !isFavorite(appId))

    fun setFavorite(appId: String, favorite: Boolean) {
        synchronized(lock) {
            val updated = FavoritesUtils.apply(_favorites.value, appId, favorite)
            if (updated == _favorites.value) return
            _favorites.value = updated
            if (_loaded.value) {
                PrefManager.favoriteAppIds = updated
            } else {
                // Still loading: record the intent so the load replays it on top of the saved set.
                pendingEdits[appId] = favorite
            }
        }
    }
}
