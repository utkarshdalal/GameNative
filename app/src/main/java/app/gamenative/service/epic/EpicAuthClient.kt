package app.gamenative.service.epic

import app.gamenative.service.epic.EpicConstants
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

// * Example Request:  https://www.epicgames.com/id/login?redirectUrl=https://www.epicgames.com/id/api/redirect%3FclientId%3D34a02cf8f4414e29b15921876da36f9a%26responseType%3Dcode

data class EpicAuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
    val displayName: String,
    val expiresAt: Long,
    val expiresIn: Int,
)

/**
 * Native Epic OAuth authentication client
 *
 * Handles authentication, token refresh, and token verification
 * without requiring Python/legendary
 */

object EpicAuthClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Authenticate with Epic using authorization code
     *
     * POST https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token
     * Body: grant_type=authorization_code&code=<code>&token_type=eg1
     * Auth: Basic (CLIENT_ID:CLIENT_SECRET)
     */
    suspend fun authenticateWithCode(authorizationCode: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://${EpicConstants.OAUTH_HOST}/account/api/oauth/token"

            val formBody = "grant_type=authorization_code&code=$authorizationCode&token_type=eg1"
            val requestBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

            val credentials = okhttp3.Credentials.basic(EpicConstants.EPIC_CLIENT_ID, EpicConstants.EPIC_CLIENT_SECRET)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("User-Agent", EpicConstants.USER_AGENT)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.e("Authentication failed: ${response.code} - $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val json = JSONObject(body)

            if (json.has("errorCode")) {
                val errorCode = json.getString("errorCode")
                val errorMessage = json.optString("errorMessage", "Authentication failed")
                Timber.e("Epic auth error: $errorCode - $errorMessage")
                return@withContext Result.failure(Exception("$errorCode: $errorMessage"))
            }

            val authResponse = EpicAuthResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accountId = json.getString("account_id"),
                displayName = json.optString("displayName", ""),
                expiresAt = parseExpiresAt(json),
                expiresIn = json.getInt("expires_in"),
            )

            Timber.i("Successfully authenticated with Epic: ${authResponse.displayName} (${authResponse.accountId})")
            Result.success(authResponse)
        } catch (e: Exception) {
            Timber.e(e, "Failed to authenticate with Epic")
            Result.failure(e)
        }
    }

    /**
     * Refresh access token using refresh token
     *
     * POST https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/token
     * Body: grant_type=refresh_token&refresh_token=<token>&token_type=eg1
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://${EpicConstants.OAUTH_HOST}/account/api/oauth/token"

            val formBody = "grant_type=refresh_token&refresh_token=$refreshToken&token_type=eg1"
            val requestBody = formBody.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())

            val credentials = okhttp3.Credentials.basic(EpicConstants.EPIC_CLIENT_ID, EpicConstants.EPIC_CLIENT_SECRET)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", credentials)
                .header("User-Agent", EpicConstants.USER_AGENT)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                Timber.e("Token refresh failed: ${response.code} - $body")
                return@withContext Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val json = JSONObject(body)

            if (json.has("errorCode")) {
                val errorCode = json.getString("errorCode")
                val errorMessage = json.optString("errorMessage", "Token refresh failed")
                Timber.e("Epic token refresh error: $errorCode - $errorMessage")
                return@withContext Result.failure(Exception("$errorCode: $errorMessage"))
            }

            val authResponse = EpicAuthResponse(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accountId = json.getString("account_id"),
                displayName = json.optString("displayName", ""),
                expiresAt = parseExpiresAt(json),
                expiresIn = json.getInt("expires_in"),
            )

            Timber.i("Successfully refreshed Epic token")
            Result.success(authResponse)
        } catch (e: Exception) {
            Timber.e(e, "Failed to refresh Epic token")
            Result.failure(e)
        }
    }

    private fun parseExpiresAt(json: JSONObject): Long {
        return try {
            // Try to get as long first (epoch milliseconds)
            json.getLong("expires_at")
        } catch (e: Exception) {
            try {
                // If that fails, try parsing as ISO 8601 string
                val expiresAtString = json.getString("expires_at")
                val instant = Instant.parse(expiresAtString)
                instant.toEpochMilli()
            } catch (e2: Exception) {
                // Fallback: calculate from expires_in if available
                val expiresIn = json.optInt("expires_in", 7200) // default 2 hours
                System.currentTimeMillis() + (expiresIn * 1000L)
            }
        }
    }
}
