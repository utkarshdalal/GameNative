package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps track of which games the user has marked as favourite.
 *
 * Favourites are stored as a set of [LibraryItem.appId] values, so they work across every source
 * (Steam, GOG, Epic, Amazon and custom games) without needing an account. The current set is
 * exposed as a [StateFlow] so the library list and the game cards update as soon as it changes,
 * while [PrefManager] keeps the values on disk between sessions.
 */
object FavouritesManager {
    private val _favourites = MutableStateFlow(PrefManager.favouriteAppIds)

    /** The set of favourited app ids. Observe this to react to changes. */
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    fun isFavourite(appId: String): Boolean = _favourites.value.contains(appId)

    /** Adds the game if it is not a favourite yet, or removes it if it already is. */
    fun toggle(appId: String) = setFavourite(appId, !isFavourite(appId))

    fun setFavourite(appId: String, favourite: Boolean) {
        val current = _favourites.value
        val updated = FavouritesUtils.apply(current, appId, favourite)
        if (updated == current) return

        _favourites.value = updated
        PrefManager.favouriteAppIds = updated
    }
}
