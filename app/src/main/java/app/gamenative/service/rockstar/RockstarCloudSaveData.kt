package app.gamenative.service.rockstar

import java.io.File
import org.json.JSONObject

/**
 * Best-effort reader for the `cloudsavedata.dat` that socialclub64.dll keeps next to a profile's
 * saves. The file is a bit-packed record set whose strings are not byte aligned, so each of the
 * eight bit alignments is scanned for a NUL-terminated file name followed by a balanced JSON
 * object, and each object is paired with the nearest preceding name in that alignment.
 *
 * The format is not fully decoded; a miss only degrades the metadata shown in the cloud-save
 * conflict dialog and never affects syncing itself.
 */
object RockstarCloudSaveData {
    private const val MAX_SIZE = 0x26FD0
    private const val MAX_JSON = 65536
    private const val MAX_NAME = 255

    fun readMetadata(file: File): Map<String, String> = runCatching {
        if (!file.isFile || file.length() > MAX_SIZE) return emptyMap()
        parse(file.readBytes())
    }.getOrDefault(emptyMap())

    internal fun parse(data: ByteArray): Map<String, String> = runCatching {
        val result = LinkedHashMap<String, String>()
        for (shift in 0 until 8) scan(shifted(data, shift), result)
        result
    }.getOrDefault(emptyMap())

    private fun shifted(data: ByteArray, shift: Int): ByteArray {
        if (shift == 0) return data
        return ByteArray(data.size) { i ->
            val high = (data[i].toInt() and 0xff) shl shift
            val low = if (i + 1 < data.size) (data[i + 1].toInt() and 0xff) ushr (8 - shift) else 0
            (high or low).toByte()
        }
    }

    private fun isNameChar(b: Int): Boolean =
        b in 'A'.code..'Z'.code || b in 'a'.code..'z'.code || b in '0'.code..'9'.code || Char(b) in " _.-()[]"

    private fun scan(b: ByteArray, result: MutableMap<String, String>) {
        var name: String? = null
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            if (c == '{'.code) {
                val end = jsonEnd(b, i)
                if (end > 0) {
                    val text = String(b, i, end - i, Charsets.US_ASCII)
                    val valid = runCatching { JSONObject(text).length() > 0 }.getOrDefault(false)
                    if (valid) {
                        name?.let { if (it !in result) result[it] = text }
                        name = null
                        i = end
                        continue
                    }
                }
            }
            if (isNameChar(c) && (i == 0 || b[i - 1].toInt() == 0)) {
                var j = i
                while (j < b.size && isNameChar(b[j].toInt() and 0xff)) j++
                if (j < b.size && b[j].toInt() == 0 && j - i <= MAX_NAME) {
                    val candidate = String(b, i, j - i, Charsets.US_ASCII)
                    if (candidate.isNotBlank()) name = candidate
                }
                i = j
                continue
            }
            i++
        }
    }

    private fun jsonEnd(b: ByteArray, start: Int): Int {
        var depth = 0
        var quoted = false
        var j = start
        while (j < b.size && j - start < MAX_JSON) {
            val c = b[j].toInt() and 0xff
            if (c < 0x20 || c > 0x7e) return -1
            if (quoted) {
                if (c == '\\'.code) j++ else if (c == '"'.code) quoted = false
            } else if (c == '"'.code) {
                quoted = true
            } else if (c == '{'.code) {
                depth++
            } else if (c == '}'.code) {
                if (--depth == 0) return j + 1
            }
            j++
        }
        return -1
    }
}
