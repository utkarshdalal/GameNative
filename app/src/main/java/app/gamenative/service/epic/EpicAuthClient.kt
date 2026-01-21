package app.gamenative.service.epic

import app.gamenative.service.epic.EpicConstants
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import app.gamenative.utils.Net


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
 * Handles authentication, token refresh, and token verification
 */

object EpicAuthClient {
    private val httpClient = Net.http

    /**
     * Authenticate with Epic using authorization code
     */
    suspend fun authenticateWithCode(authorizationCode: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://${EpicConstants.OAUTH_HOST}/account/api/oauth/token"

            val requestBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", authorizationCode)
                .add("token_type", "eg1")
                .build()

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

            Timber.i("Successfully authenticated with Epic")
            Result.success(authResponse)
        } catch (e: Exception) {
            Timber.e(e, "Failed to authenticate with Epic")
            Result.failure(e)
        }
    }

    /**
     * Refresh access token using refresh token
     */
    suspend fun refreshAccessToken(refreshToken: String): Result<EpicAuthResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "https://${EpicConstants.OAUTH_HOST}/account/api/oauth/token"

            val requestBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refreshToken)
                .add("token_type", "eg1")
                .build()

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
