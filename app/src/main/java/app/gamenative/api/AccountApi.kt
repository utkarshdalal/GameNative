package app.gamenative.api

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
        data object SlowDown : PollResult()
        data object Expired : PollResult()
        data class SignedIn(val account: Account?) : PollResult()
        data class Failure(val code: Int?, val message: String) : PollResult()
    }

    private data class Tokens(val accessToken: String, val refreshToken: String)

    private data class RawResponse(val code: Int, val body: String)

    private const val TAG = "AccountApi"

    val account = mutableStateOf<Account?>(null)

    private val refreshMutex = Mutex()

    @Volatile
    private var cachedTokens: Tokens? = null

    private fun url(path: String) = "${GameNativeApi.BASE_URL}$path"

    private fun jsonBody(json: JSONObject) =
        json.toString().toRequestBody("application/json".toMediaType())

    private fun execute(request: Request): RawResponse {
        GameNativeApi.httpClient.newCall(request).execute().use { response ->
            return RawResponse(response.code, response.body?.string() ?: "")
        }
    }

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

    private fun loadTokens(): Tokens? {
        cachedTokens?.let { return it }
        val tokens = try {
            Tokens(PrefManager.gameNativeAccessToken, PrefManager.gameNativeRefreshToken)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read stored tokens")
            return null
        }
        if (tokens.refreshToken.isEmpty()) return null
        cachedTokens = tokens
        return tokens
    }

    private fun saveTokens(accessToken: String, refreshToken: String) {
        cachedTokens = Tokens(accessToken, refreshToken)
        PrefManager.gameNativeAccessToken = accessToken
        PrefManager.gameNativeRefreshToken = refreshToken
    }

    private fun clearTokens() {
        cachedTokens = null
        account.value = null
        PrefManager.gameNativeAccessToken = ""
        PrefManager.gameNativeRefreshToken = ""
    }

    suspend fun loadSignedInState(): Boolean = withContext(Dispatchers.IO) {
        val signedIn = loadTokens() != null
        PrefManager.gameNativeSignedIn.value = signedIn
        signedIn
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
            Timber.tag(TAG).e(e, "device/start failed")
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
                    val accessToken = json.getString("access_token")
                    val refreshToken = json.getString("refresh_token")
                    saveTokens(accessToken, refreshToken)
                    val signedInAccount = json.optJSONObject("account")?.let { parseAccount(it) }
                    account.value = signedInAccount
                    PollResult.SignedIn(signedInAccount)
                }
                202 -> PollResult.Pending
                410 -> PollResult.Expired
                429 -> PollResult.SlowDown
                else -> {
                    Timber.tag(TAG).w("device/poll HTTP ${response.code}: ${response.body}")
                    PollResult.Failure(response.code, errorCode(response.body))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "device/poll failed")
            PollResult.Failure(null, e.message ?: "Network error")
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
                val json = JSONObject(response.body)
                saveTokens(json.getString("access_token"), json.getString("refresh_token"))
                true
            }
            401 -> {
                Timber.tag(TAG).i("Refresh token rejected, signing out")
                clearTokens()
                false
            }
            else -> throw IOException("Refresh failed with HTTP ${response.code}")
        }
    }

    private suspend fun authorized(build: (Request.Builder) -> Request.Builder): RawResponse? {
        val tokens = loadTokens() ?: return null
        val first = execute(build(Request.Builder()).header("Authorization", "Bearer ${tokens.accessToken}").build())
        if (first.code != 401) return first
        if (!refreshTokens(tokens.accessToken)) return null
        val refreshed = loadTokens() ?: return null
        return execute(build(Request.Builder()).header("Authorization", "Bearer ${refreshed.accessToken}").build())
    }

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
            Timber.tag(TAG).e(e, "$name failed")
            ApiResult.NetworkError(e)
        }
    }

    suspend fun fetchAccount(): ApiResult<Account> {
        val result = authorizedCall(
            name = "account",
            build = { it.url(url("/api/account")).get() },
            parse = { parseAccount(JSONObject(it)) },
        )
        if (result is ApiResult.Success) {
            account.value = result.data
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

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val refreshToken = loadTokens()?.refreshToken
        clearTokens()
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
