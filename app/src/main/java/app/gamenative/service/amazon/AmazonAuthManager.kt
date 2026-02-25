package app.gamenative.service.amazon

import android.content.Context
import app.gamenative.data.AmazonCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/** Manages Amazon authentication and credential lifecycle. */
object AmazonAuthManager {

    /** In-flight PKCE state between start and code exchange. */
    @Volatile private var pendingCodeVerifier: String? = null
    @Volatile private var pendingDeviceSerial: String? = null
    @Volatile private var pendingClientId: String? = null

    // ── Paths ───────────────────────────────────────────────────────────────

    private fun getCredentialsFilePath(context: Context): String {
        val dir = File(context.filesDir, "amazon")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "credentials.json").absolutePath
    }

    // ── Public query ────────────────────────────────────────────────────────

    fun hasStoredCredentials(context: Context): Boolean {
        return File(getCredentialsFilePath(context)).exists()
    }

    // ── Auth flow (step 1): prepare PKCE & return the sign-in URL ───────────

    /** Prepare a new PKCE session and return the sign-in URL. */
    fun startAuthFlow(): String {
        val serial = AmazonPKCEGenerator.generateDeviceSerial()
        val clientId = AmazonPKCEGenerator.generateClientId(serial)
        val verifier = AmazonPKCEGenerator.generateCodeVerifier()
        val challenge = AmazonPKCEGenerator.generateCodeChallenge(verifier)

        pendingCodeVerifier = verifier
        pendingDeviceSerial = serial
        pendingClientId = clientId

        val authUrl = AmazonConstants.buildAuthUrl(clientId, challenge)
        
        Timber.d("[Amazon] Auth flow started (serial=${serial.take(8)}…)")
        Timber.d("[Amazon] Client ID: device:$clientId")
        Timber.d("[Amazon] Auth URL scope: device_auth_access")
        Timber.d("[Amazon] Full auth URL: $authUrl")

        return authUrl
    }

    // ── Auth flow (step 2): exchange auth-code for tokens ───────────────────

    /** Complete PKCE by exchanging an authorization code for tokens. */
    suspend fun authenticateWithCode(
        context: Context,
        authorizationCode: String,
    ): Result<AmazonCredentials> {
        val verifier = pendingCodeVerifier
        val serial = pendingDeviceSerial
        val clientId = pendingClientId

        if (verifier == null || serial == null || clientId == null) {
            return Result.failure(Exception("No pending auth flow – call startAuthFlow() first"))
        }

        return try {
            Timber.i("[Amazon] Exchanging auth code for tokens…")

            val result = AmazonAuthClient.registerDevice(
                authorizationCode = authorizationCode,
                codeVerifier = verifier,
                deviceSerial = serial,
                clientId = clientId,
            )

            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Timber.e(error, "[Amazon] Device registration failed: ${error?.message}")
                return Result.failure(error ?: Exception("Device registration failed"))
            }

            val authResponse = result.getOrNull()!!
            val expiresAt = System.currentTimeMillis() + (authResponse.expiresIn * 1000L)

            val credentials = AmazonCredentials(
                accessToken = authResponse.accessToken,
                refreshToken = authResponse.refreshToken,
                deviceSerial = serial,
                clientId = clientId,
                expiresAt = expiresAt,
            )

            saveCredentials(context, credentials)

            // Clear in-flight state
            clearPendingState()

            Timber.i("[Amazon] Authentication successful")
            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Authentication exception: ${e.message}")
            Result.failure(Exception("Authentication exception: ${e.message}", e))
        }
    }

    // ── Get / refresh credentials ───────────────────────────────────────────

    /** Return stored credentials, refreshing access token when needed. */
    suspend fun getStoredCredentials(context: Context): Result<AmazonCredentials> {
        return try {
            val credentials = loadCredentials(context)
                ?: return Result.failure(Exception("No stored credentials found"))

            // Check expiration (5-minute buffer)
            val bufferMs = 5 * 60 * 1000L
            if (System.currentTimeMillis() + bufferMs >= credentials.expiresAt) {
                Timber.d("[Amazon] Access token expired, refreshing…")

                val refreshResult = AmazonAuthClient.refreshAccessToken(
                    refreshToken = credentials.refreshToken,
                    clientId = credentials.clientId,
                )

                if (refreshResult.isFailure) {
                    Timber.e("[Amazon] Token refresh failed")
                    return Result.failure(
                        Exception("Failed to refresh token: ${refreshResult.exceptionOrNull()?.message}")
                    )
                }

                val auth = refreshResult.getOrNull()!!
                val refreshed = credentials.copy(
                    accessToken = auth.accessToken,
                    expiresAt = System.currentTimeMillis() + (auth.expiresIn * 1000L),
                )

                saveCredentials(context, refreshed)
                Timber.i("[Amazon] Token refreshed successfully")
                return Result.success(refreshed)
            }

            Result.success(credentials)
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Error getting credentials: ${e.message}")
            Result.failure(Exception("Error getting credentials: ${e.message}", e))
        }
    }

    // ── Logout ──────────────────────────────────────────────────────────────

    /** Logout by best-effort deregister + local credential cleanup. */
    suspend fun logout(context: Context): Result<Unit> {
        return try {
            val credentials = loadCredentials(context)

            // Best-effort remote deregister
            if (credentials != null) {
                AmazonAuthClient.deregisterDevice(
                    accessToken = credentials.accessToken,
                    deviceSerial = credentials.deviceSerial,
                    clientId = credentials.clientId,
                )
            }

            // Always clear locally
            clearStoredCredentials(context)
            clearPendingState()

            Timber.i("[Amazon] Logged out successfully")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Logout exception")
            // Still clear local creds even if deregister fails
            clearStoredCredentials(context)
            Result.success(Unit)
        }
    }

    fun clearStoredCredentials(context: Context): Boolean {
        return try {
            val file = File(getCredentialsFilePath(context))
            if (file.exists()) file.delete() else true
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Failed to clear credentials")
            false
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun clearPendingState() {
        pendingCodeVerifier = null
        pendingDeviceSerial = null
        pendingClientId = null
    }

    private fun saveCredentials(context: Context, credentials: AmazonCredentials) {
        val json = JSONObject().apply {
            put("access_token", credentials.accessToken)
            put("refresh_token", credentials.refreshToken)
            put("device_serial", credentials.deviceSerial)
            put("client_id", credentials.clientId)
            put("expires_at", credentials.expiresAt)
        }

        val file = File(getCredentialsFilePath(context))
        file.writeText(json.toString())
        Timber.d("[Amazon] Credentials saved to ${file.absolutePath}")
    }

    private fun loadCredentials(context: Context): AmazonCredentials? {
        return try {
            val file = File(getCredentialsFilePath(context))
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            AmazonCredentials(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                deviceSerial = json.getString("device_serial"),
                clientId = json.getString("client_id"),
                expiresAt = json.getLong("expires_at"),
            )
        } catch (e: Exception) {
            Timber.e(e, "[Amazon] Failed to load credentials")
            null
        }
    }
}
