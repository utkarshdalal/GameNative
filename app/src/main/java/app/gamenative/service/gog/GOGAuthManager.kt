package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGCredentials
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages GOG authentication and account operations.
 *
 * - OAuth2 authentication flow
 * - Credential storage and validation
 * - Token refresh
 * - Account logout
 */
object GOGAuthManager {

    private val httpClient = Net.http

    // Internal for testing - allows tests to override token URL
    @JvmField
    internal var tokenUrl: String = "https://auth.gog.com/token"

    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/gog_auth.json"
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
    }

    /**
     * Authenticate with GOG using authorization code
     * Users must visit GOG login page, authenticate, and copy the authorization code
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
        return try {
            Timber.tag("GOG").i("Starting GOG authentication with authorization code...")

            // Extract the actual authorization code from URL if needed
            val actualCode = extractCodeFromInput(authorizationCode)
            if (actualCode.isEmpty()) {
                return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
            }

            val authConfigPath = getAuthConfigPath(context)

            // Create auth config directory
            val authFile = File(authConfigPath)
            val authDir = authFile.parentFile
            if (authDir != null && !authDir.exists()) {
                authDir.mkdirs()
                Timber.tag("GOG").d("Created auth config directory: ${authDir.absolutePath}")
            }

            // Exchange authorization code for tokens
            Timber.tag("GOG").d("Exchanging authorization code for tokens...")

            val tokenUrlWithParams = tokenUrl.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", GOGConstants.GOG_CLIENT_ID)
                .addQueryParameter("client_secret", GOGConstants.GOG_CLIENT_SECRET)
                .addQueryParameter("grant_type", "authorization_code")
                .addQueryParameter("code", actualCode)
                .addQueryParameter("redirect_uri", GOGConstants.GOG_REDIRECT_URI)
                .build()

            val request = okhttp3.Request.Builder()
                .url(tokenUrlWithParams)
                .get()
                .build()

            Timber.tag("GOG").d("Sending authentication request...")
            val tokenJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }.use { response ->
                Timber.tag("GOG").d("Received response: HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to authenticate: HTTP ${response.code} - $errorBody")
                    return Result.failure(Exception("Authentication failed: HTTP ${response.code} - $errorBody"))
                }

                val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                Timber.tag("GOG").d("Response body received, length: ${responseBody.length}")
                JSONObject(responseBody)
            }

            // Check for error in response
            if (tokenJson.has("error")) {
                val errorMsg = tokenJson.optString("error_description", "Authentication failed")
                Timber.tag("GOG").e("GOG authentication failed: $errorMsg")
                return Result.failure(Exception("Authentication failed: $errorMsg"))
            }

            // Extract credentials from response
            val accessToken = tokenJson.optString("access_token", "")
            val refreshToken = tokenJson.optString("refresh_token", "")
            val userId = tokenJson.optString("user_id", "")
            val expiresIn = tokenJson.optInt("expires_in", 3600)

            if (accessToken.isEmpty() || userId.isEmpty()) {
                Timber.tag("GOG").e("GOG authentication incomplete: missing access_token or user_id")
                return Result.failure(Exception("Authentication incomplete: missing required data"))
            }

            // Create credentials object
            val credentials = GOGCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = userId,
                username = "GOG User"
            )

            // Store credentials to file
            val authData = JSONObject().apply {
                put(GOGConstants.GOG_CLIENT_ID, JSONObject().apply {
                    put("access_token", accessToken)
                    put("refresh_token", refreshToken)
                    put("user_id", userId)
                    put("expires_in", expiresIn)
                    put("loginTime", System.currentTimeMillis() / 1000.0)
                })
            }

            withContext(Dispatchers.IO) {
                authFile.writeText(authData.toString(2))
            }
            Timber.tag("GOG").i("GOG authentication successful for user: $userId")

            Result.success(credentials)
        } catch (e: Exception) {
            val errorMessage = e.message ?: e.javaClass.simpleName
            Timber.tag("GOG").e(e, "GOG authentication exception: $errorMessage")
            Timber.tag("GOG").e("Stack trace: ${e.stackTraceToString()}")
            Result.failure(Exception("Authentication exception: $errorMessage", e))
        }
    }

    /**
     * Get user credentials from storage, automatically refreshing if expired
     */
    suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Read credentials from file (IO dispatcher)
            val authFile = File(authConfigPath)
            val authContent = withContext(Dispatchers.IO) { authFile.readText() }
            val authJson = JSONObject(authContent)

            // Get Galaxy app credentials
            if (!authJson.has(GOGConstants.GOG_CLIENT_ID)) {
                return Result.failure(Exception("No Galaxy credentials found"))
            }

            val credentialsJson = authJson.getJSONObject(GOGConstants.GOG_CLIENT_ID)

            // Check if expired
            val loginTime = credentialsJson.optDouble("loginTime", 0.0)
            val expiresIn = credentialsJson.optInt("expires_in", 0)
            val isExpired = System.currentTimeMillis() / 1000.0 >= loginTime + expiresIn

            if (isExpired) {
                Timber.tag("GOG").d("Credentials expired, refreshing...")
                // Refresh the token
                val refreshResult = refreshCredentials(context, GOGConstants.GOG_CLIENT_ID, GOGConstants.GOG_CLIENT_SECRET)
                if (refreshResult.isFailure) {
                    return Result.failure(refreshResult.exceptionOrNull() ?: Exception("Failed to refresh credentials"))
                }
                // Re-read the refreshed credentials
                return getStoredCredentials(context)
            }

            // Return valid credentials
            val credentials = GOGCredentials(
                accessToken = credentialsJson.getString("access_token"),
                refreshToken = credentialsJson.optString("refresh_token", ""),
                userId = credentialsJson.getString("user_id"),
                username = credentialsJson.optString("username", "GOG User")
            )

            Timber.tag("GOG").d("Retrieved stored credentials for user: ${credentials.userId}")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get stored credentials")
            Result.failure(e)
        }
    }

    /**
     * Get game-specific credentials using the game's clientId and clientSecret.
     * This exchanges the Galaxy app's refresh token for a game-specific access token.
     *
     * @param context Application context
     * @param clientId Game's client ID (from .info file)
     * @param clientSecret Game's client secret (from build metadata)
     * @return Game-specific credentials or error
     */
    suspend fun getGameCredentials(
        context: Context,
        clientId: String,
        clientSecret: String
    ): Result<GOGCredentials> {
        return try {
            val authFile = File(getAuthConfigPath(context))
            if (!authFile.exists()) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Read auth file
            val authContent = withContext(Dispatchers.IO) { authFile.readText() }
            val authJson = JSONObject(authContent)

            // Check if we already have credentials for this game
            if (authJson.has(clientId)) {
                val gameCredentials = authJson.getJSONObject(clientId)

                // Check if expired
                val loginTime = gameCredentials.optDouble("loginTime", 0.0)
                val expiresIn = gameCredentials.optInt("expires_in", 0)
                val isExpired = System.currentTimeMillis() / 1000.0 >= loginTime + expiresIn

                if (!isExpired) {
                    // Return existing valid credentials
                    return Result.success(GOGCredentials(
                        accessToken = gameCredentials.getString("access_token"),
                        refreshToken = gameCredentials.optString("refresh_token", ""),
                        userId = gameCredentials.getString("user_id"),
                        username = gameCredentials.optString("username", "GOG User")
                    ))
                }
            }

            // Need to get/refresh game-specific token
            // Get Galaxy app's refresh token
            val galaxyCredentials = if (authJson.has(GOGConstants.GOG_CLIENT_ID)) {
                authJson.getJSONObject(GOGConstants.GOG_CLIENT_ID)
            } else {
                return Result.failure(Exception("No Galaxy credentials found"))
            }

            val refreshToken = galaxyCredentials.optString("refresh_token", "")
            if (refreshToken.isEmpty()) {
                return Result.failure(Exception("No refresh token available"))
            }

            // Request game-specific token using Galaxy's refresh token
            Timber.tag("GOG").d("Requesting game-specific token for clientId: $clientId")
            val url = tokenUrl.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("client_secret", clientSecret)
                .addQueryParameter("grant_type", "refresh_token")
                .addQueryParameter("refresh_token", refreshToken)
                .build()

            val request = okhttp3.Request.Builder()
                .url(url)
                .get()
                .build()

            val tokenJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }.use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to get game token: HTTP ${response.code} - $errorBody")
                    return Result.failure(Exception("Failed to get game-specific token: HTTP ${response.code}"))
                }

                val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                val json = JSONObject(responseBody)

                // Store the new game-specific credentials
                json.put("loginTime", System.currentTimeMillis() / 1000.0)
                authJson.put(clientId, json)

                // Write updated auth file
                withContext(Dispatchers.IO) { authFile.writeText(authJson.toString(2)) }

                Timber.tag("GOG").i("Successfully obtained game-specific token for clientId: $clientId")
                json
            }

            return Result.success(GOGCredentials(
                accessToken = tokenJson.getString("access_token"),
                refreshToken = tokenJson.optString("refresh_token", refreshToken),
                userId = tokenJson.getString("user_id"),
                username = tokenJson.optString("username", "GOG User")
            ))
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to get game-specific credentials")
            Result.failure(e)
        }
    }

    /**
     * Validate credentials, automatically refreshing if expired
     */
    suspend fun validateCredentials(context: Context): Result<Boolean> {
        return try {
            if (!hasStoredCredentials(context)) {
                Timber.tag("GOG").d("No stored credentials found for validation")
                return Result.success(false)
            }

            Timber.tag("GOG").d("Starting credentials validation")

            // Try to get credentials - this will automatically refresh if needed
            val credentialsResult = getStoredCredentials(context)

            if (credentialsResult.isSuccess) {
                Timber.tag("GOG").d("Credentials validation successful")
                Result.success(true)
            } else {
                Timber.tag("GOG").e("Credentials validation failed: ${credentialsResult.exceptionOrNull()?.message}")
                Result.success(false)
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to validate credentials")
            Result.failure(e)
        }
    }

    /**
     * Clear stored credentials (logout)
     */
    fun clearStoredCredentials(context: Context): Boolean {
        return try {
            val authFile = File(getAuthConfigPath(context))
            if (authFile.exists()) {
                authFile.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to clear GOG credentials")
            false
        }
    }

    /**
     * Refresh credentials using refresh token
     */
    private suspend fun refreshCredentials(context: Context, clientId: String, clientSecret: String): Result<Boolean> {
        return try {
            val authFile = File(getAuthConfigPath(context))
            if (!authFile.exists()) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Read current credentials
            val authContent = withContext(Dispatchers.IO) { authFile.readText() }
            val authJson = JSONObject(authContent)

            // Get refresh token from Galaxy credentials
            val galaxyCredentials = if (authJson.has(GOGConstants.GOG_CLIENT_ID)) {
                authJson.getJSONObject(GOGConstants.GOG_CLIENT_ID)
            } else {
                return Result.failure(Exception("No Galaxy credentials found"))
            }

            val refreshToken = galaxyCredentials.optString("refresh_token", "")
            if (refreshToken.isEmpty()) {
                return Result.failure(Exception("No refresh token available"))
            }

            // Request new tokens
            Timber.tag("GOG").d("Refreshing credentials for clientId: $clientId")
            val tokenUrlWithParams = tokenUrl.toHttpUrl().newBuilder()
                .addQueryParameter("client_id", clientId)
                .addQueryParameter("client_secret", clientSecret)
                .addQueryParameter("grant_type", "refresh_token")
                .addQueryParameter("refresh_token", refreshToken)
                .build()

            val request = okhttp3.Request.Builder()
                .url(tokenUrlWithParams)
                .get()
                .build()

            val tokenJson = withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute()
            }.use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.tag("GOG").e("Failed to refresh credentials: HTTP ${response.code} - $errorBody")
                    return Result.failure(Exception("Failed to refresh credentials: HTTP ${response.code}"))
                }

                val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                JSONObject(responseBody)
            }

            // Update credentials in auth file
            tokenJson.put("loginTime", System.currentTimeMillis() / 1000.0)
            authJson.put(clientId, tokenJson)
            withContext(Dispatchers.IO) { authFile.writeText(authJson.toString(2)) }

            Timber.tag("GOG").i("Successfully refreshed credentials for clientId: $clientId")
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "Failed to refresh credentials")
            Result.failure(e)
        }
    }

    /**
     * Extract authorization code from either a full URL or plain code string.
     *
     * @param input Either a full GOG redirect URL or a plain authorization code
     * @return The extracted authorization code, or empty string if not found
     */
    fun extractCodeFromInput(input: String): String {
        return if (input.startsWith("http")) {
            // Extract code parameter from URL
            val codeParam = input.substringAfter("code=", "")
            if (codeParam.isEmpty()) {
                ""
            } else {
                // Remove any additional parameters after the code
                val cleanCode = codeParam.substringBefore("&")
                Timber.tag("GOG").d("Extracted authorization code")
                cleanCode
            }
        } else {
            input
        }
    }
}
