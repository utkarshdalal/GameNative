package app.gamenative.ui.data

import app.gamenative.data.StoreGameDetails
import app.gamenative.store.StorePageTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val hltbStats: app.gamenative.utils.HltbService.Stats? = null, // How Long To Beat stats
    /** Status line under Play for Steam Families preferred copy, e.g. "Using your copy". */
    val preferredCopyStatusText: String? = null,
    val showChangePreferredCopy: Boolean = false,
    val onChangePreferredCopy: (() -> Unit)? = null,
    /** True while available Family library copies are being resolved off the main thread. */
    val isLoadingPreferredCopy: Boolean = false,
    val storeDetails: StoreGameDetails = StoreGameDetails(), // Description, reviews, tags, and media
    val storePageTarget: StorePageTarget? = null,

    val runtime: String = "wine",
)

/** "Developer • Year", leaving out whichever part is missing. */
fun GameDisplayInfo.developerAndYear(): String {
    val year = if (releaseDate > 0) SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(releaseDate * 1000)) else ""
    return listOf(developer, year).filter { it.isNotBlank() }.joinToString(" • ")
}
