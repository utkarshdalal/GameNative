package app.gamenative.service.ubisoft

import android.content.Context
import android.webkit.WebSettings
import app.gamenative.Crypto
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

/**
 * Ubisoft account session for the Windows stub. The web sign-in's ticket is exchanged for a session
 * of the launcher's own app, which is what the stub's services accept. Endpoints, headers and app
 * ids come from the helper's configuration; the password is only ever typed into Ubisoft's page.
 */
object UbisoftAuthManager {
    @Volatile private var cached: UbisoftCredentials? = null

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    sealed class SessionResult {
        data class Ok(val credentials: UbisoftCredentials) : SessionResult()
        data class Rejected(val code: Int) : SessionResult()
        data class Failed(val error: Throwable) : SessionResult()
    }

    sealed class Freshness {
        data class Ready(val credentials: UbisoftCredentials) : Freshness()
        data object NeedsSignIn : Freshness()
        data class Unavailable(val reason: String) : Freshness()
    }

    private fun credentialsFile(context: Context) = File(context.filesDir, UbisoftConstants.CREDENTIALS_FILE)

    fun displayName(context: Context): String? = load(context)?.let { it.nameOnPlatform.ifEmpty { it.email } }

    fun logout(context: Context) {
        credentialsFile(context).delete()
        cached = null
        UbisoftLaunchSupport.clearPlacedSessions(context)
    }

    fun store(context: Context, creds: UbisoftCredentials) {
        credentialsFile(context).writeBytes(Crypto.encrypt(creds.toJson().toString().toByteArray()))
        cached = creds
        Timber.i("Ubisoft session stored (valid for %d min)", (creds.expiresAt - System.currentTimeMillis()) / 60_000)
    }

    fun load(context: Context): UbisoftCredentials? {
        cached?.let { return it }
        val f = credentialsFile(context)
        if (!f.exists()) return null
        return runCatching {
            UbisoftCredentials.fromJson(JSONObject(String(Crypto.decrypt(f.readBytes())))).also { cached = it }
        }.onFailure { Timber.w(it, "Ubisoft credentials unreadable, discarding"); f.delete() }.getOrNull()
    }

    /** Turns the web sign-in into a launcher session and stores it. */
    suspend fun signIn(context: Context, web: UbisoftWebLogin): Result<UbisoftCredentials> = withContext(Dispatchers.IO) {
        val seed = UbisoftCredentials(
            ticket = web.ticket, sessionId = web.sessionId, expiresAt = 0L, userId = web.userId,
            profileId = web.userId, nameOnPlatform = web.nameOnPlatform, rememberMeTicket = web.rememberMeTicket,
        )
        when (val result = createSession(context, ticketAuthorization(context, web.ticket), seed)) {
            is SessionResult.Ok -> {
                val withProfile = withProfile(context, result.credentials)
                store(context, withProfile)
                Result.success(withProfile)
            }
            is SessionResult.Rejected -> Result.failure(IllegalStateException("Ubisoft refused the sign-in (HTTP ${result.code})"))
            is SessionResult.Failed -> Result.failure(result.error)
        }
    }

    /** Makes sure the stored ticket has enough life left for a launch, refreshing it when it does not. */
    suspend fun ensureFresh(context: Context): Freshness = withContext(Dispatchers.IO) {
        val creds = load(context) ?: return@withContext Freshness.NeedsSignIn
        val now = System.currentTimeMillis()
        val steps = UbisoftSessionPolicy.steps(now, creds)
        if (steps.isEmpty() && UbisoftSessionPolicy.isFresh(now, creds)) return@withContext Freshness.Ready(creds)
        var extendRejected = false
        var renewRejected = false
        var lastError: Throwable? = null
        for (step in steps) {
            val result = when (step) {
                UbisoftSessionPolicy.Step.EXTEND -> extendSession(context, creds)
                UbisoftSessionPolicy.Step.RENEW -> createSession(context, rememberAuthorization(context, creds.rememberMeTicket), creds)
            }
            when (result) {
                is SessionResult.Ok -> {
                    store(context, result.credentials)
                    Timber.i("Ubisoft session refreshed by %s", step)
                    return@withContext Freshness.Ready(result.credentials)
                }
                is SessionResult.Rejected -> {
                    Timber.w("Ubisoft session %s rejected (HTTP %d)", step, result.code)
                    if (step == UbisoftSessionPolicy.Step.EXTEND) extendRejected = true else renewRejected = true
                }
                is SessionResult.Failed -> {
                    Timber.w(result.error, "Ubisoft session %s failed", step)
                    lastError = result.error
                }
            }
        }
        when (UbisoftSessionPolicy.afterFailedRefresh(System.currentTimeMillis(), creds, extendRejected, renewRejected)) {
            UbisoftSessionPolicy.Verdict.USE_EXISTING -> Freshness.Ready(creds)
            UbisoftSessionPolicy.Verdict.SIGN_IN_AGAIN -> {
                Timber.w("Ubisoft session can no longer be renewed, signing in again")
                logout(context)
                Freshness.NeedsSignIn
            }
            UbisoftSessionPolicy.Verdict.UNAVAILABLE ->
                Freshness.Unavailable(lastError?.message ?: "the Ubisoft session could not be renewed")
        }
    }

