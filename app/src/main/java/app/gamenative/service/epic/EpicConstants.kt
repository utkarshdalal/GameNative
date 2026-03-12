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

    /** Container language value for "required only" (no optional language tags). Same key as [PrefManager.containerLanguage]. */
    const val EPIC_FALLBACK_CONTAINER_LANGUAGE = "english"

    /**
     * Maps container language (same values as GOG/Steam container language) to Epic manifest install tag names.
     * Used to download required + selected language files. Keys match [PrefManager.containerLanguage].
     * Each value lists tag names/codes that may appear in manifests (e.g. "German" or "de-DE"). Same codes as GOG where applicable.
     */
    internal val CONTAINER_LANGUAGE_TO_EPIC_INSTALL_TAGS: Map<String, List<String>> = mapOf(
        "arabic" to listOf("Arabic", "ar"),
        "bulgarian" to listOf("Bulgarian", "bg-BG", "bg"),
        "schinese" to listOf("Chinese", "ChineseSimplified", "zh-Hans", "zh_Hans", "zh"),
        "tchinese" to listOf("ChineseTraditional", "zh-Hant", "zh_Hant"),
        "czech" to listOf("Czech", "cs-CZ", "cs"),
        "danish" to listOf("Danish", "da-DK", "da"),
        "dutch" to listOf("Dutch", "nl-NL", "nl"),
        "english" to listOf("English", "en-US", "en"),
        "finnish" to listOf("Finnish", "fi-FI", "fi"),
        "french" to listOf("French", "fr-FR", "fr"),
        "german" to listOf("German", "de-DE", "de"),
        "greek" to listOf("Greek", "el-GR", "el"),
        "hungarian" to listOf("Hungarian", "hu-HU", "hu"),
        "italian" to listOf("Italian", "it-IT", "it"),
        "japanese" to listOf("Japanese", "ja-JP", "ja"),
        "koreana" to listOf("Korean", "ko-KR", "ko"),
        "norwegian" to listOf("Norwegian", "nb-NO", "no"),
        "polish" to listOf("Polish", "pl-PL", "pl"),
        "portuguese" to listOf("Portuguese", "pt-PT", "pt"),
        "brazilian" to listOf("PortugueseBrazilian", "Brazilian", "pt-BR", "br"),
        "romanian" to listOf("Romanian", "ro-RO", "ro"),
        "russian" to listOf("Russian", "ru-RU", "ru"),
        "spanish" to listOf("Spanish", "es-ES", "es"),
        "latam" to listOf("SpanishLatinAmerica", "Latam", "es-MX", "es_mx"),
        "swedish" to listOf("Swedish", "sv-SE", "sv"),
        "thai" to listOf("Thai", "th-TH", "th"),
        "turkish" to listOf("Turkish", "tr-TR", "tr"),
        "ukrainian" to listOf("Ukrainian", "uk-UA", "uk"),
        "vietnamese" to listOf("Vietnamese", "vi-VN", "vi"),
    )

    /**
     * Maps container language name to Epic manifest install tags to include (in addition to required files).
     * Uses the same container language values as GOG. Returns empty list for unknown → required-only is always used as fallback.
     */
    fun containerLanguageToEpicInstallTags(containerLanguage: String): List<String> =
        CONTAINER_LANGUAGE_TO_EPIC_INSTALL_TAGS[containerLanguage.lowercase()]
            ?: CONTAINER_LANGUAGE_TO_EPIC_INSTALL_TAGS.getValue(EPIC_FALLBACK_CONTAINER_LANGUAGE)

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
