package app.gamenative.mods

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the non-secret account metadata Nexus places in its access-token JWT.
 *
 * This is not token authentication: Nexus's API servers still validate the signature and authorize
 * every Bearer request. The claims only let the client display the connected account and choose the
 * free/premium download flow without calling the API-key-only validation endpoint.
 */
internal fun nexusAccountFromAccessToken(accessToken: String): NexusOAuthAccount? {
    if (accessToken.isEmpty() || accessToken.length > MAX_JWT_CHARS) return null

    val firstSeparator = accessToken.indexOf('.')
    val secondSeparator = accessToken.indexOf('.', firstSeparator + 1)
    if (
        firstSeparator !in 1..MAX_JWT_HEADER_CHARS ||
        secondSeparator <= firstSeparator + 1 ||
        secondSeparator >= accessToken.lastIndex ||
        accessToken.indexOf('.', secondSeparator + 1) != -1
    ) {
        return null
    }

    val header = accessToken.substring(0, firstSeparator)
    val payload = accessToken.substring(firstSeparator + 1, secondSeparator)
    val signature = accessToken.substring(secondSeparator + 1)
    if (
        payload.length > MAX_JWT_PAYLOAD_CHARS ||
        signature.length > MAX_JWT_SIGNATURE_CHARS ||
        !header.isStrictBase64Url() ||
        !payload.isStrictBase64Url() ||
        !signature.isStrictBase64Url()
    ) {
        return null
    }

    val payloadBytes = try {
        Base64.getUrlDecoder().decode(payload)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (payloadBytes.isEmpty() || payloadBytes.size > MAX_JWT_PAYLOAD_BYTES) return null

    val payloadText = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(payloadBytes))
            .toString()
    } catch (_: Exception) {
        return null
    }

    val user = try {
        JSONObject(payloadText).optJSONObject("user")
    } catch (_: Exception) {
        null
    } ?: return null

    val userId = (user.opt("id") as? Number)
        ?.toString()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: return null
    val username = (user.opt("username") as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_USERNAME_CHARS }
        ?: return null
    val roles = if (user.has("membership_roles")) {
        user.optJSONArray("membership_roles")?.strictStringValues() ?: return null
    } else {
        emptyList()
    }

    return NexusOAuthAccount(
        id = userId.toString(),
        name = username,
        membershipRoles = roles,
    )
}

private fun String.isStrictBase64Url(): Boolean =
    isNotEmpty() && all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-' ||
            character == '_'
    }

private fun JSONArray.strictStringValues(): List<String>? {
    if (length() > MAX_MEMBERSHIP_ROLES) return null
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        val role = (opt(index) as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_MEMBERSHIP_ROLE_CHARS }
            ?: return null
        result += role
    }
    return result.distinct()
}

private const val MAX_JWT_CHARS = 512 * 1024
private const val MAX_JWT_HEADER_CHARS = 8 * 1024
private const val MAX_JWT_PAYLOAD_CHARS = 384 * 1024
private const val MAX_JWT_SIGNATURE_CHARS = 16 * 1024
private const val MAX_JWT_PAYLOAD_BYTES = 288 * 1024
private const val MAX_USERNAME_CHARS = 256
private const val MAX_MEMBERSHIP_ROLES = 64
private const val MAX_MEMBERSHIP_ROLE_CHARS = 128
