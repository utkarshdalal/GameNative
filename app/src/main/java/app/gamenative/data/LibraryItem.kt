package app.gamenative.data

import app.gamenative.Constants
import app.gamenative.utils.OpenContainerScanner

enum class GameSource {
    STEAM,
    OPEN_CONTAINER,
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
        get() = when (gameSource) {
            GameSource.STEAM -> if (iconHash.isNotEmpty()) {
                Constants.Library.ICON_URL + "${gameId}/$iconHash.ico"
            } else {
                ""
            }
            GameSource.OPEN_CONTAINER -> {
                // Attempt to resolve a local icon from the selected/unique exe folder
                val localPath = OpenContainerScanner.findIconFileForOpenContainer(appId)
                if (!localPath.isNullOrEmpty()) {
                    if (localPath.startsWith("file://")) localPath else "file://$localPath"
                } else {
                    ""
                }
            }
        }

    /**
     * Helper property to get the game ID as an integer
     * Extracts the numeric part by removing the gameSource prefix
     */
    val gameId: Int
        get() = appId.removePrefix("${gameSource.name}_").toInt()
}
