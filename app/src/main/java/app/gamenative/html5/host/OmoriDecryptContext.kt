package app.gamenative.html5.host

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import timber.log.Timber

// AES-256-CTR decrypt for OMORI's `.OMORI` plugin files. key comes from Steam launch
// arguments (`--<32-hex>`) on the OMORI.exe entry -- see SteamService.getLaunchArgumentsForOs.
// registry: TitleQuirks.OMORI.
// the 32-hex string is used as ASCII bytes (32 bytes) for the AES-256 key, NOT hex-decoded --
// this matches OMORI's own `crypto.createDecipheriv("aes-256-ctr", steamkey, iv)` where
// steamkey is a JS string.

// `.OMORI` file format: [16-byte IV][AES-256-CTR ciphertext]. plugin file = decrypted JS source.
class OmoriDecryptContext(private val keyBytes: ByteArray) {
    init {
        require(keyBytes.size == 32) { "OMORI key must be 32 bytes (got ${keyBytes.size})" }
    }

    // reads the entire encrypted body, decrypts, returns a stream over the plaintext.
    // .OMORI plugin sizes are bounded (largest in OMORI ~4MB) -- full-buffer decrypt is fine.
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

    // OMORI ships data/System.KEL (AES-encrypted) where stock RMMV ships data/System.json.
    // standard `.rpgmvp`/.rpgmvo XOR key still lives in the JSON's `encryptionKey` field --
    // we just have to decrypt+parse System.KEL Kotlin-side to extract it. returns the 16-byte
    // XOR key, or null on any failure (missing file, parse error, malformed key).
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
        // factory: takes the raw Steam launch arg (with leading `--`) and builds a context.
        // returns null when the arg is missing/malformed.
        fun fromSteamLaunchArg(launchArg: String?): OmoriDecryptContext? {
            val stripped = launchArg?.removePrefix("--")?.takeIf { it.length == 32 } ?: return null
            return runCatching { OmoriDecryptContext(stripped.toByteArray(Charsets.UTF_8)) }
                .onFailure { Timber.tag("OmoriDecrypt").e(it, "context build failed") }
                .getOrNull()
        }
    }
}
