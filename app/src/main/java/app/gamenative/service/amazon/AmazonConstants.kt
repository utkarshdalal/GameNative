package app.gamenative.service.amazon

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import java.io.File
import java.nio.file.Paths
import timber.log.Timber

/** Constants for Amazon Games integration. */
object AmazonConstants {

    // ── Device registration identifiers (from nile) ─────────────────────────
    const val DEVICE_TYPE = "A2UMVHOX7UP4V7"
    const val MARKETPLACE_ID = "ATVPDKIKX0DER"
    const val APP_NAME = "AGSLauncher for Windows"
    const val APP_VERSION = "1.0.0"

    // ── Amazon API endpoints ────────────────────────────────────────────────
    const val AMAZON_API_BASE = "https://api.amazon.com"
    const val AUTH_REGISTER_URL = "$AMAZON_API_BASE/auth/register"
    const val AUTH_TOKEN_URL = "$AMAZON_API_BASE/auth/token"
    const val AUTH_DEREGISTER_URL = "$AMAZON_API_BASE/auth/deregister"

    // ── Amazon Gaming API ───────────────────────────────────────────────────
    const val GAMING_API_BASE = "https://gaming.amazon.com/api"
    const val ENTITLEMENTS_URL = "$GAMING_API_BASE/distribution/entitlements"

    // ── OpenID Connect / OAuth parameters ───────────────────────────────────
    const val OPENID_NS = "http://specs.openid.net/auth/2.0"
    const val OPENID_NS_PAPE = "http://specs.openid.net/extensions/pape/1.0"
    const val OPENID_NS_OA2 = "http://www.amazon.com/ap/ext/oauth/2"
    const val OPENID_ASSOC_HANDLE = "amzn_sonic_games_launcher"
    const val OPENID_CLAIMED_ID = "http://specs.openid.net/auth/2.0/identifier_select"
    const val OPENID_IDENTITY = "http://specs.openid.net/auth/2.0/identifier_select"
    const val OPENID_MODE = "checkid_setup"
    const val OPENID_RETURN_TO = "https://www.amazon.com"

    /** OAuth scope required for device authentication. */
    const val OA2_SCOPE = "device_auth_access"

    const val OA2_RESPONSE_TYPE = "code"

    // ── User-Agent ──────────────────────────────────────────────────────────
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    // ── Amazon Gaming API identifiers ───────────────────────────────────────
    const val GAMING_USER_AGENT = "com.amazon.agslauncher.win/3.0.9202.1"
    const val GAMING_KEY_ID = "d5dc8b8b-86c8-4fc4-ae93-18c0def5314d"

    // ── SDK / Launcher channel ──────────────────────────────────────────────
    const val LAUNCHER_CHANNEL_ID = "87d38116-4cbf-4af0-a371-a5b498975346"

    fun defaultAmazonGamesPath(context: Context): String {
        return if (PrefManager.useExternalStorage && File(PrefManager.externalStoragePath).exists()) {
            val path = Paths.get(PrefManager.externalStoragePath, "Amazon", "games").toString()
            Timber.i("Amazon using external storage: $path")
            File(path).mkdirs()
            path
        } else {
            val path = File(context.filesDir, "Amazon").absolutePath
            Timber.i("Amazon using internal storage: $path")
            // Ensure directory exists for StatFs
            File(path).mkdirs()
            path
        }
    }

    /** Return the install directory for a specific Amazon game title. */
    fun getGameInstallPath(context: Context, gameTitle: String): String {
        val sanitized = gameTitle.replace(Regex("[^a-zA-Z0-9 \\-_]"), "").trim()
        val dirName = sanitized.ifEmpty { "game_${gameTitle.hashCode().toUInt()}" }
        return Paths.get(defaultAmazonGamesPath(context), dirName).toString()
    }

    /** Build the Amazon OAuth login URL for a PKCE challenge and dynamic clientId. */
    fun buildAuthUrl(clientId: String, codeChallenge: String): String {
        return Uri.Builder()
            .scheme("https")
            .authority("www.amazon.com")
            .path("/ap/signin")
            .appendQueryParameter("openid.ns", OPENID_NS)
            .appendQueryParameter("openid.claimed_id", OPENID_CLAIMED_ID)
            .appendQueryParameter("openid.identity", OPENID_IDENTITY)
            .appendQueryParameter("openid.mode", OPENID_MODE)
            .appendQueryParameter("openid.oa2.scope", OA2_SCOPE)
            .appendQueryParameter("openid.ns.oa2", OPENID_NS_OA2)
            .appendQueryParameter("openid.oa2.response_type", OA2_RESPONSE_TYPE)
            .appendQueryParameter("openid.oa2.code_challenge_method", "S256")
            .appendQueryParameter("openid.oa2.client_id", "device:$clientId")
            .appendQueryParameter("language", "en_US")
            .appendQueryParameter("marketPlaceId", MARKETPLACE_ID)
            .appendQueryParameter("openid.return_to", OPENID_RETURN_TO)
            .appendQueryParameter("openid.pape.max_auth_age", "0")
            .appendQueryParameter("openid.ns.pape", OPENID_NS_PAPE)
            .appendQueryParameter("openid.assoc_handle", OPENID_ASSOC_HANDLE)
            .appendQueryParameter("pageId", OPENID_ASSOC_HANDLE)
            .appendQueryParameter("openid.oa2.code_challenge", codeChallenge)
            .build()
            .toString()
    }
}
