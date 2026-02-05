package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicCredentials
import app.gamenative.data.EpicGameToken
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Manages Epic Games authentication and account operations.
 */
object EpicAuthManager {

    private fun getCredentialsFilePath(context: Context): String {
        val dir = File(context.filesDir, "epic")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "credentials.json").absolutePath
    }


    fun hasStoredCredentials(context: Context): Boolean {
        val credentialsFile = File(getCredentialsFilePath(context))
        return credentialsFile.exists()
    }

        /**
         * Clear stored credentials (logout)
         */
        fun clearStoredCredentials(context: Context): Boolean {
            return try {
                val authFile = File(getCredentialsFilePath(context))
                if (authFile.exists()) {
                    authFile.delete()
                } else {
                    true
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear Epic credentials")
                false
            }
        }

    /**
     * Extract authorization code from various input formats:
     * - Full URL: https://www.epicgames.com/id/api/redirect?code=abc123
     * - Just code: abc123
     */
    private fun extractCodeFromInput(input: String): String {
        val trimmed = input.trim()
        // Check if it's a URL with code parameter
        if (trimmed.startsWith("http")) {
            val codeMatch = Regex("[?&]code=([^&]+)").find(trimmed)
            return codeMatch?.groupValues?.get(1) ?: ""
        }
        // Otherwise assume it's already the code
        return trimmed
    }

    /**
     * Authenticate with Epic Games using authorization code from OAuth2 flow
     * Users must visit Epic login page, authenticate, and copy the authorization code
     *
     * @param context Android context
     * @param authorizationCode OAuth authorization code from Epic redirect
     * @return Result containing EpicCredentials on success, exception on failure
     */
    suspend fun authenticateWithCode(context: Context, authorizationCode: String): Result<EpicCredentials> {
        return try {
            Timber.i("Starting Epic authentication with authorization code...")

            // Extract the actual authorization code from URL if needed
            val actualCode = extractCodeFromInput(authorizationCode)
            if (actualCode.isEmpty()) {
                return Result.failure(Exception("Invalid authorization URL: no code parameter found"))
            }

            // Use native API client for authentication
            Timber.d("Authenticating via EpicAuthClient...")

            val authResult = EpicAuthClient.authenticateWithCode(actualCode)

            if (authResult.isFailure) {
                val error = authResult.exceptionOrNull()
                Timber.e(error, "Epic authentication failed: ${error?.message}")
                return Result.failure(error ?: Exception("Authentication failed"))
            }

            val authResponse = authResult.getOrNull()!!

            // Save credentials to file
            val credentials = EpicCredentials(
                accessToken = authResponse.accessToken,
                refreshToken = authResponse.refreshToken,
                accountId = authResponse.accountId,
                displayName = authResponse.displayName,
                expiresAt = authResponse.expiresAt
            )

            saveCredentials(context, credentials)

            Timber.i("Epic authentication successful: ${credentials.displayName}")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Epic authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    suspend fun getStoredCredentials(context: Context): Result<EpicCredentials> {
        return try {
            if (!hasStoredCredentials(context)) {
                return Result.failure(Exception("No stored credentials found"))
            }

            val credentials = loadCredentials(context)
            if (credentials == null) {
                return Result.failure(Exception("Failed to load credentials"))
            }

            // Check if token is expired (with 5 minute buffer)
            val now = System.currentTimeMillis()
            val expiresAt = credentials.expiresAt
            val bufferMs = 5 * 60 * 1000 // 5 minutes

            if (now + bufferMs >= expiresAt) {
                Timber.d("Access token expired, refreshing...")

                val refreshResult = EpicAuthClient.refreshAccessToken(credentials.refreshToken)

                if (refreshResult.isFailure) {
                    Timber.e("Failed to refresh token")
                    return Result.failure(Exception("Failed to refresh expired token: ${refreshResult.exceptionOrNull()?.message}"))
                }

                val authResponse = refreshResult.getOrNull()!!
                val refreshedCredentials = EpicCredentials(
                    accessToken = authResponse.accessToken,
                    refreshToken = authResponse.refreshToken,
                    accountId = authResponse.accountId,
                    displayName = authResponse.displayName,
                    expiresAt = authResponse.expiresAt
                )

                saveCredentials(context, refreshedCredentials)
                Timber.i("Access token refreshed successfully")

                return Result.success(refreshedCredentials)
            }

            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "Error getting Epic credentials: ${e.message}")
            Result.failure(Exception("Error getting credentials: ${e.message}", e))
        }
    }

    /**
     * Get game launch token for authenticating with Epic Games Services
     * This should be called immediately before launching a game that requires online authentication
     *
     */
    suspend fun getGameLaunchToken(
        context: Context,
        namespace: String? = null,
        catalogItemId: String? = null,
        requiresOwnershipToken: Boolean = false
    ): Result<EpicGameToken> {
        return try {
            // Get current valid credentials (will refresh if expired)
            val credentialsResult = getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                return Result.failure(credentialsResult.exceptionOrNull() ?: Exception("Not authenticated"))
            }

            val credentials = credentialsResult.getOrNull()!!

            // Get game exchange token (required for all games)
            Timber.d("Getting game exchange token for launch...")
            val exchangeTokenResult = EpicAuthClient.getGameExchangeToken(credentials.accessToken)
            if (exchangeTokenResult.isFailure) {
                return Result.failure(exchangeTokenResult.exceptionOrNull() ?: Exception("Failed to get exchange token"))
            }
            val exchangeCode = exchangeTokenResult.getOrNull()!!

            // Get ownership token if required (for DRM-protected games)
            var ownershipTokenHex: String? = null
            if (requiresOwnershipToken) {
                if (namespace.isNullOrEmpty() || catalogItemId.isNullOrEmpty()) {
                    return Result.failure(Exception("Namespace and catalogItemId required for ownership token"))
                }

                Timber.d("Getting ownership token for $namespace:$catalogItemId...")
                val ownershipResult = EpicAuthClient.getOwnershipToken(
                    accessToken = credentials.accessToken,
                    accountId = credentials.accountId,
                    namespace = namespace,
                    catalogItemId = catalogItemId
                )

                if (ownershipResult.isFailure) {
                    val error = ownershipResult.exceptionOrNull()?.message ?: "Unknown error"
                    Timber.e("Failed to get required ownership token: $error")
                    return Result.failure(
                        Exception("Failed to get ownership token for DRM-protected game: $error")
                    )
                } else {
                    // Convert binary token to hex string for easier handling
                    // Use toInt() and 0xFF to prevent sign extension of negative bytes
                    val tokenBytes = ownershipResult.getOrNull()!!
                    ownershipTokenHex = tokenBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    Timber.d("Ownership token obtained (${tokenBytes.size} bytes)")
                }
            }

            val gameToken = EpicGameToken(
                authCode = exchangeCode,
                accountId = credentials.accountId,
                ownershipToken = ownershipTokenHex
            )

            Timber.i("Successfully obtained game launch token")
            Result.success(gameToken)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get game launch token")
            Result.failure(e)
        }
    }

    suspend fun logout(context: Context): Result<Unit> {
        return try {
            val credentialsFile = File(getCredentialsFilePath(context))
            if (credentialsFile.exists()) {
                credentialsFile.delete()
                Timber.i("Epic credentials cleared")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear Epic credentials")
            Result.failure(e)
        }
    }

    private fun saveCredentials(context: Context, credentials: EpicCredentials) {
        val json = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
            put("account_id", credentials.accountId)
            put("display_name", credentials.displayName)
            put("expires_at", credentials.expiresAt)
        }

        val file = File(getCredentialsFilePath(context))
        file.writeText(json.toString())

        Timber.d("Credentials saved to ${file.absolutePath}")
    }

    private fun loadCredentials(context: Context): EpicCredentials? {
        return try {
            val file = File(getCredentialsFilePath(context))
            if (!file.exists()) {
                return null
            }

            val json = JSONObject(file.readText())

            EpicCredentials(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                accountId = json.getString("account_id"),
                displayName = json.getString("display_name"),
                expiresAt = json.getLong("expires_at")
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to load credentials")
            null
        }
    }
}
