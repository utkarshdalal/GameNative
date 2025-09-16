package app.gamenative.data
import app.gamenative.service.GameManagerService

/**
 * Data class for the Library list
 */
data class LibraryItem(
    val index: Int = 0,
    val appId: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val isShared: Boolean = false,
    val gameSource: GameSource = GameSource.STEAM,
) {

    /**
     * Helper property to get the game ID as an integer
     * Extracts the numeric part by removing the gameSource prefix
     */
    val gameId: Int
        get() = appId.removePrefix("${gameSource.name}_").toInt()
}
