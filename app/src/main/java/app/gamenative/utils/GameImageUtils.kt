package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem

/**
 * Utility functions for retrieving game images from various sources.
 * Handles the priority: Custom media -> SteamGridDB -> Steam URLs
 */
object GameImageUtils {
    /**
     * Notifies the UI that game images have been refreshed (e.g., after fetching from SteamGridDB).
     * This triggers a recomposition of all image-related UI components to display the newly fetched images.
     */
    fun notifyImagesRefreshed() {
        // Increment media version to trigger UI refresh
        // This causes all remember(mediaVersion, ...) blocks to recompute
        CustomMediaUtils.notifyMediaChanged()
    }
    /**
     * Get game image URI/URL with proper priority:
     * 1. Custom media (user-selected)
     * 2. SteamGridDB images (for custom games, from game folder)
     * 3. Steam URLs (for Steam games)
     *
     * @param libraryItem The library item containing appId and gameSource
     * @param imageType The type of image: "hero", "capsule", "header", "logo", "icon", "grid_hero", "grid_capsule"
     * @param steamUrl Optional Steam URL fallback (only used for Steam games)
     * @return The image URI/URL string (can be file:// URI or http:// URL), or null if not found
     */
    fun getGameImage(
        libraryItem: LibraryItem,
        imageType: String,
        steamUrl: String? = null
    ): String? {
        // Get appId and gameId from libraryItem
        val appId = libraryItem.appId
        val gameId = libraryItem.gameId
                
        // 1. Check custom media first (only if we have a valid gameId)
        if (gameId != null) {
            val customUri = when (imageType) {
                "hero", "grid_hero" -> CustomMediaUtils.getCustomHeroUri(gameId)
                "capsule", "grid_capsule" -> CustomMediaUtils.getCustomCapsuleUri(gameId)
                "header" -> CustomMediaUtils.getCustomHeaderUri(gameId)
                "logo" -> CustomMediaUtils.getCustomLogoUri(gameId)
                "icon" -> CustomMediaUtils.getCustomIconUri(gameId)
                else -> null
            }
            if (customUri != null) return customUri.toString()
        }

        // 2. Check SteamGridDB images
        if (appId != null) {
            // Find icon from SteamGridDB
            var icon = SteamGridDB.findSteamGridDBImageByAppId(appId, "icon")

            // If no icon found, find icon from custom game scanner (extracted from exe file)
            if (libraryItem.gameSource == GameSource.CUSTOM_GAME && icon == null) {
                icon = CustomGameScanner.findIconFileForCustomGame(appId)
            }
            
            // Check if the game has a custom icon
            val steamGridUri = when (imageType) {
                "hero", "grid_hero" -> SteamGridDB.findSteamGridDBImageByAppId(appId, "grid_hero")
                "capsule", "grid_capsule" -> SteamGridDB.findSteamGridDBImageByAppId(appId, "grid_capsule")
                "header" -> SteamGridDB.findSteamGridDBImageByAppId(appId, "header")
                "logo" -> SteamGridDB.findSteamGridDBImageByAppId(appId, "logo")
                "icon" -> icon
                else -> null
            }

            if (steamGridUri != null) return steamGridUri
        }

        // 3. Fall back to Steam URL (for Steam games)
        return steamUrl

    }
}

