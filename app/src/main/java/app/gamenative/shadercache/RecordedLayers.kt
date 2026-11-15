package app.gamenative.shadercache

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater
import org.json.JSONObject

/**
 * Reads which DXVK and vkd3d-proton versions a Fossilize recording was made with, from the application info entries the
 * layer stores next to the pipelines (engine name and Vulkan-encoded engine version, as reported by the translation
 * layer itself). Entry layout: 40 hex characters of tag and hash, then stored size, flags (1 = raw, 2 = deflate), crc
 * and payload size as little-endian uint32, then the payload.
 */
internal object RecordedLayers {
    private const val HEADER_BYTES = 16L
    private const val HASH_CHARS = 40
    private const val TAG_CHARS = 24
    private const val ENTRY_HEADER_BYTES = HASH_CHARS + 16
    private const val MAX_APP_INFO_BYTES = 64 * 1024
    private const val FLAG_RAW = 1
    private const val FLAG_DEFLATE = 2
    private val MAGIC = byteArrayOf(0x81.toByte()) + "FOSSILIZEDB".toByteArray()
    private val APP_INFO_TAG = "0".repeat(TAG_CHARS)

    /** dxvk-<x.y.z> and vkd3d-<x.y.z> as reported in [files] (first one found of each), null where none was found. */
    fun read(files: List<File>): Pair<String?, String?> {
        var dxvk: String? = null
        var vkd3d: String? = null
        for (file in files) {
            runCatching {
                forEachAppInfo(file) { json ->
                    val info = json.optJSONObject("applicationInfo") ?: return@forEachAppInfo
                    val name = info.optString("engineName")
                    val version = info.optLong("engineVersion", -1L).takeIf { it >= 0 }?.let(::vulkanVersion)
                        ?: return@forEachAppInfo
                    when {
                        name.equals("DXVK", ignoreCase = true) -> if (dxvk == null) dxvk = "dxvk-$version"
                        name.startsWith("vkd3d", ignoreCase = true) -> if (vkd3d == null) vkd3d = "vkd3d-$version"
                    }
                }
            }
            if (dxvk != null && vkd3d != null) break
        }
        return dxvk to vkd3d
    }

    internal fun vulkanVersion(encoded: Long): String =
        "${(encoded ushr 22) and 0x7F}.${(encoded ushr 12) and 0x3FF}.${encoded and 0xFFF}"

    private fun forEachAppInfo(file: File, block: (JSONObject) -> Unit) {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            if (length < HEADER_BYTES) return
            val header = ByteArray(MAGIC.size).also { raf.readFully(it) }
            if (!header.contentEquals(MAGIC)) return
            var offset = HEADER_BYTES
            val entry = ByteArray(ENTRY_HEADER_BYTES)
            while (offset + ENTRY_HEADER_BYTES <= length) {
                raf.seek(offset)
                raf.readFully(entry)
                val stored = uint32(entry, HASH_CHARS)
                val flags = uint32(entry, HASH_CHARS + 4).toInt()
                val size = uint32(entry, HASH_CHARS + 12)
                val payloadAt = offset + ENTRY_HEADER_BYTES
                // The last entry may be cut short.
                if (payloadAt + stored > length) return
                if (String(entry, 0, TAG_CHARS, Charsets.US_ASCII) == APP_INFO_TAG &&
                    stored <= MAX_APP_INFO_BYTES && size <= MAX_APP_INFO_BYTES
                ) {
                    val payload = ByteArray(stored.toInt()).also { raf.readFully(it) }
                    val text = when (flags) {
                        FLAG_RAW -> payload
                        FLAG_DEFLATE -> inflate(payload, size.toInt())
                        else -> null
                    }
                    text?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }?.let(block)
                }
                offset = payloadAt + stored
            }
        }
    }

    private fun inflate(data: ByteArray, size: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArray(size)
            val read = inflater.inflate(out)
            if (read == size) out else null
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun uint32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)
}
