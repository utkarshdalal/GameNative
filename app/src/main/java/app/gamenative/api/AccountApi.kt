package app.gamenative.api

import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import app.gamenative.PrefManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException

object AccountApi {

    data class Account(
        val id: String,
        val email: String,
        val tier: String,
        val tierSource: String?,
        val discordLinked: Boolean,
        val trialAvailable: Boolean,
    )

    data class DeviceStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresIn: Int,
        val interval: Int,
    )

    sealed class PollResult {
        data object Pending : PollResult()
        data class SlowDown(val retryAfterSeconds: Long?) : PollResult()
        data object Expired : PollResult()
        data class SignedIn(val account: Account?) : PollResult()
        data class Failure(val code: Int?, val message: String) : PollResult()
    }

    private data class Tokens(val accessToken: String, val refreshToken: String)

    private data class RawResponse(val code: Int, val body: String, val retryAfterSeconds: Long? = null)

    private sealed class TokenState {
        data object NotLoaded : TokenState()
        data object SignedOut : TokenState()
        data class Present(val tokens: Tokens) : TokenState()
    }

    private const val TAG = "AccountApi"
    private const val TOKEN_EXPIRY_MARGIN_MS = 30_000L

    val account = mutableStateOf<Account?>(null)

    private val refreshMutex = Mutex()

    private val tokenMutex = Mutex()

    private var tokenState: TokenState = TokenState.NotLoaded

    private var session = 0L

    private fun url(path: String) = "https://api.gamenative.app$path"

    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json".toMediaType())

    private fun execute(request: Request): RawResponse =
        GameNativeApi.httpClient.newCall(request).execute().use { rawResponse(it) }

    private fun rawResponse(response: Response): RawResponse =
        RawResponse(
            code = response.code,
            body = response.body?.string() ?: "",
            retryAfterSeconds = response.header("Retry-After")?.trim()?.toLongOrNull(),
        )

    private fun errorCode(body: String): String =
        try {
            JSONObject(body).optString("error").ifBlank { body }
        } catch (_: Exception) {
            body
        }

    private fun parseAccount(json: JSONObject): Account =
        Account(
            id = json.optString("id"),
            email = json.optString("email"),
            tier = json.optString("tier", "none").ifBlank { "none" },
            tierSource = if (json.isNull("tier_source")) null else json.optString("tier_source").ifBlank { null },
            discordLinked = json.optBoolean("discord_linked", false),
            trialAvailable = json.optBoolean("trial_available", false),
        )

    private fun logFailure(name: String, e: Exception) {
        if (e is JSONException) {
            Timber.tag(TAG).e("$name returned an unreadable body")
        } else {
            Timber.tag(TAG).e(e, "$name failed")
        }
    }

    private fun loadTokensLocked(): Tokens? {
        when (val state = tokenState) {
            is TokenState.Present -> return state.tokens
            TokenState.SignedOut -> return null
            TokenState.NotLoaded -> Unit
        }
        val tokens = try {
            Tokens(PrefManager.gameNativeAccessToken, PrefManager.gameNativeRefreshToken)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read stored tokens")
            return null
        }
        if (tokens.refreshToken.isEmpty()) {
            tokenState = TokenState.SignedOut
            return null
        }
        tokenState = TokenState.Present(tokens)
        return tokens
    }

    private suspend fun loadTokens(): Tokens? = tokenMutex.withLock { loadTokensLocked() }

    private suspend fun startSession(tokens: Tokens, signedInAccount: Account?) {
        tokenMutex.withLock {
            session++
            account.value = null
            tokenState = TokenState.Present(tokens)
            PrefManager.saveGameNativeTokens(tokens.accessToken, tokens.refreshToken)
            account.value = signedInAccount
        }
    }

    private suspend fun replaceTokens(expected: Tokens, tokens: Tokens): Boolean =
        tokenMutex.withLock {
            if (loadTokensLocked() != expected) return@withLock false
            tokenState = TokenState.Present(tokens)
            PrefManager.saveGameNativeTokens(tokens.accessToken, tokens.refreshToken)
            true
        }

    private suspend fun clearTokens(expected: Tokens? = null): Tokens? =
        tokenMutex.withLock {
            val previous = loadTokensLocked()
            if (expected != null && previous != expected) return@withLock null
            session++
            tokenState = TokenState.SignedOut
            account.value = null
            PrefManager.clearGameNativeTokens()
            previous
        }

    suspend fun loadSignedInState(): Boolean = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            val signedIn = loadTokensLocked() != null
            PrefManager.gameNativeSignedIn.value = signedIn
            signedIn
        }
    }

    suspend fun startDeviceSignIn(): ApiResult<DeviceStart> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/auth/device/start"))
                .post(jsonBody(JSONObject()))
                .build()
            val response = execute(request)
            if (response.code != 200) {
                Timber.tag(TAG).w("device/start HTTP ${response.code}: ${response.body}")
                return@withContext ApiResult.HttpError(response.code, errorCode(response.body))
            }
            val json = JSONObject(response.body)
            val start = DeviceStart(
                deviceCode = json.getString("device_code"),
                userCode = json.optString("user_code"),
                verificationUrl = json.getString("verification_url"),
                expiresIn = json.optInt("expires_in", 600),
                interval = json.optInt("interval", 3),
            )
            ApiResult.Success(start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("device/start", e)
            ApiResult.NetworkError(e)
        }
    }

    suspend fun pollDeviceSignIn(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url("/api/auth/device/poll"))
                .post(jsonBody(JSONObject().put("device_code", deviceCode)))
                .build()
            val response = execute(request)
            when (response.code) {
                200 -> {
                    val json = JSONObject(response.body)
                    val tokens = Tokens(json.getString("access_token"), json.getString("refresh_token"))
                    val signedInAccount = json.optJSONObject("account")?.let { parseAccount(it) }
                    startSession(tokens, signedInAccount)
                    PollResult.SignedIn(signedInAccount)
                }
                202 -> PollResult.Pending
                410 -> PollResult.Expired
                429 -> PollResult.SlowDown(response.retryAfterSeconds)
                else -> {
                    Timber.tag(TAG).w("device/poll HTTP ${response.code}: ${response.body}")
                    PollResult.Failure(response.code, errorCode(response.body))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure("device/poll", e)
            PollResult.Failure(null, if (e is JSONException) "invalid_response" else e.message ?: "Network error")
        }
    }

    private suspend fun refreshTokens(staleAccessToken: String): Boolean = refreshMutex.withLock {
        val current = loadTokens() ?: return@withLock false
        if (current.accessToken != staleAccessToken) return@withLock true
        val request = Request.Builder()
            .url(url("/api/auth/refresh"))
            .post(jsonBody(JSONObject().put("refresh_token", current.refreshToken)))
            .build()
        val response = execute(request)
        when (response.code) {
            200 -> {
                val refreshed = try {
                    val json = JSONObject(response.body)
                    Tokens(json.getString("access_token"), json.getString("refresh_token"))
                } catch (_: JSONException) {
                    throw IOException("Refresh returned an unreadable body")
                }
                replaceTokens(current, refreshed)
            }
            401 -> {
                Timber.tag(TAG).i("Refresh token rejected, signing out")
                clearTokens(expected = current)
                false
            }
            else -> throw IOException("Refresh failed with HTTP ${response.code}")
        }
    }

    private fun accessTokenExpiresAt(accessToken: String): Long? =
        try {
            val parts = accessToken.split('.')
            if (parts.size != 3) {
                null
            } else {
                val payload = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8,
                )
                val json = JSONObject(payload)
                if (json.has("exp")) json.getLong("exp") * 1000L else null
            }
        } catch (_: Exception) {
            null
        }

    internal suspend fun currentAccessTokenOrNull(): String? =
        try {
            withContext(Dispatchers.IO) {
                val tokens = loadTokens() ?: return@withContext null
                val expiresAt = accessTokenExpiresAt(tokens.accessToken)
                val expired = tokens.accessToken.isEmpty() ||
                    (expiresAt != null && expiresAt - TOKEN_EXPIRY_MARGIN_MS <= System.currentTimeMillis())
                if (!expired) return@withContext tokens.accessToken
                if (!refreshTokens(tokens.accessToken)) return@withContext null
                loadTokens()?.accessToken?.ifEmpty { null }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w("Access token unavailable: ${e.javaClass.simpleName}")
            null
        }

    private fun <T : Any> send(
        build: (Request.Builder) -> Request.Builder,
        accessToken: String,
        read: (Response) -> T?,
    ): T? {
        val request = build(Request.Builder()).header("Authorization", "Bearer $accessToken").build()
        return GameNativeApi.httpClient.newCall(request).execute().use(read)
    }

    internal suspend fun <T : Any> sendAuthorized(
        build: (Request.Builder) -> Request.Builder,
        read: (Response) -> T,
    ): T? {
        val tokens = loadTokens() ?: return null
        var unauthorized = false
        val first = send(build, tokens.accessToken) { response ->
            if (response.code == 401) {
                unauthorized = true
                null
            } else {
                read(response)
            }
        }
        if (!unauthorized) return first
        if (!refreshTokens(tokens.accessToken)) return null
        val refreshed = loadTokens() ?: return null
        return send(build, refreshed.accessToken, read)
    }

    private suspend fun authorized(build: (Request.Builder) -> Request.Builder): RawResponse? =
        sendAuthorized(build) { rawResponse(it) }

    private suspend fun <T> authorizedCall(
        name: String,
        build: (Request.Builder) -> Request.Builder,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        try {
            val response = authorized(build) ?: return@withContext ApiResult.HttpError(401, "not_signed_in")
            if (response.code !in 200..299) {
                Timber.tag(TAG).w("$name HTTP ${response.code}: ${response.body}")
                return@withContext ApiResult.HttpError(response.code, errorCode(response.body))
            }
            ApiResult.Success(parse(response.body))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(name, e)
            ApiResult.NetworkError(e)
        }
    }

    suspend fun fetchAccount(): ApiResult<Account> {
        val expectedSession = tokenMutex.withLock { session }
        val result = authorizedCall(
            name = "account",
            build = { it.url(url("/api/account")).get() },
            parse = { parseAccount(JSONObject(it)) },
        )
        if (result is ApiResult.Success) {
            tokenMutex.withLock {
                if (session == expectedSession && tokenState is TokenState.Present) {
                    account.value = result.data
                }
            }
        }
        return result
    }

    suspend fun createCheckout(tier: String): ApiResult<String> =
        authorizedCall(
            name = "billing/checkout",
            build = { it.url(url("/api/billing/checkout")).post(jsonBody(JSONObject().put("tier", tier))) },
            parse = { JSONObject(it).getString("url") },
        )

    suspend fun createPortal(): ApiResult<String> =
        authorizedCall(
            name = "billing/portal",
            build = { it.url(url("/api/billing/portal")).post(jsonBody(JSONObject())) },
            parse = { JSONObject(it).getString("url") },
        )

    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        val refreshToken = clearTokens()?.refreshToken
        if (refreshToken.isNullOrEmpty()) return@withContext
        try {
            val request = Request.Builder()
                .url(url("/api/auth/logout"))
                .post(jsonBody(JSONObject().put("refresh_token", refreshToken)))
                .build()
            execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "logout request failed")
        }
    }
}
