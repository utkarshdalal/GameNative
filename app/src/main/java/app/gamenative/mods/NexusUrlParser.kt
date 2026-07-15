package app.gamenative.mods

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class NexusModReference(
    val gameDomain: String,
    val modId: Long,
    val fileId: Long? = null,
    val downloadAuthorization: NexusDownloadAuthorization? = null,
)

/**
 * Short-lived, file-specific authorization issued by nexusmods.com in an NXM link.
 *
 * This is deliberately separate from the user's API key. Free Nexus accounts must
 * visit the website for each file and return these values to the mod manager.
 */
data class NexusDownloadAuthorization(
    val key: String,
    val expires: Long,
    val userId: Long? = null,
) {
    init {
        require(key.isNotBlank()) { "Nexus download authorization key cannot be blank" }
        require(key.length <= 2048) { "Nexus download authorization key is too long" }
        require(expires > 0L) { "Nexus download authorization expiry must be positive" }
    }

    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
        expires <= nowEpochSeconds

    // Never expose the signed key through logs or an enclosing data class's toString().
    override fun toString(): String =
        "NexusDownloadAuthorization(expires=$expires, userId=$userId, key=<redacted>)"
}

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
        return runCatching {
            when (uri.scheme?.lowercase()) {
                "http", "https" -> parseWebUrl(uri)
                "nxm" -> parseNxmUrl(uri)
                else -> null
            }
        }.getOrNull()
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
        val modId = segments[modsIndex + 1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val fileIdFromPath = if (modsIndex + 3 < segments.size && segments[modsIndex + 2].equals("files", true)) {
            segments[modsIndex + 3].toLongOrNull()?.takeIf { it > 0L }
        } else {
            null
        }
        val fileId = fileIdFromPath ?: parseQuery(uri.rawQuery)["file_id"]?.toLongOrNull()?.takeIf { it > 0L }
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
        val modId = segments[modsIndex + 1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val fileId = if (modsIndex + 3 < segments.size && segments[modsIndex + 2].equals("files", true)) {
            segments[modsIndex + 3].toLongOrNull()?.takeIf { it > 0L }
        } else {
            parseQuery(uri.rawQuery)["file_id"]?.toLongOrNull()?.takeIf { it > 0L }
        }
        val query = parseQuery(uri.rawQuery)
        val downloadAuthorization = query["key"]
            ?.takeIf { it.isNotBlank() && it.length <= 2048 }
            ?.let { key ->
                val expires = query["expires"]?.toLongOrNull()?.takeIf { it > 0L } ?: return@let null
                NexusDownloadAuthorization(
                    key = key,
                    expires = expires,
                    userId = query["user_id"]?.toLongOrNull()?.takeIf { it > 0L },
                )
            }
        return NexusModReference(gameDomain, modId, fileId, downloadAuthorization)
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
        val query = runCatching { NexusUrlParser.parseQuery(uri.rawQuery) }.getOrNull() ?: return null
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
