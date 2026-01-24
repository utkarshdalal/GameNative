package app.gamenative.enums

/**
 * Configuration for game-specific save location workarounds
 * 
 * This data class defines how to create symlinks for games that store saves
 * in non-standard locations or need compatibility workarounds.
 * 
 * @property appId The Steam app ID
 * @property pathType Which PathType to use as the base path (e.g., WinAppDataLocal, WinMyDocuments)
 * @property sourceRelativePath Relative path from the base path to the actual save location
 * @property targetRelativePath Relative path where the symlink should be created
 * @property description Human-readable game name for logging
 * 
 * Supports placeholders in paths:
 * - {64BitSteamID} - Replaced with the user's 64-bit Steam ID
 * - {Steam3AccountID} - Replaced with the user's Steam3 account ID
 */
data class SaveLocationWorkaround(
    val appId: Int,
    val pathType: PathType,
    val sourceRelativePath: String,
    val targetRelativePath: String,
    val description: String
) {
    companion object {
        /**
         * Registry of game-specific save location workarounds
         * Add new entries here for games that need save folder symlinks
         */
        val registry = listOf(
            SaveLocationWorkaround(
                appId = 2680010,
                pathType = PathType.WinAppDataLocal,
                sourceRelativePath = "The First Berserker Khazan/Saved/SaveGames/{64BitSteamID}",
                targetRelativePath = "BBQ/Saved/SaveGames",
                description = "The First Berserker Khazan"
            )
            // Add more game workarounds here as needed
        )
    }
}
