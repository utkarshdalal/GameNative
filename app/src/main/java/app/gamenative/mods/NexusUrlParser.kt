package app.gamenative.mods

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class NexusModReference(
    val gameDomain: String,
    val modId: Long,
    val fileId: Long? = null,
)

data class NexusCollectionReference(
    val gameDomain: String,
    val slug: String,
    val revision: Int? = null,
)

object NexusUrlParser {
    fun parse(input: String): NexusModReference? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> parseWebUrl(uri)
            "nxm" -> parseNxmUrl(uri)
            else -> null
        }
    }

    private fun parseWebUrl(uri: URI): NexusModReference? {
        val host = uri.host?.lowercase() ?: return null
        if (host != "nexusmods.com" && !host.endsWith(".nexusmods.com")) return null

        val segments = uri.path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: return null
        val modsIndex = segments.indexOfFirst { it.equals("mods", ignoreCase = true) }
        if (modsIndex <= 0 || modsIndex + 1 >= segments.size) return null

        val gameDomain = segments[modsIndex - 1].lowercase()
        val modId = segments[modsIndex + 1].toLongOrNull() ?: return null
        val fileIdFromPath = if (modsIndex + 3 < segments.size && segments[modsIndex + 2].equals("files", true)) {
            segments[modsIndex + 3].toLongOrNull()
        } else {
            null
        }
        val fileId = fileIdFromPath ?: parseQuery(uri.rawQuery)["file_id"]?.toLongOrNull()
        return NexusModReference(gameDomain, modId, fileId)
    }

    private fun parseNxmUrl(uri: URI): NexusModReference? {
        val gameDomain = uri.host?.lowercase() ?: return null
        val segments = uri.path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: return null
        val modsIndex = segments.indexOfFirst { it.equals("mods", ignoreCase = true) }
        if (modsIndex < 0 || modsIndex + 1 >= segments.size) return null
        val modId = segments[modsIndex + 1].toLongOrNull() ?: return null
        val fileId = if (modsIndex + 3 < segments.size && segments[modsIndex + 2].equals("files", true)) {
            segments[modsIndex + 3].toLongOrNull()
        } else {
            parseQuery(uri.rawQuery)["file_id"]?.toLongOrNull()
        }
        return NexusModReference(gameDomain, modId, fileId)
    }

    internal fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = decode(part.substring(0, idx))
                val value = decode(part.substring(idx + 1))
                key to value
            }
            .toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}

object NexusCollectionUrlParser {
    fun parse(input: String): NexusCollectionReference? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "nexusmods.com" && !host.endsWith(".nexusmods.com")) return null

        val segments = uri.path
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?: return null
        val collectionsIndex = segments.indexOfFirst { it.equals("collections", ignoreCase = true) }
        if (collectionsIndex <= 0 || collectionsIndex + 1 >= segments.size) return null

        val gameDomain = segments[collectionsIndex - 1].lowercase()
        val slug = segments[collectionsIndex + 1].lowercase()
        val revisionFromPath = segments
            .drop(collectionsIndex + 2)
            .windowed(size = 2, step = 1, partialWindows = false)
            .firstOrNull { it.first().equals("revisions", ignoreCase = true) || it.first().equals("revision", ignoreCase = true) }
            ?.getOrNull(1)
            ?.toIntOrNull()
        val query = NexusUrlParser.parseQuery(uri.rawQuery)
        val revisionFromQuery = query["revision"]?.toIntOrNull()
            ?: query["revision_id"]?.toIntOrNull()
            ?: query["rev"]?.toIntOrNull()

        return NexusCollectionReference(
            gameDomain = gameDomain,
            slug = slug,
            revision = revisionFromPath ?: revisionFromQuery,
        )
    }
}
