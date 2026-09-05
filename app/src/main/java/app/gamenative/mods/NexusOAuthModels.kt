package app.gamenative.mods

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Public/native Nexus OAuth configuration. A client secret must never be shipped in the app. */
object NexusOAuthConfig {
    const val CLIENT_ID = "gamenative"
    const val REDIRECT_URI = "app.gamenative://oauth/callback"
    const val AUTHORIZATION_ENDPOINT = "https://users.nexusmods.com/oauth/authorize"
    const val TOKEN_ENDPOINT = "https://users.nexusmods.com/oauth/token"
    const val REVOCATION_ENDPOINT = "https://users.nexusmods.com/oauth/revoke"
    const val USER_INFO_ENDPOINT = "https://users.nexusmods.com/oauth/userinfo"
    const val SCOPE = "openid"

    internal const val AUTH_TRANSACTION_TTL_MILLIS = 10L * 60L * 1000L
    internal const val ACCESS_TOKEN_EXPIRY_SKEW_SECONDS = 60L
}

enum class NexusConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

enum class NexusAuthError {
    SIGN_IN_FAILED,
    SESSION_EXPIRED,
    REFRESH_RETRY_PENDING,
    CREDENTIAL_STORAGE_FAILED,
}

data class NexusOAuthAccount(
    val id: String,
    val name: String,
    val membershipRoles: List<String> = emptyList(),
) {
    val isPremium: Boolean
        get() = membershipRoles.any {
            it.equals("premium", ignoreCase = true) ||
                it.equals("lifetime", ignoreCase = true) ||
                it.equals("lifetimepremium", ignoreCase = true)
        }
}

data class NexusAuthState(
    val connection: NexusConnectionState = NexusConnectionState.DISCONNECTED,
    val account: NexusOAuthAccount? = null,
    val error: NexusAuthError? = null,
) {
    val isConnected: Boolean
        get() = connection == NexusConnectionState.CONNECTED
}

internal data class NexusPkcePair(
    val verifier: String,
    val challenge: String,
)

internal object NexusPkce {
    private const val RANDOM_BYTE_COUNT = 32

    fun generate(random: SecureRandom = SecureRandom()): NexusPkcePair {
        val verifier = randomUrlSafeString(random)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return NexusPkcePair(
            verifier = verifier,
            challenge = digest.base64Url(),
        )
    }

    fun generateState(random: SecureRandom = SecureRandom()): String =
        randomUrlSafeString(random)

    private fun randomUrlSafeString(random: SecureRandom): String {
        val bytes = ByteArray(RANDOM_BYTE_COUNT)
        random.nextBytes(bytes)
        return bytes.base64Url()
    }

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)
}

internal fun statesMatch(expected: String, actual: String): Boolean =
    MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        actual.toByteArray(Charsets.UTF_8),
    )
