package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class SteamCollection(
    val id: String,
    val name: String,
    val appIds: Set<Int> = emptySet(),
) {
    companion object {
        // Steam's built-in collections. Display names are localized per client language,
        // but these ids are stable across languages.
        const val ID_FAVORITE = "favorite"
        const val ID_HIDDEN = "hidden"
    }
}
