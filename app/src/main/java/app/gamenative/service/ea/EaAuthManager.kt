package app.gamenative.service.ea

import android.content.Context
import android.net.Uri
import android.provider.Settings
import app.gamenative.Crypto
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

data class EaCredentials(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val personaId: String,
    val displayName: String,
)

/**
 * EA account session for the LSX launcher: the same Juno OAuth flow the EA app uses
 * (JUNO_PC_CLIENT, PKCE, pc_sign), with the code captured from the qrc:// redirect in a WebView.
 */
object EaAuthManager {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val lock = Mutex()
    private var cached: EaCredentials? = null
    private var pendingVerifier: String? = null

    private fun credentialsFile(context: Context) = File(context.filesDir, EaConstants.CREDENTIALS_FILE)

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun isLoggedIn(context: Context): Boolean = load(context) != null

    fun displayName(context: Context): String? = load(context)?.displayName

    fun logout(context: Context) {
        credentialsFile(context).delete()
        cached = null
    }

    /** Builds the login page URL; a fresh PKCE verifier is kept for the following code exchange. */
    fun buildLoginUrl(context: Context): String {
        val verifier = b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        pendingVerifier = verifier
        val challenge = b64url(EaCrypto.sha256(verifier.toByteArray()))
        return authUrl(context, EaConstants.CLIENT_ID, "code", null, extra = mapOf(
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
        ))
    }

    /** True when the WebView navigated to EA's success redirect (a Qt resource URL). */
    fun isRedirect(url: String): Boolean = url.startsWith("qrc:", ignoreCase = true) && url.contains("login_successful")

