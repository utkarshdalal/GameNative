package app.gamenative.service.ea

import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto primitives EA's launcher uses on the LSX socket and for licence files.
 * Ported from the Maxima and EAappEmulater implementations.
 */
object EaCrypto {
    /** Fixed key for the LSX challenge and for "version 2" sessions. */
    val CHALLENGE_KEY: ByteArray = ByteArray(16) { it.toByte() }

    /** AES-128-CBC key (zero IV) for the .dlf licence blobs. */
    val OOA_KEY: ByteArray = byteArrayOf(
        65, 50, 114, 45, 208.toByte(), 130.toByte(), 239.toByte(), 176.toByte(),
        220.toByte(), 100, 87, 197.toByte(), 118, 104, 202.toByte(), 9,
    )

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Lenient hex decode: ignores anything that is not a hex digit, empty on odd length. */
    fun unhex(s: String): ByteArray {
        val clean = s.lowercase().filter { it in "0123456789abcdef" }
        if (clean.length % 2 != 0) return ByteArray(0)
        return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)

    fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")) }.doFinal(data)

    /** LSX wire encryption: AES-ECB, hex encoded. */
    fun lsxEncrypt(key: ByteArray, plain: String): String = hex(aesEcbEncrypt(key, plain.toByteArray(Charsets.UTF_8)))

    fun lsxDecrypt(key: ByteArray, hexData: String): String = String(aesEcbDecrypt(key, unhex(hexData)), Charsets.UTF_8)

    fun checkChallengeResponse(response: String, challenge: String): Boolean =
        runCatching { String(aesEcbDecrypt(CHALLENGE_KEY, unhex(response)), Charsets.US_ASCII) == challenge }.getOrDefault(false)

    fun makeChallengeResponse(key: String): String = hex(aesEcbEncrypt(CHALLENGE_KEY, key.toByteArray(Charsets.US_ASCII)))

    /** Session key derivation: MSVC LCG seeded from the accepted-key bytes. Seed 0 means the fixed key. */
    fun makeLsxKey(seed: Int): ByteArray {
        if (seed == 0) return CHALLENGE_KEY
        val rng = CRandom()
        rng.seed(7)
        rng.seed((rng.rand().toLong() + seed).toInt())
        return ByteArray(16) { rng.rand().toByte() }
    }

    class CRandom {
        private var state = 0

        fun seed(s: Int) {
            state = s
        }

        fun rand(): Int {
            state = state * 214013 + 2531011
            return (state ushr 16) and 0xFFFF
        }
    }

    fun ooaDecrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(OOA_KEY, "AES"), IvParameterSpec(ByteArray(16)))
        }.doFinal(data)

    fun ooaEncrypt(data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(OOA_KEY, "AES"), IvParameterSpec(ByteArray(16)))
        }.doFinal(data)

    /** EARtPLaunchCode: date-derived handshake value the game expects in its environment. */
    fun rtpHandshake(): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val year = cal.get(Calendar.YEAR).toLong()
        val month = (cal.get(Calendar.MONTH) + 1).toLong()
        val day = cal.get(Calendar.DAY_OF_MONTH).toLong()
        val t = ((104729L * year) xor (month * 224737L) xor (day * 350377L)) and 0xFFFFFFFFL
        return (t xor ((t shl 16) and 0xFFFFFFFFL) xor (t ushr 16)) and 0xFFFFFFFFL
    }

    fun fnv1a64(data: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in data) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return hash
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
