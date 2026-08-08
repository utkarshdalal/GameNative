package app.gamenative.html5.host

import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

// reads data/System.json once at WebViewScreen setup. cached key re-used for every
// .rpgmv{o,p,m} request. NO third-party crypto -- 5 lines of XOR.

// PERF CRITICAL: do NOT read the entire file into memory. uses
// SequenceInputStream to concatenate a tiny decrypted-header ByteArrayInputStream with the
// tail InputStream untouched. `ByteArrayInputStream(decryptedHeader + readBytes())` is WRONG.

// preResolvedKey lets callers inject the 16-byte RMMV XOR key directly when System.json is
// not on disk in the standard location -- e.g. OMORI's standard PNG/OGG XOR key lives inside
// AES-encrypted data/System.KEL, decoded Kotlin-side via OmoriDecryptContext. when set,
// installRoot's System.json is not consulted.
class Html5DecryptContext(installRoot: File, preResolvedKey: ByteArray? = null) {
    private val key: ByteArray? by lazy {
        if (preResolvedKey != null && preResolvedKey.size == 16) preResolvedKey
        else readKey(installRoot)
    }

    val hasKey: Boolean get() = key != null

    // skip 16-byte fake RPGMV header, XOR next 16 bytes with key, return concatenation of
    // decrypted header + untouched tail stream. returns null on missing key OR file shorter
    // than the 32-byte decrypt region -- callers fall through to raw bytes (fail-loud-but-not-crash).
    fun wrapStream(encrypted: InputStream): InputStream? {
        val k = key ?: return null
        val skipped = encrypted.skip(16)
        if (skipped != 16L) {
            Timber.tag("Html5Decrypt").w("file shorter than RPGMV header (skipped=%d)", skipped)
            return null
        }
        val headerXor = ByteArray(16)
        val read = encrypted.read(headerXor)
        if (read != 16) {
            Timber.tag("Html5Decrypt").w("file shorter than XOR header region (read=%d)", read)
            return null
        }
        for (i in 0..15) headerXor[i] = (headerXor[i].toInt() xor k[i].toInt()).toByte()
        return SequenceInputStream(ByteArrayInputStream(headerXor), encrypted)
    }

    private fun readKey(root: File): ByteArray? {
        val systemJson = File(root, "data/System.json")
        if (!systemJson.isFile) {
            Timber.tag("Html5Decrypt").d("no data/System.json — skipping decrypt")
            return null
        }
        return runCatching {
            val json = JSONObject(systemJson.readText(Charsets.UTF_8))
            val hex = json.optString("encryptionKey", "")
            if (hex.length != 32) {
                Timber.tag("Html5Decrypt").w("encryptionKey length %d != 32 — skipping decrypt", hex.length)
                return@runCatching null
            }
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }.onFailure {
            Timber.tag("Html5Decrypt").e(it, "failed to parse System.json")
        }.getOrNull()
    }
}
