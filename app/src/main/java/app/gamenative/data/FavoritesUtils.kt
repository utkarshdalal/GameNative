package app.gamenative.data

internal object FavoritesUtils {

    fun apply(current: Set<String>, appId: String, favorite: Boolean): Set<String> =
        if (favorite) current + appId else current - appId

    fun <T> filter(items: List<T>, favorites: Set<String>, id: (T) -> String): List<T> =
        items.filter { id(it) in favorites }

    fun countPresent(favorites: Set<String>, eligibleIds: Set<String>): Int =
        favorites.count { it in eligibleIds }
}
