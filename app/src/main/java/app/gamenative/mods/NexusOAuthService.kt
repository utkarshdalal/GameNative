package app.gamenative.mods

import app.gamenative.BuildConfig
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class NexusOAuthEndpoints(
    val token: String = NexusOAuthConfig.TOKEN_ENDPOINT,
    val revocation: String = NexusOAuthConfig.REVOCATION_ENDPOINT,
    val userInfo: String = NexusOAuthConfig.USER_INFO_ENDPOINT,
)

internal data class NexusTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val tokenType: String,
    val expiresInSeconds: Long,
    val createdAtEpochSeconds: Long?,
) {
    override fun toString(): String =
        "NexusTokenResponse(accessToken=[REDACTED], refreshToken=[REDACTED], " +
            "tokenType=$tokenType, expiresIn=$expiresInSeconds, createdAt=$createdAtEpochSeconds)"
}

internal class NexusOAuthException(
    message: String,
    val errorCode: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val isInvalidGrant: Boolean
        get() = errorCode.equals("invalid_grant", ignoreCase = true)
}

internal interface NexusOAuthRemote {
    suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
    ): NexusTokenResponse

    suspend fun refresh(refreshToken: String): NexusTokenResponse

    suspend fun getUserInfo(accessToken: String): NexusOAuthAccount

    suspend fun revoke(token: String, tokenTypeHint: String)
}

internal class NexusOAuthService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val endpoints: NexusOAuthEndpoints = NexusOAuthEndpoints(),
) : NexusOAuthRemote {

    override suspend fun exchangeAuthorizationCode(
        code: String,
        codeVerifier: String,
    ): NexusTokenResponse = requestToken(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", NexusOAuthConfig.CLIENT_ID)
            .add("redirect_uri", NexusOAuthConfig.REDIRECT_URI)
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .build(),
    )

    override suspend fun refresh(refreshToken: String): NexusTokenResponse = requestToken(
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", NexusOAuthConfig.CLIENT_ID)
            .add("refresh_token", refreshToken)
            .build(),
    )

    override suspend fun getUserInfo(accessToken: String): NexusOAuthAccount = withContext(Dispatchers.IO) {
        val request = baseRequest(endpoints.userInfo)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw oauthError(response.code, body, "Nexus account lookup failed")
            }
            val json = parseJson(body, "Nexus returned an invalid account response")
            val id = json.optString("sub")
                .toLongOrNull()
                ?.takeIf { it > 0L }
                ?.toString()
            val name = json.optString("name")
            if (id == null || name.isBlank()) {
                throw NexusOAuthException("Nexus returned an incomplete account response")
            }
            val rolesJson = json.optJSONArray("membership_roles")
                ?: throw NexusOAuthException("Nexus returned an incomplete account response")
            val roles = buildList {
                for (index in 0 until rolesJson.length()) {
                    val role = (rolesJson.opt(index) as? String)
                        ?.takeIf(String::isNotBlank)
                        ?: throw NexusOAuthException("Nexus returned an invalid account response")
                    add(role)
                }
            }
            NexusOAuthAccount(
                id = id,
                name = name,
                membershipRoles = roles,
            )
        }
    }

    override suspend fun revoke(token: String, tokenTypeHint: String): Unit = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("client_id", NexusOAuthConfig.CLIENT_ID)
            .add("token", token)
            .add("token_type_hint", tokenTypeHint)
            .build()
        val request = baseRequest(endpoints.revocation)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw oauthError(response.code, responseBody, "Nexus token revocation failed")
            }
        }
    }

    private suspend fun requestToken(body: FormBody): NexusTokenResponse = withContext(Dispatchers.IO) {
        val request = baseRequest(endpoints.token)
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw oauthError(response.code, responseBody, "Nexus authorization failed")
            }
            val json = parseJson(responseBody, "Nexus returned an invalid token response")
            val accessToken = json.optString("access_token")
            val expiresIn = json.optLong("expires_in", 0L)
            val tokenType = json.optString("token_type", "Bearer")
            if (
                accessToken.isBlank() ||
                expiresIn <= 0L ||
                !tokenType.equals("Bearer", ignoreCase = true)
            ) {
                throw NexusOAuthException("Nexus returned an incomplete token response")
            }
            NexusTokenResponse(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank),
                tokenType = "Bearer",
                expiresInSeconds = expiresIn,
                createdAtEpochSeconds = json.optLong("created_at", 0L).takeIf { it > 0L },
            )
        }
    }

    private fun baseRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .header("Application-Name", "GameNative")
        .header("Application-Version", BuildConfig.VERSION_NAME)
        .header("User-Agent", "GameNative/${BuildConfig.VERSION_NAME}")

    private fun oauthError(statusCode: Int, body: String, fallback: String): NexusOAuthException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val errorCode = json?.optString("error")?.takeIf(String::isNotBlank)
        val description = json?.optString("error_description")
            ?.takeIf(String::isNotBlank)
            ?.take(MAX_ERROR_DESCRIPTION_LENGTH)
        return NexusOAuthException(
            message = description ?: errorCode ?: "$fallback (HTTP $statusCode)",
            errorCode = errorCode,
        )
    }

    private fun parseJson(body: String, message: String): JSONObject =
        try {
            JSONObject(body)
        } catch (error: Exception) {
            throw NexusOAuthException(message, cause = error)
        }

    private companion object {
        private const val MAX_ERROR_DESCRIPTION_LENGTH = 300
    }
}
