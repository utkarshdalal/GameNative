package app.gamenative.service.rockstar

import android.content.Context
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import app.gamenative.Crypto
import java.io.File
import org.json.JSONObject
import timber.log.Timber

data class RockstarCredentials(
    val scAuthToken: String,
    val obtainedAt: Long,
    val nickname: String,
    val rockstarId: String,
    /*
     * The gateway also returns these. They are what the sign-in page's "keep me signed in" box
     * controls, and they are the means of getting a fresh ScAuthToken later without sending the
     * user back through the form -- ScAuthTokens expire, so throwing them away would turn every
     * expiry into a full re-login. Kept for that, not used yet.
     */
    val loginGuid: String = "",
    val rememberedMachineToken: String = "",
    val launcherTicket: String = "",
)

/**
 * Rockstar account session for the Social Club stub.
 *
 * The stub mints its ROS ticket with CreateTicketScAuthToken2, which needs an ScAuthToken. Minting
 * from Steam ownership instead was tried and rejected (CreateTicketScSteam2 returns HTTP 400 for
 * both ticket encodings while the same ticket is accepted by GetEntitlements), so a Rockstar
 * sign-in is required and this is where it happens.
 *
 * WHAT IS NOT YET KNOWN: exactly where the token surfaces in the /sdk flow. The launcher's page
 * uses reCAPTCHA Enterprise and a Castle token added by its own fetchJson, and earlier work
 * recorded a `gn_code` cookie exchanged at /api/connect/gateway on the launcher host. Rather than
 * guess, [observe] records every navigation and every cookie on the Rockstar hosts so one real
 * sign-in shows where it appears, and [findToken] accepts the candidates we know how to spot.
 * Until that is confirmed against a real sign-in, treat capture as unproven.
 */
object RockstarAuthManager {
    private var cached: RockstarCredentials? = null

    /** Filled by the last successful exchange so [store] can persist them alongside the token. */
    private var lastExtras: Triple<String, String, String> = Triple("", "", "")

    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * A code from a previous attempt is worse than none: it looks valid, is picked up immediately,
     * and the exchange fails with 401 because it expired minutes ago. Drop it before starting.
     */
    fun clearHandoffCookie() {
        val cm = CookieManager.getInstance()
        for (h in listOf(SIGNIN_URL, LAUNCHER_URL, "https://.rockstargames.com")) {
            runCatching {
                cm.setCookie(h, "${RockstarConstants.HANDOFF_COOKIE}=; Max-Age=0; path=/")
                cm.setCookie(h, "${RockstarConstants.HANDOFF_COOKIE}=; Max-Age=0; path=/; domain=.rockstargames.com")
            }
        }
        runCatching { cm.flush() }
        Timber.i("Rockstar sign-in: cleared any stale %s cookie", RockstarConstants.HANDOFF_COOKIE)
    }

    private const val SIGNIN_URL = "https://signin.rockstargames.com"
    private const val LAUNCHER_URL = "https://rgl.rockstargames.com"

