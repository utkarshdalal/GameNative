package app.gamenative.service.amazon

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** PKCE utilities for Amazon OAuth. */
object AmazonPKCEGenerator {

    private val secureRandom = SecureRandom()

    /** Generate a random device serial. */
    fun generateDeviceSerial(): String {
        return UUID.randomUUID().toString().replace("-", "").uppercase()
    }

    /** Build the dynamic client_id by hex-encoding "serial#DEVICE_TYPE". */
    fun generateClientId(deviceSerial: String): String {
        val raw = "$deviceSerial#${AmazonConstants.DEVICE_TYPE}"
        return raw.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    }

    /** Generate a PKCE code verifier. */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** Derive a PKCE code challenge from a code verifier. */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
