// Based on xodus (https://github.com/xodus-gaming/xodus), GPL-3.0.
package app.gamenative.service.xbox.live

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

open class LiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

object LiveCrypto {
    /** KDF label login.live.com uses for WS-SecureConversation session keys. */
    const val KDF_LABEL = "WS-SecureConversationWS-SecureConversation"

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    fun nonce(): ByteArray = randomBytes(32)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /**
     * NIST SP 800-108 counter-mode KDF with HMAC-SHA256:
     * HMAC(key, BE32(i) || label || 0x00 || context || BE32(bits)).
     */
    fun sp800108(keyLength: Int, key: ByteArray, label: String, context: ByteArray): ByteArray {
        val labelBytes = label.toByteArray(Charsets.US_ASCII)
        val material = ByteBuffer.allocate(4 + labelBytes.size + 1 + context.size + 4)
        material.putInt(0).put(labelBytes).put(0).put(context).putInt(keyLength * 8)
        val input = material.array()
        val out = ByteArray(keyLength)
        var produced = 0
        var counter = 1
        while (produced < keyLength) {
            ByteBuffer.wrap(input, 0, 4).putInt(counter++)
            val block = hmacSha256(key, input)
            val amount = minOf(block.size, keyLength - produced)
            System.arraycopy(block, 0, out, produced, amount)
            produced += amount
        }
        return out
    }

    /** Session key for one RST2 message: derived from the device secret, then again from the TPM secret when present. */
    fun sessionKey(clepSecret: ByteArray, tpmSecret: ByteArray, nonce: ByteArray): ByteArray {
        val clep = sp800108(32, clepSecret, KDF_LABEL, nonce)
        return if (tpmSecret.isEmpty()) clep else sp800108(32, tpmSecret, KDF_LABEL, clep)
    }

    /** XML-Enc payload: 16-byte IV followed by AES-256-CBC ciphertext with PKCS#7 padding. */
    fun decryptCipherValue(cipherValue: ByteArray, key: ByteArray): ByteArray {
        if (cipherValue.size < 16) throw LiveException("Response contains an invalid encrypted payload")
        return try {
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(cipherValue, 0, 16))
                doFinal(cipherValue, 16, cipherValue.size - 16)
            }
        } catch (e: java.security.GeneralSecurityException) {
            throw LiveException("Unable to decrypt response", e)
        }
    }

    private const val ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun randomAlphanumeric(length: Int): String =
        buildString(length) { repeat(length) { append(ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)]) } }
}
