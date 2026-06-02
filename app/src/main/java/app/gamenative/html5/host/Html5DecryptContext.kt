package app.gamenative.html5.host

import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.SequenceInputStream

// RMMV asset decrypt (.rpgmv{o,p,m}). PERF: never read the whole file into memory -- stream the
// decrypted header followed by the untouched tail.
// preResolvedKey is for titles whose key isn't in data/System.json (OMORI keeps it inside AES-encrypted
// System.KEL, see OmoriDecryptContext).
class Html5DecryptContext(installRoot: File, preResolvedKey: ByteArray? = null) {
    private val key: ByteArray? by lazy {
        if (preResolvedKey != null && preResolvedKey.size == 16) preResolvedKey
        else readKey(installRoot)
    }

    val hasKey: Boolean get() = key != null

    // layout: 16-byte fake header, 16 XOR'd bytes, plaintext tail. null (no key / file too short) ->
    // callers serve raw bytes.
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
