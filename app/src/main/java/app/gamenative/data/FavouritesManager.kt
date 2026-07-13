package app.gamenative.data

import app.gamenative.PrefManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * finishes the set is simply empty, then it fills in once the values come back.
 */
object FavouritesManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _favourites = MutableStateFlow<Set<String>>(emptySet())

    /** The set of favourited app ids. Observe this to react to changes. */
    val favourites: StateFlow<Set<String>> = _favourites.asStateFlow()

    /**
     * Whether the user has changed favourites already. It guards against the async load below
     * overwriting an early toggle if someone stars a game before the saved set has been read.
     */
    private val userHasEdited = AtomicBoolean(false)

    init {
        scope.launch {
            val stored = PrefManager.favouriteAppIds
            _favourites.update { current -> if (userHasEdited.get()) current else stored }
        }
    }

    fun isFavourite(appId: String): Boolean = _favourites.value.contains(appId)

    /** Adds the game if it is not a favourite yet, or removes it if it already is. */
    fun toggle(appId: String) = setFavourite(appId, !isFavourite(appId))

    fun setFavourite(appId: String, favourite: Boolean) {
        userHasEdited.set(true)
        val updated = _favourites.updateAndGet { current ->
            FavouritesUtils.apply(current, appId, favourite)
        }
        if (PrefManager.favouriteAppIds != updated) {
            PrefManager.favouriteAppIds = updated
        }
    }
}
