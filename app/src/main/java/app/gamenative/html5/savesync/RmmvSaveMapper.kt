package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import timber.log.Timber

// rmmv-filesystem mapper: rmmv <.rpgsave> files ↔ chromium localStorage leveldb entries.
// byte-for-byte passthrough -- the value payload IS the .rpgsave file content, wrapped only
// with Chromium's single-byte framing.

// chromium localStorage key format (per LocalStorageImpl): `_<origin>\x00<utf-16-LE storage key>`.
// value format: `0x01 <payload>` -- 0x01 = utf-8 content marker, payload = the .rpgsave ASCII
// bytes verbatim.

// keys we recognise (RMMV):
// file<n>.rpgsave ↔ "RPG File<n>"
// config.rpgsave ↔ "RPG Config"
// global.rpgsave ↔ "RPG Global"
// file<n>save.rpgsave ↔ "RPG Save<n>" (documented-not-observed)
object RmmvSaveMapper {

    private const val VALUE_FRAME_UTF8: Byte = 0x01
    private val KEY_PREFIX_UNDERSCORE: Byte = '_'.code.toByte()
    private val KEY_SEPARATOR_NUL: Byte = 0x00

    // WebView localStorage LevelDB → <saveDir>/*.rpgsave.
    // strips framing byte from each RPG-prefixed value, writes payload verbatim to matching file.
    // bails via SaveSyncFailure on open/read failure; missing srcDb path throws PathMissing.
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

                        // storage key is the key text after _<origin>\x00. decode utf-16-LE.
                        val storageKeyBytes = key.copyOfRange(expectedKeyPrefix.size, key.size)
                        val storageKey = decodeUtf16LeSafe(storageKeyBytes) ?: continue
                        val filename = filenameForStorageKey(storageKey) ?: continue

                        val payload = stripValueFraming(entry.value) ?: continue
                        if (payload.isEmpty()) {
                            // empty payload → write nothing; .rpgsave files are never empty.
                            Timber.tag("RmmvSaveMapper").w("skipping empty payload for key=%s", storageKey)
                            continue
                        }
                        File(saveDir, filename).writeBytes(payload)
                    }
                }
            }
        } catch (failure: SaveSyncFailure) {
            throw failure
        } catch (t: Throwable) {
            throw classifyFailure(t, localStorageDb, saveDir)
        }
    }

    // <saveDir>/*.rpgsave → WebView localStorage LevelDB.
    // each known-mapped file becomes `0x01 <payload>` under key `_<origin>\x00<utf-16-LE key>`.
    // empty files skipped (Timber.w). unknown filenames skipped silently.
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

    // --- key/value framing helpers ---

    // `_<origin>\x00` -- bytes before the utf-16-LE storage key.
    private fun buildKeyPrefix(origin: String): ByteArray {
        val originBytes = origin.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(1 + originBytes.size + 1)
        out[0] = KEY_PREFIX_UNDERSCORE
        System.arraycopy(originBytes, 0, out, 1, originBytes.size)
        out[out.size - 1] = KEY_SEPARATOR_NUL
        return out
    }

    // strip chromium localStorage framing -- expects first byte to be 0x01 (utf-8 content marker).
    // returns null + logs on framing mismatch rather than crashing -- some chromium versions use
    // 0x00 for utf-16-LE payloads which aren't our case but shouldn't abort the whole sync.
    private fun stripValueFraming(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        if (raw[0] != VALUE_FRAME_UTF8) {
            Timber.tag("RmmvSaveMapper").w("unexpected value framing byte: 0x%02X (expected 0x01)", raw[0].toInt() and 0xFF)
            return null
        }
        return raw.copyOfRange(1, raw.size)
    }

    // prepend 0x01 framing byte. matches the on-device layout.
    private fun wrapValueFraming(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 1)
        out[0] = VALUE_FRAME_UTF8
        System.arraycopy(payload, 0, out, 1, payload.size)
        return out
    }

    // filename → key mapping (reverse of storageKeyForFilename). null for unrecognised filenames.
    internal fun storageKeyForFilename(filename: String): String? {
        val base = filename.removeSuffix(".rpgsave")
        if (base == filename) return null // must end with .rpgsave
        return when {
            base == "config" -> "RPG Config"
            base == "global" -> "RPG Global"
            // "fileNsave" → "RPG SaveN" (documented; not observed in TERMINA fixtures)
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

    // key → filename mapping.
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

    // --- utf-16-LE codec (android-free pure-JVM) ---

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

    // tolerant utf-16-LE decode -- returns null on odd-length or invalid inputs.
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

    // prefix match for ByteArray (same as LevelDbRewriter.startsWith but private here to avoid
    // coupling).
    private fun startsWith(key: ByteArray, prefix: ByteArray): Boolean {
        if (key.size < prefix.size) return false
        for (i in prefix.indices) {
            if (key[i] != prefix[i]) return false
        }
        return true
    }

    // --- lib open / failure classification ---

    // localStorage uses bytewise comparator (chromium default for localStorage) -- NOT Idb1Comparator.
    private fun openDb(dir: File, readOnly: Boolean): org.iq80.leveldb.DB {
        val options = Options().apply {
            createIfMissing(!readOnly)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
        }
        return Iq80DBFactory.factory.open(dir, options)
    }

    // delegates to the shared LeveldbFailures. no `.sst`/`.ldb`→Corruption branch here (RMMV
    // maps LS, not a synthesized-MANIFEST snapshot). internal for direct test access.
    internal fun classifyFailure(t: Throwable, src: File, dst: File): SaveSyncFailure =
        LeveldbFailures.classify(t, src, dst, sstLdbAsCorruption = false)
}
