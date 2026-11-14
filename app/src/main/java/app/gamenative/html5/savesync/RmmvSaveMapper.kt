package app.gamenative.html5.savesync

import app.gamenative.html5.writeBytesAtomic
import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import timber.log.Timber

// rmmv .rpgsave files <-> chromium localStorage entries, byte-for-byte: the value payload IS the file
// content behind chromium's one-byte framing.
// key: `_<origin>\x00<utf-16-LE storage key>`; value: `0x01 <payload>` (0x01 = latin-1 marker).
object RmmvSaveMapper {

    private const val VALUE_FRAME_LATIN1: Byte = 0x01
    private val KEY_PREFIX_UNDERSCORE: Byte = '_'.code.toByte()
    private val KEY_SEPARATOR_NUL: Byte = 0x00

    fun writeLocalStorageToFiles(
        localStorageDb: File,
        webViewOriginPrefix: String,
        saveDir: File,
    ) {
        if (!localStorageDb.isDirectory) throw SaveSyncFailure.PathMissing(localStorageDb.absolutePath)
        saveDir.mkdirs()

        val expectedKeyPrefix = buildKeyPrefix(webViewOriginPrefix)

        try {
            openDb(localStorageDb, readOnly = true).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        val entry = iter.next()
                        val key = entry.key
                        if (!startsWith(key, expectedKeyPrefix)) continue

                        val storageKeyBytes = key.copyOfRange(expectedKeyPrefix.size, key.size)
                        val storageKey = decodeUtf16LeSafe(storageKeyBytes) ?: continue
                        val filename = filenameForStorageKey(storageKey) ?: continue

                        val payload = stripValueFraming(entry.value) ?: continue
                        if (payload.isEmpty()) {
                            // .rpgsave files are never empty.
                            Timber.tag("RmmvSaveMapper").w("skipping empty payload for key=%s", storageKey)
                            continue
                        }
                        File(saveDir, filename).writeBytesAtomic(payload)
                    }
                }
            }
        } catch (failure: SaveSyncFailure) {
            throw failure
        } catch (t: Throwable) {
            throw classifyFailure(t, localStorageDb, saveDir)
        }
    }

    fun readFilesToLocalStorage(
        saveDir: File,
        localStorageDb: File,
        webViewOriginPrefix: String,
    ) {
        if (!saveDir.isDirectory) throw SaveSyncFailure.PathMissing(saveDir.absolutePath)
        localStorageDb.mkdirs()

        val keyPrefix = buildKeyPrefix(webViewOriginPrefix)

        try {
            openDb(localStorageDb, readOnly = false).use { db ->
                val files = saveDir.listFiles() ?: emptyArray()
                for (file in files) {
                    if (!file.isFile) continue
                    val storageKey = storageKeyForFilename(file.name) ?: continue
                    if (file.length() == 0L) {
                        Timber.tag("RmmvSaveMapper").w("skipping empty .rpgsave file: %s", file.name)
                        continue
                    }
                    val payload = file.readBytes()
                    val value = wrapValueFraming(payload)
                    val dbKey = keyPrefix + encodeUtf16Le(storageKey)
                    db.put(dbKey, value)
                }
            }
        } catch (failure: SaveSyncFailure) {
            throw failure
        } catch (t: Throwable) {
            throw classifyFailure(t, saveDir, localStorageDb)
        }
    }

    private fun buildKeyPrefix(origin: String): ByteArray {
        val originBytes = origin.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + originBytes.size + 1)
        out[0] = KEY_PREFIX_UNDERSCORE
        System.arraycopy(originBytes, 0, out, 1, originBytes.size)
        out[out.size - 1] = KEY_SEPARATOR_NUL
        return out
    }

    // a 0x00 (utf-16-LE) value isn't an .rpgsave; skip it rather than abort the whole sync.
    private fun stripValueFraming(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        if (raw[0] != VALUE_FRAME_LATIN1) {
            Timber.tag("RmmvSaveMapper").w("unexpected value framing byte: 0x%02X (expected 0x01)", raw[0].toInt() and 0xFF)
            return null
        }
        return raw.copyOfRange(1, raw.size)
    }

    private fun wrapValueFraming(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = VALUE_FRAME_LATIN1
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    internal fun storageKeyForFilename(filename: String): String? {
        val base = filename.removeSuffix(".rpgsave")
        if (base == filename) return null // must end with .rpgsave
        return when {
            base == "config" -> "RPG Config"
            base == "global" -> "RPG Global"
            // "fileNsave" -> "RPG SaveN" (documented, never seen in the wild)
            base.matches(Regex("""^file\d+save$""")) -> {
                val n = base.removePrefix("file").removeSuffix("save")
                "RPG Save$n"
            }
            base.matches(Regex("""^file\d+$""")) -> {
                val n = base.removePrefix("file")
                "RPG File$n"
            }
            else -> null
        }
    }

    internal fun filenameForStorageKey(storageKey: String): String? {
        return when {
            storageKey == "RPG Config" -> "config.rpgsave"
            storageKey == "RPG Global" -> "global.rpgsave"
            storageKey.matches(Regex("""^RPG File\d+$""")) -> {
                val n = storageKey.removePrefix("RPG File")
                "file$n.rpgsave"
            }
            storageKey.matches(Regex("""^RPG Save\d+$""")) -> {
                val n = storageKey.removePrefix("RPG Save")
                "file${n}save.rpgsave"
            }
            else -> null
        }
    }

    private fun encodeUtf16Le(s: String): ByteArray {
        val chars = s.toCharArray()
        val out = ByteArray(chars.size * 2)
        for ((i, c) in chars.withIndex()) {
            val code = c.code
            out[i * 2] = (code and 0xFF).toByte()
            out[i * 2 + 1] = ((code ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun decodeUtf16LeSafe(bytes: ByteArray): String? {
        if (bytes.size % 2 != 0) return null
        val sb = StringBuilder(bytes.size / 2)
        var i = 0
        while (i < bytes.size) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
            i += 2
        }
        return sb.toString()
    }

    private fun startsWith(key: ByteArray, prefix: ByteArray): Boolean {
        if (key.size < prefix.size) return false
        for (i in prefix.indices) {
            if (key[i] != prefix[i]) return false
        }
        return true
    }

    // LS is bytewise -- NOT Idb1Comparator.
    private fun openDb(dir: File, readOnly: Boolean): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
        }
        return Iq80DBFactory.factory.open(dir, options)
    }

    // no synthesized MANIFEST here, so a missing .sst/.ldb is not corruption.
    internal fun classifyFailure(t: Throwable, src: File, dst: File): SaveSyncFailure =
        LeveldbFailures.classify(t, src, dst, sstLdbAsCorruption = false)
}
