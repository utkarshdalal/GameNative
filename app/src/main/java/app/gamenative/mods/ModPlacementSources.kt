package app.gamenative.mods

import org.json.JSONArray

object ModPlacementSources {
    fun decode(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        if (!trimmed.startsWith("[")) {
            val normalized = normalize(trimmed)
            return if (normalized.isBlank()) emptyList() else listOf(normalized)
        }

        val parsed = runCatching {
            val array = JSONArray(trimmed)
            buildList {
                for (index in 0 until array.length()) {
                    val path = normalize(array.optString(index))
                    if (path.isNotBlank()) add(path)
                }
            }
        }.getOrElse {
            val normalized = normalize(trimmed)
            return if (normalized.isBlank()) emptyList() else listOf(normalized)
        }

        return parsed.distinct()
    }

    fun encode(paths: Collection<String>): String {
        val normalized = paths
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
        return when (normalized.size) {
            0 -> ""
            1 -> normalized.single()
            else -> JSONArray().apply {
                normalized.forEach(::put)
            }.toString()
        }
    }

    fun normalize(path: String): String =
        path.trim()
            .trim('/', '\\')
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .joinToString("/")
}
