package app.gamenative.utils

import java.util.Locale

/** Reads Wine's semicolon-separated override sets without rewriting the user's spelling or order. */
object WineDllOverrides {
    data class Entry(val names: List<String>)

    fun parse(value: String): List<Entry>? {
        val entries = mutableListOf<Entry>()
        for (part in value.split(';').filter { it.isNotBlank() }) {
            if (part.count { it == '=' } != 1) return null
            val names = part.substringBefore('=').trim().split(Regex("[,\\s]+"))
            if (names.any { it.isBlank() }) return null
            val order = part.substringAfter('=').trim()
            if (order.split(Regex("[,\\s]+")).any {
                    it.lowercase(Locale.ROOT) !in setOf("", "n", "native", "b", "builtin", "d", "disabled")
                }
            ) {
                return null
            }
            entries += Entry(names)
        }
        return entries
    }

    /** Path-specific overrides also count as an explicit choice: do not broaden their scope. */
    fun matches(name: String, dll: String): Boolean {
        val normalized = name.trim().replace('\\', '/').lowercase(Locale.ROOT).removeSuffix(".dll")
        return normalized == "*" || normalized.removePrefix("*").substringAfterLast('/') == dll
    }

    fun mentions(entries: List<Entry>, dll: String): Boolean =
        entries.any { entry -> entry.names.any { matches(it, dll) } }
}
