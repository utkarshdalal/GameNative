package app.gamenative.service.epic

import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import timber.log.Timber

/**
 * Constants for Epic Games Store integration via Legendary CLI
 *
 * Epic uses OAuth 2.0 for authentication and provides API access through
 * GraphQL endpoints. All game operations are managed through the Legendary CLI.
 */
object EpicConstants {
    // ! OAuth Configuration - Using Legendary's official credentials (Do not worry, these are hard-coded and not sensitive.)
    const val EPIC_CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
    const val EPIC_CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf"

    // Epic OAuth URLs
    const val EPIC_AUTH_BASE_URL = "https://www.epicgames.com"
    const val EPIC_OAUTH_TOKEN_URL = "https://account-public-service-prod.ol.epicgames.com/account/api/oauth/token"

    // Redirect URI for OAuth callback - using Epic's standard redirect endpoint
    const val EPIC_REDIRECT_URI = "https://www.epicgames.com/id/api/redirect"

    const val OAUTH_HOST = "account-public-service-prod03.ol.epicgames.com"
    const val USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"

    // OAuth authorization URL with all required parameters
    // This is the standard Epic Games OAuth login flow
    const val EPIC_AUTH_LOGIN_URL =
        "$EPIC_AUTH_BASE_URL/id/login" +
            "?redirectUrl=$EPIC_REDIRECT_URI" +
            "%3FclientId%3D$EPIC_CLIENT_ID" +
            "%26responseType%3Dcode"

    // Epic GraphQL API endpoints
    const val EPIC_GRAPHQL_URL = "https://launcher.store.epicgames.com/graphql"
    const val EPIC_STORE_API_URL = "https://store-content.ak.epicgames.com/api"
    const val EPIC_LIBRARY_API_URL = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items"

    // Epic CDN for game assets
    const val EPIC_CATALOG_API_URL = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api"

    // Epic Launcher API for manifests
    const val EPIC_LAUNCHER_API_URL = "https://launcher-public-service-prod06.ol.epicgames.com"

    // User Agent for API requests (Legendary CLI)
    val EPIC_USER_AGENT = "Legendary/${getBuildVersion()} (GameNative)"

    // Legendary CLI configuration
    const val LEGENDARY_CONFIG_DIR = "legendary"
    const val LEGENDARY_USER_FILE = "user.json"
    const val LEGENDARY_INSTALLED_FILE = "installed.json"
    const val LEGENDARY_METADATA_DIR = "metadata"
    const val LEGENDARY_TMP_DIR = "tmp"

    // Environment variables for Epic Games
    const val ENV_EOS_APP_ID = "EOS_APP_ID"
    const val ENV_EOS_OVERLAY_ENABLED = "EOS_OVERLAY_ENABLED"

    // Epic game launch parameters
    const val LAUNCH_PARAM_AUTH_PASSWORD = "-AUTH_PASSWORD"
    const val LAUNCH_PARAM_AUTH_TYPE = "-AUTH_TYPE"
    const val LAUNCH_PARAM_EPIC_APP = "-epicapp"
    const val LAUNCH_PARAM_EPIC_ENV = "-epicenv"
    const val LAUNCH_PARAM_EPIC_USERNAME = "-epicusername"
    const val LAUNCH_PARAM_EPIC_USERID = "-epicuserid"
    const val LAUNCH_PARAM_EPIC_LOCALE = "-epiclocale"
    const val LAUNCH_PARAM_EPIC_OVT = "-epicovt"
    const val LAUNCH_PARAM_EPIC_SANDBOX = "-epicsandboxid"
    const val LAUNCH_PARAM_EPIC_DEPLOYMENT = "-epicdeploymentid"

    // Auth types
    const val AUTH_TYPE_EXCHANGE_CODE = "exchangecode"
    const val AUTH_TYPE_DEVICE_AUTH = "deviceauth"

    // Epic Games installation paths
    private const val INTERNAL_BASE_PATH = "/data/data/app.gamenative/files"

    /**
     * Internal Epic games installation path (similar to Steam's internal path)
     * /data/data/app.gamenative/files/Epic/games/
     */
    val internalEpicGamesPath: String
        get() = Paths.get(INTERNAL_BASE_PATH, "Epic", "games").toString()

    /**
     * External Epic games installation path
     * {externalStoragePath}/Epic/games/
     */
    val externalEpicGamesPath: String
        get() = Paths.get(PrefManager.externalStoragePath, "Epic", "games").toString()

    /**
     * Default Epic games installation path - uses external storage if available
     */
    val defaultEpicGamesPath: String
        get() {
            return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                Timber.i("Epic using external storage: $externalEpicGamesPath")
                externalEpicGamesPath
            } else {
                Timber.i("Epic using internal storage: $internalEpicGamesPath")
                internalEpicGamesPath
            }
        }

    /**
     * Legendary configuration directory path
     * /data/data/app.gamenative/files/legendary/
     */
    val legendaryConfigPath: String
        get() = Paths.get(INTERNAL_BASE_PATH, LEGENDARY_CONFIG_DIR).toString()

    /**
     * Get the installation path for a specific Epic game
     * Sanitizes the game title to be filesystem-safe
     */
    fun getGameInstallPath(gameTitle: String): String {
        // Sanitize game title for filesystem
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9 -_]"), "").trim()
        return Paths.get(defaultEpicGamesPath, sanitizedTitle).toString()
    }

    /**
     * Get the installation path by app name (preferred method)
     */
    fun getGameInstallPathByAppName(appName: String): String {
        return Paths.get(defaultEpicGamesPath, appName).toString()
    }

    /**
     * Get build version for user agent
     */
    private fun getBuildVersion(): String {
        return "0.1.0" // TODO: Pull from BuildConfig
    }

    // Platform identifier (GameNative only supports Windows games via Wine)
    const val PLATFORM_WINDOWS = "Windows"

    // Epic environment types
    const val EPIC_ENV_PROD = "Prod"
    const val EPIC_ENV_STAGE = "Stage"

    // Default locale
    const val DEFAULT_LOCALE = "en-US"

    // Cache TTL (milliseconds)
    const val LIBRARY_CACHE_TTL = 3600000L // 1 hour
    const val TOKEN_REFRESH_BUFFER = 300000L // 5 minutes before expiry

    // Legendary command timeout
    const val LEGENDARY_COMMAND_TIMEOUT = 30000L // 30 seconds
    const val LEGENDARY_DOWNLOAD_TIMEOUT = 0L // No timeout for downloads

    // SharedPreferences keys
    const val PREF_NAME = "epic_credentials"
    const val PREF_KEY_DISPLAY_NAME = "display_name"
    const val PREF_KEY_ACCOUNT_ID = "account_id"
    const val PREF_KEY_LAST_LIBRARY_SYNC = "last_library_sync"

    // Error messages
    const val ERROR_NOT_LOGGED_IN = "Not logged in to Epic Games"
    const val ERROR_GAME_NOT_FOUND = "Game not found"
    const val ERROR_GAME_NOT_INSTALLED = "Game not installed"
    const val ERROR_LEGENDARY_FAILED = "Legendary CLI execution failed"
    const val ERROR_INVALID_CREDENTIALS = "Invalid Epic credentials"
    const val ERROR_TOKEN_EXPIRED = "Epic token expired"
}
