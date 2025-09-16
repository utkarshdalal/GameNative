package app.gamenative.data
import app.gamenative.enums.AppType

/**
 * Data class for the Library list
 */
data class LibraryItem(
    val index: Int = 0,
    val appId: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val isShared: Boolean = false,
    val isInstalled: Boolean = false,
    val appType: AppType = AppType.game,
    val gameSource: GameSource = GameSource.STEAM,
) {

    /**
     * Helper property to get the game ID as an integer
     * Extracts the numeric part by removing the gameSource prefix
     */
    val gameId: Int
        get() = appId.removePrefix("${gameSource.name}_").toInt()
}
