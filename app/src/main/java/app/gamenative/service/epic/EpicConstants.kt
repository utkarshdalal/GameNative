package app.gamenative.service.epic

import android.net.Uri
import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import java.security.SecureRandom
import timber.log.Timber

/**
 * Constants for Epic Games Store integration
 */
object EpicConstants {
    //! OAuth Configuration - Using Legendary's official credentials (Do not worry, these are hard-coded and not sensitive.)
    const val EPIC_CLIENT_ID = "34a02cf8f4414e29b15921876da36f9a"
    const val EPIC_CLIENT_SECRET = "daafbccc737745039dffe53d94fc76cf"

    // Epic OAuth URLs
    const val EPIC_AUTH_BASE_URL = "https://www.epicgames.com"
    const val EPIC_OAUTH_TOKEN_URL = "https://account-public-service-prod.ol.epicgames.com/account/api/oauth/token"

    // Redirect URI for OAuth callback - using Epic's standard redirect endpoint
    const val EPIC_REDIRECT_URI = "https://www.epicgames.com/id/api/redirect"

    const val ECOMMERCE_HOST = "ecommerceintegration-public-service-ecomprod02.ol.epicgames.com"
    const val OAUTH_HOST = "account-public-service-prod03.ol.epicgames.com"
    const val USER_AGENT = "UELauncher/11.0.1-14907503+++Portal+Release-Live Windows/10.0.19041.1.256.64bit"

    /** Base URL for OAuth login (append &state=... for CSRF protection). */
    val EPIC_AUTH_LOGIN_URL: String
        get() = "$EPIC_AUTH_BASE_URL/id/login" +
            "?redirectUrl=$EPIC_REDIRECT_URI" +
            "%3FclientId%3D$EPIC_CLIENT_ID" +
            "%26responseType%3Dcode"

    /**
     * Builds a full OAuth login URL with a fresh state parameter for CSRF protection.
     * @return Pair of (full auth URL, state) – store state and validate it on redirect.
     */
    fun LoginUrlWithState(): Pair<String, String> {
        val state = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val url = "$EPIC_AUTH_LOGIN_URL&state=${Uri.encode(state)}"
        return url to state
    }

    const val EPIC_LIBRARY_API_URL = "https://library-service.live.use1a.on.epicgames.com/library/api/public/items"
    // Epic CDN for game assets
    const val EPIC_CATALOG_API_URL = "https://catalog-public-service-prod06.ol.epicgames.com/catalog/api"
    // Epic Launcher API for manifests
    const val EPIC_LAUNCHER_API_URL = "https://launcher-public-service-prod06.ol.epicgames.com"

    // User Agent for API requests (Legendary CLI)
    val EPIC_USER_AGENT = "Legendary/${getBuildVersion()} (GameNative)"

    // Epic Games installation paths

    /**
     * Internal Epic games installation path (similar to Steam's internal path)
     * {context.dataDir}/Epic/games/
     */
    fun internalEpicGamesPath(context: android.content.Context): String {
        val path = Paths.get(context.dataDir.path, "Epic", "games").toString()
        File(path).mkdirs()
        return path
    }

    /**
     * External Epic games installation path
     * {externalStoragePath}/Epic/games/
     */
    fun externalEpicGamesPath(): String {
        val path = Paths.get(PrefManager.externalStoragePath, "Epic", "games").toString()
        // Ensure directory exists for StatFs
        File(path).mkdirs()
        return path
    }

    /**
     * Default Epic games installation path - uses external storage if available
     */
    fun defaultEpicGamesPath(context: android.content.Context): String {
        return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
            val path = externalEpicGamesPath()
            Timber.i("Epic using external storage: $path")
            path
        } else {
            val path = internalEpicGamesPath(context)
            Timber.i("Epic using internal storage: $path")
            // Ensure directory exists for StatFs
            File(path).mkdirs()
            path
        }
    }

    /**
     * Get the installation path for a specific Epic game
     * Sanitizes the game title to be filesystem-safe
     */
    fun getGameInstallPath(context: android.content.Context, gameTitle: String): String {
        // Sanitize game title for filesystem
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
        return Paths.get(defaultEpicGamesPath(context), sanitizedTitle).toString()
    }

    /**
     * Get build version for user agent
     */
    private fun getBuildVersion(): String {
        return "0.1.0" // TODO: Pull from BuildConfig
    }
}
