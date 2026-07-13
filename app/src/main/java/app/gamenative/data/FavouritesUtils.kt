package app.gamenative.data

/**
 * Pure helpers for the favourites feature.
 *
 * The logic is kept here, separate from [FavouritesManager] and the library view model, so it can
 * be reasoned about and unit tested on its own without touching storage or Android state.
 */
internal object FavouritesUtils {

    /** Returns the favourites set after adding or removing [appId]. */
    fun apply(current: Set<String>, appId: String, favourite: Boolean): Set<String> =
        if (favourite) current + appId else current - appId

    /** Returns the favourites set with [appId] flipped on or off. */
    fun toggle(current: Set<String>, appId: String): Set<String> =
        apply(current, appId, appId !in current)

    /** Keeps only the [items] whose id (via [id]) is in [favourites], preserving order. */
    fun <T> filter(items: List<T>, favourites: Set<String>, id: (T) -> String): List<T> =
        items.filter { id(it) in favourites }

    /** Counts how many of the [items] are in [favourites]. */
    fun <T> count(items: List<T>, favourites: Set<String>, id: (T) -> String): Int =
        items.count { id(it) in favourites }
}
