package app.gamenative.html5.host

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import timber.log.Timber

// registry: TitleQuirks.OMORI. key is the Steam launch arg `--<32-hex>`.
// the 32 hex chars are used as ASCII key bytes, NOT hex-decoded -- OMORI passes the JS string straight to
// createDecipheriv. file format: [16-byte IV][AES-256-CTR ciphertext].
class OmoriDecryptContext(private val keyBytes: ByteArray) {
    init {
        require(keyBytes.size == 32) { "OMORI key must be 32 bytes (got ${keyBytes.size})" }
    }

    // full-buffer decrypt is fine: the largest file is ~4MB.
    fun decryptStream(encrypted: InputStream): InputStream? {
        return runCatching {
            val all = encrypted.readBytes()
            if (all.size < 16) {
                Timber.tag("OmoriDecrypt").w("body too short to contain IV (size=%d)", all.size)
                return@runCatching null
            }
            val iv = all.copyOfRange(0, 16)
            val ct = all.copyOfRange(16, all.size)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val pt = cipher.doFinal(ct)
            ByteArrayInputStream(pt) as InputStream
        }.onFailure {
            Timber.tag("OmoriDecrypt").e(it, "AES-CTR decrypt failed")
        }.getOrNull()
    }

    // OMORI ships an AES-encrypted data/System.KEL in place of System.json; the standard RMMV XOR
    // key is its `encryptionKey` field.
    fun resolveRmmvXorKey(installRoot: File): ByteArray? {
        val systemKel = File(installRoot, "data/System.KEL")
        if (!systemKel.isFile) {
            Timber.tag("OmoriDecrypt").w("no data/System.KEL — cannot resolve RMMV XOR key")
            return null
        }
        return runCatching {
            val plaintextStream = decryptStream(systemKel.inputStream()) ?: return@runCatching null
            val json = JSONObject(plaintextStream.readBytes().toString(Charsets.UTF_8))
            val hex = json.optString("encryptionKey", "")
            if (hex.length != 32) {
                Timber.tag("OmoriDecrypt").w("System.KEL encryptionKey length %d != 32", hex.length)
                return@runCatching null
            }
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.onFailure {
            Timber.tag("OmoriDecrypt").e(it, "failed to extract RMMV XOR key from System.KEL")
        }.getOrNull()
    }

    companion object {
        fun fromSteamLaunchArg(launchArg: String?): OmoriDecryptContext? {
            val stripped = launchArg?.removePrefix("--")?.takeIf { it.length == 32 } ?: return null
            return runCatching { OmoriDecryptContext(stripped.toByteArray(Charsets.UTF_8)) }
                .onFailure { Timber.tag("OmoriDecrypt").e(it, "context build failed") }
                .getOrNull()
        }
    }
}
