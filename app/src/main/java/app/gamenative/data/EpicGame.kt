package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import app.gamenative.enums.AppType

/**
 * Epic Game entity for Room database
 * Represents a game from the Epic Games Store via Legendary CLI
 *
 * Fields based on Legendary's game metadata structure:
 * - legendary list --json (library listing)
 * - legendary info <app_name> --json (detailed game info)
 * - installed.json (installation status)
 */
@Entity(tableName = "epic_games")
data class EpicGame(
    // TODO: Verify whether we should use app_name of the id for the primary key. String as primary key is ick.
    @PrimaryKey
    @ColumnInfo("id")
    val id: String, // Epic catalog item ID (hash/UUID) - Primary key for consistency with Steam/GOG

    @ColumnInfo("app_name")
    val appName: String = "", // Legendary CLI identifier (used for all Legendary operations)

    @ColumnInfo("title")
    val title: String = "",

    @ColumnInfo("namespace")
    val namespace: String = "",

    @ColumnInfo("developer")
    val developer: String = "",

    @ColumnInfo("publisher")
    val publisher: String = "",

    // Installation info
    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    @ColumnInfo("platform")
    val platform: String = "Windows",

    @ColumnInfo("version")
    val version: String = "",

    @ColumnInfo("executable")
    val executable: String = "",

    @ColumnInfo("install_size")
    val installSize: Long = 0,

    @ColumnInfo("download_size")
    val downloadSize: Long = 0,

    // Art assets - Full HTTPS URLs from Epic CDN
    // Extracted from game metadata's keyImages array (DieselGameBoxTall, DieselGameBox, etc.)
    @ColumnInfo("art_cover")
    val artCover: String = "", // DieselGameBoxTall - Tall cover art

    @ColumnInfo("art_square")
    val artSquare: String = "", // DieselGameBox - Square box art

    @ColumnInfo("art_logo")
    val artLogo: String = "", // DieselGameBoxLogo - Logo image

    @ColumnInfo("art_portrait")
    val artPortrait: String = "", // DieselStoreFrontWide or other portrait variants

    // DRM and features
    @ColumnInfo("can_run_offline")
    val canRunOffline: Boolean = true,

    @ColumnInfo("requires_ot")
    val requiresOT: Boolean = false, // requires online token

    @ColumnInfo("cloud_save_enabled")
    val cloudSaveEnabled: Boolean = false,

    @ColumnInfo("save_folder")
    val saveFolder: String = "",

    // Third-party platform management (EA, Ubisoft)
    @ColumnInfo("third_party_managed_app")
    val thirdPartyManagedApp: String = "",

    @ColumnInfo("is_ea_managed")
    val isEAManaged: Boolean = false,

    // DLC info
    @ColumnInfo("is_dlc")
    val isDLC: Boolean = false,

    @ColumnInfo("base_game_app_name")
    val baseGameAppName: String = "",

    // Metadata
    @ColumnInfo("description")
    val description: String = "",

    @ColumnInfo("release_date")
    val releaseDate: String = "",

    @ColumnInfo("genres")
    val genres: List<String> = emptyList(),

    @ColumnInfo("tags")
    val tags: List<String> = emptyList(),

    // Usage tracking
    @ColumnInfo("last_played")
    val lastPlayed: Long = 0,

    @ColumnInfo("play_time")
    val playTime: Long = 0,

    @ColumnInfo("type")
    val type: AppType = AppType.game,

    // Epic Online Services
    @ColumnInfo("eos_catalog_item_id")
    val eosCatalogItemId: String = "",

    @ColumnInfo("eos_app_id")
    val eosAppId: String = "",
) {
    /**
     * Primary image URL for the game
     * Prioritizes: artCover -> artSquare -> artLogo -> artPortrait
     * These are full HTTPS URLs from Epic's CDN
     */
    val primaryImageUrl: String
        get() = when {
            artCover.isNotEmpty() -> artCover
            artSquare.isNotEmpty() -> artSquare
            artLogo.isNotEmpty() -> artLogo
            artPortrait.isNotEmpty() -> artPortrait
            else -> ""
        }

    /**
     * Icon URL for the game (uses square art or primary image)
     */
    val iconUrl: String
        get() = artSquare.ifEmpty { primaryImageUrl }

    /**
     * Check if game requires EA/Origin launcher
     */
    val requiresOrigin: Boolean
        get() = isEAManaged || thirdPartyManagedApp.lowercase() in setOf("origin", "the ea app", "ea app")

    /**
     * Check if game requires Ubisoft Connect
     */
    val requiresUbisoft: Boolean
        get() = thirdPartyManagedApp.lowercase() in setOf("uplay", "ubisoft connect")

    /**
     * Check if game can be launched offline
     * Only true if explicitly allowed and not requiring online token
     */
    val supportsOfflineLaunch: Boolean
        get() = canRunOffline && !requiresOT

    /**
     * Get the app name to launch (base game if this is DLC)
     */
    val launchAppName: String
        get() = if (isDLC && baseGameAppName.isNotEmpty()) baseGameAppName else appName
}

/**
 * Epic credentials for OAuth authentication
 */
data class EpicCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val displayName: String,
    val expiresAt: Long = 0,
)

/**
 * Epic download progress info
 */
data class EpicDownloadInfo(
    val appName: String,
    val totalSize: Long,
    val downloadedSize: Long = 0,
    val progress: Float = 0f,
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
)

/**
 * DLC information for Epic games
 */
data class EpicDLCInfo(
    val appName: String,
    val title: String,
    val isInstalled: Boolean = false,
)

/**
 * Game launch token from Legendary
 * Used for Epic authentication during game launch
 */
data class GameToken(
    val authCode: String, // Exchange code for -AUTH_PASSWORD parameter
    val accountId: String, // User account ID for -epicuserid
    val ownershipToken: String? = null, // Optional DRM ownership token
)