    fun extractCode(url: String): String? {
        val q = url.substringAfter('?', "").substringBefore('#')
        val frag = url.substringAfter('#', "")
        for (part in (q.split('&') + frag.split('&'))) {
            val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k == "code" && v.isNotEmpty()) return Uri.decode(v)
        }
        return null
    }

    suspend fun authenticateWithCode(context: Context, code: String): Result<EaCredentials> = withContext(Dispatchers.IO) {
        runCatching {
            val verifier = pendingVerifier ?: error("No login in progress")
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", verifier)
                .add("client_id", EaConstants.CLIENT_ID)
                .add("client_secret", EaConstants.CLIENT_SECRET)
                .add("redirect_uri", EaConstants.REDIRECT_URI)
                .add("token_format", "JWS")
                .build()
            val token = postToken(form)
            val identity = fetchIdentity(token.getString("access_token"))
            val creds = EaCredentials(
                accessToken = token.getString("access_token"),
                refreshToken = token.getString("refresh_token"),
                expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000,
                userId = identity.first,
                personaId = identity.second,
                displayName = identity.third,
            )
            save(context, creds)
            pendingVerifier = null
            creds
        }.onFailure { Timber.e(it, "EA login failed") }
    }

    /** Valid access token, refreshed when within a minute of expiry. */
    suspend fun accessToken(context: Context): String = lock.withLock {
        val creds = load(context) ?: error("Not signed in to EA")
        if (System.currentTimeMillis() < creds.expiresAt - 60_000) return@withLock creds.accessToken
        withContext(Dispatchers.IO) {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", creds.refreshToken)
                .add("client_id", EaConstants.CLIENT_ID)
                .add("client_secret", EaConstants.CLIENT_SECRET)
                .build()
            val token = postToken(form)
            val refreshed = creds.copy(
                accessToken = token.getString("access_token"),
                refreshToken = token.optString("refresh_token", creds.refreshToken),
                expiresAt = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000,
            )
            save(context, refreshed)
            refreshed.accessToken
        }
    }

    fun credentials(context: Context): EaCredentials? = load(context)

    /**
     * The game asks the launcher for an auth code for its own client id; EA issues it by
     * redirecting an authenticated /connect/auth request.
     */
    suspend fun authCodeFor(context: Context, clientId: String, scope: String? = null): String = withContext(Dispatchers.IO) {
        val token = accessToken(context)
        val url = authUrl(context, clientId, "code", token, extra = if (scope.isNullOrBlank()) emptyMap() else mapOf("scope" to scope))
        redirectParam(url, "code")
    }

    /** Short-lived opaque token the EA app hands games as EALaunchUserAuthToken. */
    suspend fun opaqueLaunchToken(context: Context): String = withContext(Dispatchers.IO) {
        val token = accessToken(context)
        val scopes = "basic.commerce.cartv2 service.atom dp.client.default signin social_recommendation_user " +
            "basic.optin.write basic.commerce.cartv2.write basic.billing external.social_information_ups_admin"
        val url = authUrl(context, EaConstants.CLIENT_ID, "token", token, extra = mapOf(
            "scope" to scopes,
            "token_format" to "OPAQUE",
            "expires_in" to "550",
        ))
        redirectParam(url, "access_token")
    }

    private fun redirectParam(url: String, key: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 EA Download Manager Origin/10.5.94.46774")
            .header("X-Origin-Platform", "PCWIN")
            .build()
        client.newCall(req).execute().use { resp ->
            val location = resp.header("Location") ?: error("EA auth exchange: no redirect (HTTP ${resp.code})")
            if (location.startsWith("https://signin.ea.com")) error("EA auth exchange: session no longer valid")
            val normalized = location.replace("qrc:/html", "http://127.0.0.1")
            val query = normalized.substringAfter('?', "").substringBefore('#')
            val fragment = normalized.substringAfter('#', "")
            for (part in (fragment.split('&') + query.split('&'))) {
                val kv = part.split('=', limit = 2)
                if (kv.size == 2 && kv[0] == key) return Uri.decode(kv[1])
            }
            error("EA auth exchange: '$key' missing from redirect")
        }
    }

    private fun authUrl(context: Context, clientId: String, responseType: String, accessToken: String?, extra: Map<String, String>): String {
        val b = Uri.parse(EaConstants.AUTH_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("sbiod_enabled", "false")
            .appendQueryParameter("response_type", responseType)
            .appendQueryParameter("locale", "en_US")
            .appendQueryParameter("pc_sign", pcSign(context))
            .appendQueryParameter("nonce", SecureRandom().nextInt().toString())
        if (accessToken != null) b.appendQueryParameter("access_token", accessToken)
        for ((k, v) in extra) b.appendQueryParameter(k, v)
        return b.build().toString()
    }

    /** EA's PC fingerprint: an HMAC-signed JSON of stable machine identifiers. Ours derive from the device. */
    private fun pcSign(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "gamenative"
        val bsn = "GN-" + androidId.take(12).uppercase()
        val hsn = androidId.uppercase()
        val msn = "GN" + androidId.takeLast(10).uppercase()
        val mac = "$" + EaCrypto.hex(EaCrypto.sha256(("mac" + androidId).toByteArray()).copyOf(6))
        val midInput = "GameNative" + bsn + "GameNative" + "None" + "1970-01-0100:00:00.000000000+0000" + "None" + mac
        val mid = java.lang.Long.toUnsignedString(EaCrypto.fnv1a64(midInput.toByteArray()))
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss:SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val json = JSONObject()
            .put("av", "v1")
            .put("bsn", bsn)
            .put("gid", 0)
            .put("hsn", hsn)
            .put("mac", mac)
            .put("mid", mid)
            .put("msn", msn)
            .put("sv", "v2")
            .put("ts", ts)
            .toString()
        val payload = b64url(json.toByteArray())
        val sig = EaCrypto.hmacSha256("nt5FfJbdPzNcl2pkC3zgjO43Knvscxft".toByteArray(), payload.toByteArray())
        return payload + "." + b64url(sig)
    }

    private fun postToken(form: FormBody): JSONObject {
        val req = Request.Builder().url(EaConstants.TOKEN_ENDPOINT).post(form).build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("EA token endpoint HTTP ${resp.code}: ${body.take(300)}")
            return JSONObject(body)
        }
    }

    private fun fetchIdentity(accessToken: String): Triple<String, String, String> {
        val req = Request.Builder().url(EaConstants.IDENTITY_ENDPOINT)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Expand-Results", "true")
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("EA identity HTTP ${resp.code}: ${body.take(300)}")
            val personas = JSONObject(body).getJSONObject("personas").getJSONArray("persona")
            val p = personas.getJSONObject(0)
            val userId = p.optString("pidId").ifEmpty { p.getJSONObject("pidId").toString() }
            return Triple(userId, p.optString("personaId"), p.optString("displayName"))
        }
    }

    private fun save(context: Context, creds: EaCredentials) {
        val json = JSONObject()
            .put("access_token", creds.accessToken)
            .put("refresh_token", creds.refreshToken)
            .put("expires_at", creds.expiresAt)
            .put("user_id", creds.userId)
            .put("persona_id", creds.personaId)
            .put("display_name", creds.displayName)
            .toString()
        credentialsFile(context).writeBytes(Crypto.encrypt(json.toByteArray()))
        cached = creds
    }

    private fun load(context: Context): EaCredentials? {
        cached?.let { return it }
        val f = credentialsFile(context)
        if (!f.exists()) return null
        return runCatching {
            val json = JSONObject(String(Crypto.decrypt(f.readBytes())))
            EaCredentials(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresAt = json.getLong("expires_at"),
                userId = json.getString("user_id"),
                personaId = json.getString("persona_id"),
                displayName = json.getString("display_name"),
            ).also { cached = it }
        }.onFailure { Timber.w(it, "EA credentials unreadable, discarding") ; f.delete() }.getOrNull()
    }
}
