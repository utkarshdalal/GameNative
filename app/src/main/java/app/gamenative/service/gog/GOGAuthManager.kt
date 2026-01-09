package app.gamenative.service.gog

import android.content.Context
import app.gamenative.data.GOGCredentials
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
 * ! Note: We currently don't use redirect flow due to Pluvia Issues
 * Uses GOGPythonBridge for all GOGDL command execution.
 */
object GOGAuthManager {


    fun getAuthConfigPath(context: Context): String {
        return "${context.filesDir}/gog_auth.json"
    }

    fun hasStoredCredentials(context: Context): Boolean {
        val authFile = File(getAuthConfigPath(context))
        return authFile.exists()
    }

    /**
     * Authenticate with GOG using authorization code from OAuth2 flow
     * Users must visit GOG login page, authenticate, and copy the authorization code
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<GOGCredentials> {
        return try {
            Timber.i("Starting GOG authentication with authorization code...")

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
                Timber.d("Created auth config directory: ${authDir.absolutePath}")
            }

            // Execute GOGDL auth command with the authorization code
            Timber.d("Authenticating with auth config path")

            val result = GOGPythonBridge.executeCommand(
                "--auth-config-path", authConfigPath,
                "auth", "--code", actualCode
            )

            Timber.d("GOGDL executeCommand result: isSuccess=${result.isSuccess}")

            if (result.isSuccess) {
                val gogdlOutput = result.getOrNull() ?: ""
                Timber.i("GOGDL command completed, checking authentication result...")

                // Parse and validate the authentication result
                return parseAuthenticationResult(authConfigPath, gogdlOutput)
            } else {
                val error = result.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown authentication error"
                Timber.e(error, "GOG authentication command failed: $errorMsg")
                Result.failure(Exception("Authentication failed: $errorMsg", error))
            }
        } catch (e: Exception) {
            Timber.e(e, "GOG authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    /**
     * Get user credentials by calling GOGDL auth command (without --code)
     * This will automatically handle token refresh if needed
     */
    suspend fun getStoredCredentials(context: Context): Result<GOGCredentials> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            // Use GOGDL to get credentials - this will handle token refresh automatically
            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "auth")

            if (result.isSuccess) {
                val output = result.getOrNull() ?: ""
                return parseCredentialsFromOutput(output)
            } else {
                Timber.e("GOGDL credentials command failed")
                Result.failure(Exception("Failed to get credentials from GOG"))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get stored credentials via GOGDL")
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
            val authContent = authFile.readText()
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
            Timber.d("Requesting game-specific token for clientId: $clientId")
            val tokenUrl = "https://auth.gog.com/token?client_id=$clientId&client_secret=$clientSecret&grant_type=refresh_token&refresh_token=$refreshToken"

            val request = okhttp3.Request.Builder()
                .url(tokenUrl)
                .get()
                .build()

            val tokenJson = okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Timber.e("Failed to get game token: HTTP ${response.code} - $errorBody")
                    return Result.failure(Exception("Failed to get game-specific token: HTTP ${response.code}"))
                }

                val responseBody = response.body?.string() ?: return Result.failure(Exception("Empty response"))
                val json = JSONObject(responseBody)

                // Store the new game-specific credentials
                json.put("loginTime", System.currentTimeMillis() / 1000.0)
                authJson.put(clientId, json)

                // Write updated auth file
                authFile.writeText(authJson.toString(2))

                Timber.i("Successfully obtained game-specific token for clientId: $clientId")
                json
            }

            return Result.success(GOGCredentials(
                accessToken = tokenJson.getString("access_token"),
                refreshToken = tokenJson.optString("refresh_token", refreshToken),
                userId = tokenJson.getString("user_id"),
                username = tokenJson.optString("username", "GOG User")
            ))
        } catch (e: Exception) {
            Timber.e(e, "Failed to get game-specific credentials")
            Result.failure(e)
        }
    }

    /**
     * Validate credentials by calling GOGDL auth command (without --code)
     * This will automatically refresh tokens if they're expired
     */
    suspend fun validateCredentials(context: Context): Result<Boolean> {
        return try {
            val authConfigPath = getAuthConfigPath(context)

            if (!hasStoredCredentials(context)) {
                Timber.d("No stored credentials found for validation")
                return Result.success(false)
            }

            Timber.d("Starting credentials validation with GOGDL")

            // Use GOGDL to validate credentials - this will handle token refresh automatically
            val result = GOGPythonBridge.executeCommand("--auth-config-path", authConfigPath, "auth")

            if (!result.isSuccess) {
                val error = result.exceptionOrNull()
                Timber.e("Credentials validation failed - command failed: ${error?.message}")
                return Result.success(false)
            }

            val output = result.getOrNull() ?: ""

            try {
                val credentialsJson = JSONObject(output.trim())

                // Check if there's an error
                if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                    val errorDesc = credentialsJson.optString("message", "Unknown error")
                    Timber.e("Credentials validation failed: $errorDesc")
                    return Result.success(false)
                }

                Timber.d("Credentials validation successful")
                return Result.success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse validation response")
                return Result.success(false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to validate credentials")
            return Result.failure(e)
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
            Timber.e(e, "Failed to clear GOG credentials")
            false
        }
    }

    private fun extractCodeFromInput(input: String): String {
        return if (input.startsWith("http")) {
            // Extract code parameter from URL
            val codeParam = input.substringAfter("code=", "")
            if (codeParam.isEmpty()) {
                ""
            } else {
                // Remove any additional parameters after the code
                val cleanCode = codeParam.substringBefore("&")
                Timber.d("Extracted authorization code")
                cleanCode
            }
        } else {
            input
        }
    }

    private fun parseAuthenticationResult(authConfigPath: String, gogdlOutput: String): Result<GOGCredentials> {
        try {
            Timber.d("Attempting to parse GOGDL output as JSON (length: ${gogdlOutput.length})")
            val outputJson = JSONObject(gogdlOutput.trim())
            Timber.d("Successfully parsed JSON, keys: ${outputJson.keys().asSequence().toList()}")

            // Check if the response indicates an error
            if (outputJson.has("error") && outputJson.getBoolean("error")) {
                val errorMsg = outputJson.optString("error_description", "Authentication failed")
                val errorDetails = outputJson.optString("message", "No details available")
                Timber.e("GOG authentication failed: $errorMsg - Details: $errorDetails")
                return Result.failure(Exception("GOG authentication failed: $errorMsg"))
            }

            // Check if we have the required fields for successful auth
            val accessToken = outputJson.optString("access_token", "")
            val userId = outputJson.optString("user_id", "")

            if (accessToken.isEmpty() || userId.isEmpty()) {
                Timber.e("GOG authentication incomplete: missing access_token or user_id in output")
                return Result.failure(Exception("Authentication incomplete: missing required data"))
            }

            // GOGDL output looks good, now check if auth file was created
            val authFile = File(authConfigPath)
            if (authFile.exists()) {
                // Parse authentication result from file
                val authData = parseFullCredentialsFromFile(authConfigPath)
                if (authData != null) {
                    Timber.i("GOG authentication successful for user")
                    return Result.success(authData)
                } else {
                    Timber.e("Failed to parse auth file despite file existing")
                    return Result.failure(Exception("Failed to parse authentication file"))
                }
            }

            Timber.w("GOGDL returned success but no auth file created, using output data")
            // Create credentials from GOGDL output
            val credentials = createCredentialsFromJson(outputJson)
            return Result.success(credentials)

        } catch (e: Exception) {
            Timber.e(e, "Failed to parse GOGDL output")
            // Fallback: check if auth file exists
            val authFile = File(authConfigPath)
            if (!authFile.exists()) {
                Timber.e("GOG authentication failed: no auth file created and failed to parse output")
                return Result.failure(Exception("Authentication failed: no credentials available"))
            }
            try {
                val authData = parseFullCredentialsFromFile(authConfigPath)
                if (authData != null) {
                    Timber.i("GOG authentication successful (fallback) for user")
                    return Result.success(authData)
                } else {
                    Timber.e("Failed to parse auth file (fallback path)")
                    return Result.failure(Exception("Failed to parse authentication file"))
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Failed to parse auth file")
                return Result.failure(Exception("Failed to parse authentication result: ${ex.message}"))
            }
        }
    }

    private fun parseCredentialsFromOutput(output: String): Result<GOGCredentials> {
        try {
            val credentialsJson = JSONObject(output.trim())

            // Check if there's an error
            if (credentialsJson.has("error") && credentialsJson.getBoolean("error")) {
                val errorMsg = credentialsJson.optString("message", "Authentication failed")
                Timber.e("GOGDL credentials failed: $errorMsg")
                return Result.failure(Exception("Authentication failed: $errorMsg"))
            }

            // Extract credentials from GOGDL response
            val accessToken = credentialsJson.optString("access_token", "")
            val refreshToken = credentialsJson.optString("refresh_token", "")
            val username = credentialsJson.optString("username", "GOG User")
            val userId = credentialsJson.optString("user_id", "")

            val credentials = GOGCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                username = username,
                userId = userId,
            )

            Timber.d("Got credentials for user")
            return Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse GOGDL credentials response")
            return Result.failure(e)
        }
    }

    private fun parseFullCredentialsFromFile(authConfigPath: String): GOGCredentials? {
        return try {
            val authFile = File(authConfigPath)
            if (!authFile.exists()) {
                Timber.e("Auth file does not exist: $authConfigPath")
                return null
            }

            val authContent = authFile.readText()
            val authJson = JSONObject(authContent)

            // GOGDL stores credentials nested under client ID
            val credentialsJson = if (authJson.has(GOGConstants.GOG_CLIENT_ID)) {
                authJson.getJSONObject(GOGConstants.GOG_CLIENT_ID)
            } else {
                // Fallback: try to read from root level
                authJson
            }

            val accessToken = credentialsJson.optString("access_token", "")
            val refreshToken = credentialsJson.optString("refresh_token", "")
            val userId = credentialsJson.optString("user_id", "")

            // Validate required fields
            if (accessToken.isEmpty() || userId.isEmpty()) {
                Timber.e("Auth file missing required fields (access_token or user_id)")
                return null
            }

            GOGCredentials(
                accessToken = accessToken,
                refreshToken = refreshToken,
                userId = userId,
                username = credentialsJson.optString("username", "GOG User"),
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse auth result from file: ${e.message}")
            null
        }
    }
    private fun createCredentialsFromJson(outputJson: JSONObject): GOGCredentials {
        return GOGCredentials(
            accessToken = outputJson.optString("access_token", ""),
            refreshToken = outputJson.optString("refresh_token", ""),
            userId = outputJson.optString("user_id", ""),
            username = "GOG User", // We don't have username in the token response
        )
    }
}
