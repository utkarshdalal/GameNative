package app.gamenative.api

import org.json.JSONObject

internal fun JSONObject.cardText(name: String): String? =
    if (!has(name) || isNull(name)) {
        null
    } else {
        when (val value = opt(name)) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

internal fun cardClean(value: String?, max: Int): String? =
    value?.trim()?.take(max)?.ifEmpty { null }

data class SupportFilesRequest(
    val requestId: String,
    val note: String?,
    val files: List<Item>,
    val applicable: Boolean,
) {
    data class Item(
        val path: String,
        val why: String?,
        val maxBytes: Long,
        val list: Boolean = false,
        val depth: Int = 1,
    )

    companion object {
        const val MAX_FILES = 8
        const val MAX_LISTINGS = 6
        const val MAX_DEPTH = 2
        const val INSTALL_ROOT = "."
        const val MAX_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_NOTE = 300
        private const val MAX_WHY = 200

        private val PATH = Regex("""^[^\\/:*?"<>|][^:*?"<>|]{0,255}$""")
        private val ID = Regex("^[A-Za-z0-9-]{1,64}$")

        fun isValidId(value: String?): Boolean = value != null && ID.matches(value)

        fun isSafePath(path: String): Boolean {
            if (!PATH.matches(path)) return false
            if (path.any { it.code < 0x20 }) return false
            val segments = path.split('/', '\\')
            return segments.none { it.isEmpty() || it == "." || it == ".." }
        }

        fun parse(json: JSONObject?): SupportFilesRequest? {
            if (json == null) return null
            if (json.optInt("v", 0) != 1) return null
            val requestId = json.cardText("requestId")
            if (!isValidId(requestId)) return null
            val array = json.optJSONArray("files") ?: return null
            if (array.length() == 0) return null
            var applicable = array.length() <= MAX_FILES
            val files = mutableListOf<Item>()
            for (i in 0 until minOf(array.length(), MAX_FILES)) {
                val item = array.optJSONObject(i)
                val path = item?.cardText("path")
                val list = item?.optBoolean("list", false) == true
                if (item == null || path == null || !(isSafePath(path) || (list && path == INSTALL_ROOT))) {
                    applicable = false
                    continue
                }
                if (list) {
                    val depth = item.optInt("depth", 1)
                    if (depth !in 1..MAX_DEPTH) applicable = false
                    files += Item(
                        path = path,
                        why = cardClean(item.cardText("why"), MAX_WHY),
                        maxBytes = 0L,
                        list = true,
                        depth = depth.coerceIn(1, MAX_DEPTH),
                    )
                    continue
                }
                val maxBytes = if (item.has("maxBytes") && !item.isNull("maxBytes")) item.optLong("maxBytes", -1L) else MAX_BYTES
                if (maxBytes <= 0) applicable = false
                files += Item(
                    path = path,
                    why = cardClean(item.cardText("why"), MAX_WHY),
                    maxBytes = maxBytes.coerceIn(0L, MAX_BYTES),
                )
            }
            if (files.isEmpty()) return null
            if (files.groupBy { it.path.lowercase() }.any { it.value.size > 1 }) applicable = false
            if (files.count { it.list } > MAX_LISTINGS) applicable = false
            return SupportFilesRequest(requestId!!, cardClean(json.cardText("note"), MAX_NOTE), files, applicable)
        }
    }
}
