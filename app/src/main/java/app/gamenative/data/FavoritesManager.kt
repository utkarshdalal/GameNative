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

internal data class FavoriteMutation(
    val appId: String,
    val previousFavorite: Boolean,
    val favorite: Boolean,
    val revision: Long,
)

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

    private val revisions = HashMap<String, Long>()

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

    fun isFavorite(appId: String): Boolean = _favorites.value.contains(appId)

    internal fun toggle(appId: String): FavoriteMutation? {
        synchronized(lock) {
            if (!_loaded.value) return null
            return setFavoriteLocked(appId, !isFavorite(appId))
        }
    }

    /**
     * Reverts a removal only while no newer decision has changed this app's favorite state.
     * Snackbar actions can outlive several subsequent mutations, so an app id alone is not enough
     * to identify a valid undo target.
     */
    internal fun undo(mutation: FavoriteMutation): Boolean {
        synchronized(lock) {
            val currentFavorite = isFavorite(mutation.appId)
            if (revisions[mutation.appId] != mutation.revision ||
                currentFavorite != mutation.favorite
            ) {
                return false
            }
            setFavoriteLocked(mutation.appId, mutation.previousFavorite)
            return true
        }
    }

    private fun setFavoriteLocked(appId: String, favorite: Boolean): FavoriteMutation? {
        val previousFavorite = isFavorite(appId)
        val updated = FavoritesUtils.apply(_favorites.value, appId, favorite)
        if (updated == _favorites.value) return null

        _favorites.value = updated
        val revision = (revisions[appId] ?: 0L) + 1L
        revisions[appId] = revision
        PrefManager.favoriteAppIds = updated
        return FavoriteMutation(appId, previousFavorite, favorite, revision)
    }
}
