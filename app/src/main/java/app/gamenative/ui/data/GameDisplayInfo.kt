package app.gamenative.ui.data

/**
 * Common data structure for displaying game information in the UI.
 * This allows both Steam and Custom Games to use the same UI layout.
 */
data class GameDisplayInfo(
    val name: String,
    val developer: String,
    val releaseDate: Long, // Unix timestamp in seconds
    val heroImageUrl: String?,
    val iconUrl: String?,
    val gameId: Int, // For Steam: appId, for Custom Game: extracted from appId
    val appId: String, // Full appId including source prefix
    val installLocation: String? = null, // Path where game is installed
    val sizeOnDisk: String? = null, // Formatted size string
    val sizeFromStore: String? = null, // Formatted size from store
    val lastPlayedText: String? = null, // Formatted last played time
    val playtimeText: String? = null, // Formatted playtime
    val logoUrl: String? = null, // Logo image URL (for SteamGridDB)
    val capsuleUrl: String? = null, // Capsule/grid image URL (for SteamGridDB)
    val headerUrl: String? = null, // Header image URL (for SteamGridDB, can use grid as header)
    val compatibilityMessage: String? = null, // Compatibility message text (e.g., "Works on your GPU")
    val compatibilityColor: ULong? = null, // Compatibility message color (ARGB)
)