    private fun config(context: Context, name: String) = UbisoftHelperConfig.value(context, name)

    private fun ticketAuthorization(context: Context, ticket: String) =
        UbisoftHelperConfig.fill(config(context, UbisoftHelperConfig.AUTH_TICKET), mapOf("ticket" to ticket))

    private fun rememberAuthorization(context: Context, ticket: String) =
        UbisoftHelperConfig.fill(config(context, UbisoftHelperConfig.AUTH_REMEMBER), mapOf("ticket" to ticket))

    private fun baseRequest(context: Context, url: String, authorization: String): Request.Builder {
        val values = mapOf("appId" to config(context, UbisoftHelperConfig.APP_ID))
        val agent = UbisoftHelperConfig.optional(context, UbisoftHelperConfig.HTTP_USER_AGENT)
            ?: runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
        return Request.Builder().url(url).apply {
            UbisoftHelperConfig.headers(UbisoftHelperConfig.optional(context, UbisoftHelperConfig.HTTP_HEADERS), values)
                .forEach { (name, value) -> header(name, value) }
            header("Authorization", authorization)
            if (!agent.isNullOrEmpty()) header("User-Agent", agent)
        }
    }

    private fun createSession(context: Context, authorization: String, previous: UbisoftCredentials): SessionResult {
        val url = config(context, UbisoftHelperConfig.SESSIONS_URL)
        val body = config(context, UbisoftHelperConfig.SESSION_BODY)
        val challengeHeader = UbisoftHelperConfig.optional(context, UbisoftHelperConfig.CHALLENGE_HEADER)
        fun build(challenge: String?) = baseRequest(context, url, authorization)
            .apply { if (challenge != null && challengeHeader != null) header(challengeHeader, challenge) }
            .post(body.toRequestBody(JSON))
            .build()
        return runCatching {
            var answer = call(build(null))
            val challenge = challengeHeader?.let { answer.headers[it] }
            if (challenge != null && answer.code !in 200..299) answer = call(build(challenge))
            toResult(answer, previous)
        }.getOrElse { SessionResult.Failed(it) }
    }

    private fun extendSession(context: Context, creds: UbisoftCredentials): SessionResult {
        val url = config(context, UbisoftHelperConfig.SESSIONS_URL)
        val request = baseRequest(context, url, ticketAuthorization(context, creds.ticket))
            // The service answers 415 to a PUT that names no content type, even with an empty body.
            .put("".toRequestBody(JSON))
            .build()
        return runCatching { toResult(call(request), creds) }.getOrElse { SessionResult.Failed(it) }
    }

    /** Email and country for the stub's account block. Optional: a failure keeps what is known. */
    private fun withProfile(context: Context, creds: UbisoftCredentials): UbisoftCredentials {
        val url = UbisoftHelperConfig.optional(context, UbisoftHelperConfig.PROFILE_URL) ?: return creds
        val sessionHeader = UbisoftHelperConfig.optional(context, UbisoftHelperConfig.SESSION_HEADER)
        val request = baseRequest(context, url, ticketAuthorization(context, creds.ticket))
            .apply { if (sessionHeader != null) header(sessionHeader, creds.sessionId) }
            .get().build()
        return runCatching {
            val answer = call(request)
            if (answer.code !in 200..299) throw IOException("profile HTTP ${answer.code}")
            val json = JSONObject(answer.body)
            creds.copy(
                email = json.optString("email").ifEmpty { creds.email },
                country = json.optString("country").ifEmpty { json.optString("countryCode") }.ifEmpty { creds.country },
            )
        }.onFailure { Timber.w("Ubisoft profile not read: %s", it.message) }.getOrDefault(creds)
    }

    private class Answer(val code: Int, val body: String, val headers: Map<String, String>)

    private fun call(request: Request): Answer = http.newCall(request).execute().use { resp ->
        val headers = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
        for (name in resp.headers.names()) headers[name] = resp.header(name).orEmpty()
        Answer(resp.code, resp.body?.string().orEmpty(), headers)
            .also { Timber.i("Ubisoft account %s -> %d", request.method, it.code) }
    }

    private fun toResult(answer: Answer, previous: UbisoftCredentials): SessionResult = when {
        answer.code in 200..299 -> runCatching {
            SessionResult.Ok(UbisoftCredentials.fromSessionResponse(JSONObject(answer.body), System.currentTimeMillis(), previous))
        }.getOrElse { SessionResult.Failed(it) }
        isRejection(answer.code) -> SessionResult.Rejected(answer.code)
        else -> SessionResult.Failed(IOException("HTTP ${answer.code}"))
    }

    /** Client errors are the server's answer about the credential; timeouts and rate limits are not. */
    internal fun isRejection(code: Int) = code in 400..499 && code != 408 && code != 429

    private val JSON = "application/json".toMediaType()
}
