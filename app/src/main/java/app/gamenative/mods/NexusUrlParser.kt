package app.gamenative.mods

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class NexusModReference(
    val gameDomain: String,
    val modId: Long,
    val fileId: Long? = null,
    val downloadAuthorization: NexusDownloadAuthorization? = null,
)

/**
 * Short-lived, file-specific authorization issued by nexusmods.com in an NXM link.
 *
 * This is deliberately separate from Nexus account authentication. Free Nexus accounts must
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
    internal sealed interface NxmDownloadGrantResult {
        data class Valid(val reference: NexusModReference) : NxmDownloadGrantResult
        data object Expired : NxmDownloadGrantResult
        data object Malformed : NxmDownloadGrantResult
    }

    private val nxmPathPattern = Regex(
        pattern = "^/mods/([1-9][0-9]*)/files/([1-9][0-9]*)$",
        option = RegexOption.IGNORE_CASE,
    )
    private val nexusGameDomainPattern = Regex("^[a-z0-9][a-z0-9-]{0,127}$")

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
        if (uri.isOpaque || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        val gameDomain = uri.host
            ?.lowercase(Locale.US)
            ?.takeIf(nexusGameDomainPattern::matches)
            ?: return null
        val segments = uri.path
            ?.split('/')
            ?.filter(String::isNotBlank)
            ?: return null
        val modsIndex = segments.indexOfFirst { it.equals("mods", ignoreCase = true) }
        if (modsIndex < 0 || modsIndex + 1 >= segments.size) return null
        val modId = segments[modsIndex + 1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val query = parseNxmQuery(uri.rawQuery) ?: return null
        val fileId = if (
            modsIndex + 3 < segments.size &&
            segments[modsIndex + 2].equals("files", ignoreCase = true)
        ) {
            segments[modsIndex + 3].toLongOrNull()?.takeIf { it > 0L }
        } else {
            query.singleValue("file_id")?.toLongOrNull()?.takeIf { it > 0L }
        }
        val downloadAuthorization = query.singleValue("key")
            ?.takeIf { it.isNotBlank() && it.length <= 2048 }
            ?.let { key ->
                val expires = query.singleValue("expires")?.toLongOrNull()?.takeIf { it > 0L }
                    ?: return@let null
                NexusDownloadAuthorization(
                    key = key,
                    expires = expires,
                    userId = query.singleValue("user_id")?.toLongOrNull()?.takeIf { it > 0L },
                )
            }
        return NexusModReference(
            gameDomain = gameDomain,
            modId = modId,
            fileId = fileId,
            downloadAuthorization = downloadAuthorization,
        )
    }

    /**
     * Strictly parses the signed, account-bound NXM capability delivered by Android.
     * Unlike [parse], this requires every field needed for a free-account download.
     */
    internal fun parseNxmDownloadGrant(
        input: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        requireUserId: Boolean = true,
    ): NxmDownloadGrantResult {
        if (input.length > MAX_NXM_URL_LENGTH) return NxmDownloadGrantResult.Malformed
        val uri = runCatching { URI(input) }.getOrNull() ?: return NxmDownloadGrantResult.Malformed
        if (!uri.scheme.equals("nxm", ignoreCase = true)) return NxmDownloadGrantResult.Malformed
        val structure = parseNxmStructure(uri) ?: return NxmDownloadGrantResult.Malformed
        val query = parseNxmQuery(uri.rawQuery) ?: return NxmDownloadGrantResult.Malformed
        if (query.hasInvalidReservedNxmParameters(requireUserId)) return NxmDownloadGrantResult.Malformed

        val key = query.singleValue("key")
            ?.takeIf { value ->
                value.isNotBlank() &&
                    value.length <= MAX_NXM_KEY_LENGTH &&
                    value.none(Char::isWhitespace) &&
                    value.none(Char::isISOControl)
            }
            ?: return NxmDownloadGrantResult.Malformed
        val expires = query.singleValue("expires")
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return NxmDownloadGrantResult.Malformed
        val rawUserId = query.singleValue("user_id")
        val userId = rawUserId?.toLongOrNull()?.takeIf { it > 0L }
        if ((requireUserId || rawUserId != null) && userId == null) {
            return NxmDownloadGrantResult.Malformed
        }
        val reference = NexusModReference(
            gameDomain = structure.gameDomain,
            modId = structure.modId,
            fileId = structure.fileId,
            downloadAuthorization = NexusDownloadAuthorization(
                key = key,
                expires = expires,
                userId = userId,
            ),
        )
        if (expires <= nowEpochSeconds) return NxmDownloadGrantResult.Expired

        return NxmDownloadGrantResult.Valid(reference)
    }

    private data class NxmStructure(
        val gameDomain: String,
        val modId: Long,
        val fileId: Long,
    )

    private fun parseNxmStructure(uri: URI): NxmStructure? {
        if (uri.isOpaque || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
        val gameDomain = uri.host
            ?.lowercase(Locale.US)
            ?.takeIf(nexusGameDomainPattern::matches)
            ?: return null
        val match = uri.rawPath?.let(nxmPathPattern::matchEntire) ?: return null
        val modId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val fileId = match.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
        return NxmStructure(gameDomain, modId, fileId)
    }

    private fun parseNxmQuery(rawQuery: String?): Map<String, List<String>>? {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return runCatching {
            val values = linkedMapOf<String, MutableList<String>>()
            rawQuery.split('&').forEach { part ->
                val separator = part.indexOf('=')
                require(separator > 0)
                val name = decodeNxmQueryComponent(part.substring(0, separator))
                val value = decodeNxmQueryComponent(part.substring(separator + 1))
                values.getOrPut(name) { mutableListOf() }.add(value)
            }
            values.mapValues { (_, entries) -> entries.toList() }
        }.getOrNull()
    }

    /** URLDecoder implements form encoding, so protect literal '+' before percent decoding. */
    private fun decodeNxmQueryComponent(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private fun Map<String, List<String>>.singleValue(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.singleOrNull()

    private fun Map<String, List<String>>.hasInvalidReservedNxmParameters(requireUserId: Boolean): Boolean {
        val counts = RESERVED_NXM_QUERY_PARAMETERS.associateWith { reserved ->
            entries
                .filter { it.key.equals(reserved, ignoreCase = true) }
                .sumOf { it.value.size }
        }
        return counts.getValue("key") != 1 ||
            counts.getValue("expires") != 1 ||
            counts.getValue("user_id") > 1 ||
            (requireUserId && counts.getValue("user_id") != 1)
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

    private const val MAX_NXM_URL_LENGTH = 8192
    private const val MAX_NXM_KEY_LENGTH = 2048
    private val RESERVED_NXM_QUERY_PARAMETERS = setOf("key", "expires", "user_id")
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
