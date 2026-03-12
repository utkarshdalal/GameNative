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
    const val GOG_FALLBACK_DOWNLOAD_LANGUAGE = "english"

    /**
     * GOG language codes per container language: container name first (for manifests that use "German" etc.),
     * then GOG codes. Keys match [PrefManager.containerLanguage]. Unknown languages fall back to English.
     */
    internal val CONTAINER_LANGUAGE_TO_GOG_CODES: Map<String, List<String>> = mapOf(
        "arabic" to listOf("arabic", "ar"),
        "bulgarian" to listOf("bulgarian", "bg-BG", "bg", "bl"),
        "schinese" to listOf("schinese", "zh-Hans", "zh_Hans", "zh", "cn"),
        "tchinese" to listOf("tchinese", "zh-Hant", "zh_Hant"),
        "czech" to listOf("czech", "cs-CZ", "cz"),
        "danish" to listOf("danish", "da-DK", "da"),
        "dutch" to listOf("dutch", "nl-NL", "nl"),
        "english" to listOf("english", "en-US", "en"),
        "finnish" to listOf("finnish", "fi-FI", "fi"),
        "french" to listOf("french", "fr-FR", "fr"),
        "german" to listOf("german", "de-DE", "de"),
        "greek" to listOf("greek", "el-GR", "gk", "el-GK"),
        "hungarian" to listOf("hungarian", "hu-HU", "hu"),
        "italian" to listOf("italian", "it-IT", "it"),
        "japanese" to listOf("japanese", "ja-JP", "jp"),
        "koreana" to listOf("koreana", "ko-KR", "ko"),
        "norwegian" to listOf("norwegian", "nb-NO", "no"),
        "polish" to listOf("polish", "pl-PL", "pl"),
        "portuguese" to listOf("portuguese", "pt-PT", "pt"),
        "brazilian" to listOf("brazilian", "pt-BR", "br"),
        "romanian" to listOf("romanian", "ro-RO", "ro"),
        "russian" to listOf("russian", "ru-RU", "ru"),
        "spanish" to listOf("spanish", "es-ES", "es"),
        "latam" to listOf("latam", "es-MX", "es_mx"),
        "swedish" to listOf("swedish", "sv-SE", "sv"),
        "thai" to listOf("thai", "th-TH", "th"),
        "turkish" to listOf("turkish", "tr-TR", "tr"),
        "ukrainian" to listOf("ukrainian", "uk-UA", "uk"),
        "vietnamese" to listOf("vietnamese", "vi-VN", "vi"),
    )

    /**
     * Maps container language name (e.g. "english", "german") to an ordered list of GOG manifest language codes
     * (primary first, then fallbacks). Uses the same names as [PrefManager.containerLanguage].
     * Returns English codes (CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOG_FALLBACK_DOWNLOAD_LANGUAGE)) for unknown languages.
     */
    fun containerLanguageToGogCodes(containerLanguage: String): List<String> =
        CONTAINER_LANGUAGE_TO_GOG_CODES[containerLanguage.lowercase()] ?: CONTAINER_LANGUAGE_TO_GOG_CODES.getValue(GOG_FALLBACK_DOWNLOAD_LANGUAGE)

    /** Path under _CommonRedist to a file that indicates this dependency is installed. */
    val GOG_DEPENDENCY_INSTALLED_PATH: Map<String, String> = mapOf(
        "ISI" to "ISI/scriptinterpreter.exe",
        "MSVC2017" to "MSVC2017/VC_redist.x86.exe",
        "MSVC2017_x64" to "MSVC2017_x64/VC_redist.x64.exe",
    )

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
            val path = Paths.get(context.dataDir.path, "GOG", "games", "common").toString()
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
