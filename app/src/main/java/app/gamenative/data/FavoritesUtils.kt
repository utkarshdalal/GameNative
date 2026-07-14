package app.gamenative.data

/**
 * Pure helpers for the favorites feature.
 *
 * The logic is kept here, separate from [FavoritesManager] and the library view model, so it can
 * be reasoned about and unit tested on its own without touching storage or Android state.
 */
internal object FavoritesUtils {

    /** Returns the favorites set after adding or removing [appId]. */
    fun apply(current: Set<String>, appId: String, favorite: Boolean): Set<String> =
        if (favorite) current + appId else current - appId

    /** Keeps only the [items] whose id (via [id]) is in [favorites], preserving order. */
    fun <T> filter(items: List<T>, favorites: Set<String>, id: (T) -> String): List<T> =
        items.filter { id(it) in favorites }

    /** Counts how many of the [items] are in [favorites]. */
    fun <T> count(items: List<T>, favorites: Set<String>, id: (T) -> String): Int =
        items.count { id(it) in favorites }
}
