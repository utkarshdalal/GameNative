package app.gamenative.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Amazon Game entity for Room database.
 * Represents an entitlement returned by the Amazon Gaming Distribution service.
 *
 * Follows the Epic pattern:
 * - [appId]: Auto-generated Int primary key for internal use (events, intents, container IDs)
 * - [productId]: Amazon's actual product UUID for API calls
 */
@Entity(tableName = "amazon_games", indices = [Index("product_id")])
data class AmazonGame(
    /**
     * Auto-generated integer primary key for internal GameNative use.
     * Used for events, intents, container IDs, etc.
     * For Amazon API calls, use [productId] instead.
     */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("app_id")
    val appId: Int = 0,

    /** Amazon product ID (e.g. "amzn1.adg.product.XXXX") - used for API calls */
    @ColumnInfo("product_id")
    val productId: String,

    /**
     * Top-level entitlement UUID from the GetEntitlements response
     * (e.g. "08c0fa3b-3448-19b7-242c-4191201d0515").
     * Required by the GetGameDownload API — different from the product ID.
     */
    @ColumnInfo(name = "entitlement_id", defaultValue = "")
    val entitlementId: String = "",

    @ColumnInfo("title")
    val title: String = "",

    // Installation info
    @ColumnInfo("is_installed")
    val isInstalled: Boolean = false,

    @ColumnInfo("install_path")
    val installPath: String = "",

    // Art – full HTTPS URL to the product icon/box image (may be empty if not provided by API)
    @ColumnInfo("art_url")
    val artUrl: String = "",

    // Hero/background image URL for the detail screen (wide, landscape)
    @ColumnInfo(name = "hero_url", defaultValue = "")
    val heroUrl: String = "",

    /** ISO-8601 date string from the entitlement, e.g. "2024-01-15T00:00:00.000Z" */
    @ColumnInfo("purchased_date")
    val purchasedDate: String = "",

    /** Game developer name extracted from productDetail.details */
    @ColumnInfo(name = "developer", defaultValue = "")
    val developer: String = "",

    /** Game publisher name extracted from productDetail.details */
    @ColumnInfo(name = "publisher", defaultValue = "")
    val publisher: String = "",

    /** Release date string extracted from productDetail.details (ISO-8601 or "YYYY-MM-DD") */
    @ColumnInfo(name = "release_date", defaultValue = "")
    val releaseDate: String = "",

    /** Download size in bytes extracted from productDetail.details */
    @ColumnInfo(name = "download_size", defaultValue = "0")
    val downloadSize: Long = 0,

    /** On-disk installed size in bytes, derived from the download manifest. */
    @ColumnInfo(name = "install_size", defaultValue = "0")
    val installSize: Long = 0,

    /** Amazon version ID returned by GetGameDownload, stored at install time for update checking. */
    @ColumnInfo(name = "version_id", defaultValue = "")
    val versionId: String = "",

    /** Product SKU from entitlement response — used for FuelPump environment variables. */
    @ColumnInfo(name = "product_sku", defaultValue = "")
    val productSku: String = "",

    /** Epoch millis of the last time the game was launched. 0 = never played. */
    @ColumnInfo(name = "last_played", defaultValue = "0")
    val lastPlayed: Long = 0,

    /** Cumulative play time in seconds. */
    @ColumnInfo(name = "play_time_minutes", defaultValue = "0")
    val playTimeMinutes: Long = 0,

    /** Raw product JSON kept for manifest lookup, etc. */
    @ColumnInfo("product_json")
    val productJson: String = "",
)

/**
 * Amazon Games credentials for OAuth authentication (PKCE-based).
 *
 * Unlike Epic/GOG, Amazon uses a dynamic client_id and device_serial
 * that must be persisted alongside the tokens for refresh & deregister.
 */
data class AmazonCredentials(
    val accessToken: String,
    val refreshToken: String,
    val deviceSerial: String,
    val clientId: String,
    val expiresAt: Long = 0,
)
