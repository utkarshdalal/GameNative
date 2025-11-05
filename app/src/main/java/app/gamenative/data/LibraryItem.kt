package app.gamenative.data

import app.gamenative.Constants

enum class GameSource {
    STEAM,
    CONTAINER,  // Custom containers
    // Add other platforms here..
}

/**
 * Data class for the Library list
 */
data class LibraryItem(
    val index: Int = 0,
    val appId: String = "",
    val name: String = "",
    val iconHash: String = "",
    val isShared: Boolean = false,
    val gameSource: GameSource = GameSource.STEAM,
) {
    val clientIconUrl: String
        get() = if (gameSource == GameSource.STEAM) {
            Constants.Library.ICON_URL + "${gameId}/$iconHash.ico"
        } else {
            "" // Custom containers don't have Steam icons
        }
    
    /**
     * Helper property to get the game ID as an integer
     * Extracts the numeric part by removing the gameSource prefix
     */
    val gameId: Int
        get() = appId.removePrefix("${gameSource.name}_").toIntOrNull() ?: 0
    
    /**
     * Helper property to get the container ID as a string
     * For CONTAINER source, returns the full container ID
     */
    val containerId: String
        get() = appId.removePrefix("${gameSource.name}_")
}
