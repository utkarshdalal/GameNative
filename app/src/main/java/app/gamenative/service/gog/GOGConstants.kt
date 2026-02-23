package app.gamenative.service.gog

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import java.security.SecureRandom
import timber.log.Timber

/**
 * Constants for GOG integration
 */
object GOGConstants {
    private var appContext: Context? = null

    /**
     * Initialize GOGConstants with application context
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }
    // GOG API URLs
    const val GOG_BASE_API_URL = "https://api.gog.com"
    const val GOG_AUTH_URL = "https://auth.gog.com"
    const val GOG_EMBED_URL = "https://embed.gog.com"
    const val GOG_GAMESDB_URL = "https://gamesdb.gog.com"

    // GOG Client ID for authentication - These are public and not sensitive information.
    const val GOG_CLIENT_ID = "46899977096215655"
    const val GOG_CLIENT_SECRET = "9d85c43b1482497dbbce61f6e4aa173a433796eeae2ca8c5f6129f2dc4de46d9"

    // GOG uses a standard redirect URI that we can intercept
    const val GOG_REDIRECT_URI = "https://embed.gog.com/on_login_success?origin=client"

    /** Base URL for OAuth login (append &state=... for CSRF protection). */
    val GOG_AUTH_LOGIN_URL: String
        get() = "https://auth.gog.com/auth?" +
            "client_id=$GOG_CLIENT_ID" +
            "&redirect_uri=${Uri.encode(GOG_REDIRECT_URI)}" +
            "&response_type=code" +
            "&layout=galaxy"

    /** GOG download language (short code as used in manifest depots, e.g. "en"). */
    const val GOG_DOWNLOAD_LANGUAGE = "en"

    /**
     * Builds a full Galaxy OAuth login URL with a fresh state parameter for CSRF protection.
     * @return Pair of (full auth URL, state) – store state and validate it on redirect.
     */
    fun LoginUrlWithState(): Pair<String, String> {
        val state = ByteArray(32).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val url = "$GOG_AUTH_LOGIN_URL&state=${Uri.encode(state)}"
        return url to state
    }

    /**
     * Internal GOG games installation path (similar to Steam's internal path)
     * Uses application's internal files directory
     */
    val internalGOGGamesPath: String
        get() {
            val context = appContext ?: throw IllegalStateException("GOGConstants not initialized. Call init() first.")
            val path = Paths.get(context.filesDir.absolutePath, "GOG", "games", "common").toString()
            // Ensure directory exists for StatFs
            File(path).mkdirs()
            return path
        }

    /**
     * External GOG games installation path (similar to Steam's external path)
     * {externalStoragePath}/GOG/games/common/
     */
    val externalGOGGamesPath: String
        get() {
            val path = Paths.get(PrefManager.externalStoragePath, "GOG", "games", "common").toString()
            // Ensure directory exists for StatFs
            File(path).mkdirs()
            return path
        }

    val defaultGOGGamesPath: String
        get() {
            return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
                Timber.i("GOG using external storage: $externalGOGGamesPath")
                externalGOGGamesPath
            } else {
                Timber.i("GOG using internal storage: $internalGOGGamesPath")
                internalGOGGamesPath
            }
        }

    fun getGameInstallPath(gameTitle: String): String {
        // Sanitize game title for filesystem
        val sanitizedTitle = gameTitle.replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        return Paths.get(defaultGOGGamesPath, sanitizedTitle).toString()
    }
}