    /**
     * Exchange the auth code for the token.
     *
     * Done here rather than by pointing the WebView at the launcher origin: that origin's root
     * answers a plain browser with 403, so navigating there strands the user on an error page.
     * The request still needs that origin's cookies, which are read from the WebView's store.
     *
     * The fingerprint parameter's encoding is unconfirmed, so the status and the response's field
     * names are reported whatever happens.
     */
    suspend fun exchange(authCode: String, fingerprintJson: String): Result<String> = withContext(Dispatchers.IO) {
        val cookies = runCatching { CookieManager.getInstance().getCookie(LAUNCHER_URL) }.getOrNull().orEmpty()
        val url = RockstarConstants.gatewayUrl(
            java.net.URLEncoder.encode(authCode, "UTF-8"),
            java.net.URLEncoder.encode(fingerprintJson, "UTF-8"),
        )
        val req = Request.Builder()
            .url(url)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", "$LAUNCHER_URL/")
            .header("Origin", LAUNCHER_URL)
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookies) }
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val names = runCatching { org.json.JSONObject(body).keys().asSequence().joinToString(",") }
                    .getOrDefault("not json")
                Timber.i("Rockstar sign-in: gateway status=%d fields=[%s] body=%d bytes",
                    resp.code, names, body.length)
                val j = runCatching { org.json.JSONObject(body) }.getOrNull()
                val token = j?.optString("ScAuthToken")?.ifEmpty { j.optString("scAuthToken") }.orEmpty()
                if (token.isEmpty() || !looksLikeScAuthToken(token)) {
                    throw IllegalStateException("gateway ${resp.code}, fields [$names]")
                }
                lastExtras = Triple(
                    j?.optString("LoginGuid").orEmpty(),
                    j?.optString("RememberedMachineToken").orEmpty(),
                    j?.optString("LauncherTicket").orEmpty(),
                )
                Timber.i("Rockstar sign-in: also kept loginGuid=%s rememberedMachine=%s launcherTicket=%s",
                    lastExtras.first.isNotEmpty(), lastExtras.second.isNotEmpty(), lastExtras.third.isNotEmpty())
                token
            }
        }
    }

    private fun credentialsFile(context: Context) = File(context.filesDir, RockstarConstants.CREDENTIALS_FILE)

    fun isLoggedIn(context: Context): Boolean = load(context) != null

    fun nickname(context: Context): String? = load(context)?.nickname

    fun logout(context: Context) {
        credentialsFile(context).delete()
        cached = null
    }

    fun buildLoginUrl(): String = RockstarConstants.signInUrl()

    /**
     * Log where we are and what has been set, so the first real sign-in tells us where the token
     * lives instead of us inferring it. Values are never logged, only their names and shapes.
     */
    fun observe(stage: String, url: String?) {
        val host = url?.substringAfter("://")?.substringBefore('/') ?: "(none)"
        val path = url?.substringAfter(host)?.substringBefore('?') ?: ""
        Timber.i("Rockstar sign-in [%s] host=%s path=%s", stage, host, path)
        for (h in listOf(RockstarConstants.SIGNIN_HOST, RockstarConstants.LAUNCHER_HOST)) {
            val raw = runCatching { CookieManager.getInstance().getCookie("https://$h") }.getOrNull() ?: continue
            val described = raw.split(';').mapNotNull {
                val kv = it.trim().split('=', limit = 2)
                if (kv.size != 2 || kv[1].isEmpty()) null else "${kv[0]}(${kv[1].length} chars)"
            }
            if (described.isNotEmpty()) Timber.i("Rockstar sign-in [%s] cookies on %s: %s", stage, h, described.joinToString(" "))
        }
    }

    /** A token in a redirect or a cookie, if one is recognisable. Null when nothing matches. */
    fun findToken(url: String?): String? {
        url?.let { u ->
            for (key in listOf("scAuthToken", "gn_code", "code", "token")) {
                val v = Regex("[?&]$key=([^&#]+)").find(u)?.groupValues?.get(1)
                if (!v.isNullOrEmpty()) {
                    Timber.i("Rockstar sign-in: candidate from url param '%s' (%d chars)", key, v.length)
                    return v
                }
            }
        }
        for (h in listOf(RockstarConstants.LAUNCHER_HOST, RockstarConstants.SIGNIN_HOST)) {
            val raw = runCatching { CookieManager.getInstance().getCookie("https://$h") }.getOrNull() ?: continue
            for (part in raw.split(';')) {
                val kv = part.trim().split('=', limit = 2)
                if (kv.size != 2) continue
                if (kv[0] !in listOf("scAuthToken", "gn_code", "rsso")) continue
                if (kv[1].isEmpty()) continue
                Timber.i("Rockstar sign-in: candidate from cookie '%s' on %s (%d chars)", kv[0], h, kv[1].length)
                return kv[1]
            }
        }
        return null
    }

    /** Whether a captured value looks like the ScAuthToken the stub wants. */
    fun looksLikeScAuthToken(value: String) = RockstarConstants.TOKEN_SHAPE.matches(value)

    fun store(context: Context, token: String, nickname: String = "", rockstarId: String = "") {
        val creds = RockstarCredentials(
            token, System.currentTimeMillis(), nickname, rockstarId,
            lastExtras.first, lastExtras.second, lastExtras.third,
        )
        val json = JSONObject()
            .put("sc_auth_token", creds.scAuthToken)
            .put("obtained_at", creds.obtainedAt)
            .put("nickname", creds.nickname)
            .put("rockstar_id", creds.rockstarId)
            .put("login_guid", creds.loginGuid)
            .put("remembered_machine_token", creds.rememberedMachineToken)
            .put("launcher_ticket", creds.launcherTicket)
            .toString()
        credentialsFile(context).writeBytes(Crypto.encrypt(json.toByteArray()))
        cached = creds
        Timber.i("Rockstar session stored (%d chars)", token.length)
    }

    fun load(context: Context): RockstarCredentials? {
        cached?.let { return it }
        val f = credentialsFile(context)
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(String(Crypto.decrypt(f.readBytes())))
            RockstarCredentials(
                scAuthToken = json.getString("sc_auth_token"),
                obtainedAt = json.getLong("obtained_at"),
                nickname = json.optString("nickname"),
                rockstarId = json.optString("rockstar_id"),
                loginGuid = json.optString("login_guid"),
                rememberedMachineToken = json.optString("remembered_machine_token"),
                launcherTicket = json.optString("launcher_ticket"),
            ).also { cached = it }
        }.onFailure { Timber.w(it, "Rockstar credentials unreadable, discarding"); f.delete() }.getOrNull()
    }
}
