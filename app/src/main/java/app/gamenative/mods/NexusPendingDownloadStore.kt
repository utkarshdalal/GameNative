package app.gamenative.mods

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists only non-secret request metadata while GameNative hands a free user
 * to the Nexus website. The signed NXM key itself is never written to disk.
 */
object NexusPendingDownloadStore {
    private const val PREFERENCES_NAME = "nexus_pending_downloads"
    private const val PENDING_DOWNLOADS_KEY = "pending_downloads"
    private const val MAX_PENDING_DOWNLOADS = 16
    private val lock = Any()

    fun remember(context: Context, pending: PendingNexusWebsiteDownload) {
        synchronized(lock) {
            val downloads = read(context)
                .filterNot { it.sameFile(pending) }
                .takeLast(MAX_PENDING_DOWNLOADS - 1)
                .toMutableList()
                .apply { add(pending.withoutAuthorization()) }
            write(context, downloads)
        }
    }

    fun restore(context: Context): List<PendingNexusWebsiteDownload> = synchronized(lock) {
        val downloads = read(context)
        write(context, downloads)
        downloads
    }

    fun removeMatching(context: Context, reference: NexusModReference) {
        synchronized(lock) {
            write(context, read(context).filterNot { it.matches(reference) })
        }
    }

    fun removeMatching(
        context: Context,
        appId: String,
        reference: NexusModReference,
        requestId: String? = null,
    ) {
        synchronized(lock) {
            write(
                context,
                read(context).filterNot { pending ->
                    pending.appId == appId &&
                        pending.matches(reference) &&
                        (requestId == null || pending.requestId == requestId)
                },
            )
        }
    }

    private fun read(context: Context): List<PendingNexusWebsiteDownload> {
        val raw = preferences(context).getString(PENDING_DOWNLOADS_KEY, null) ?: return emptyList()
        val now = System.currentTimeMillis() / 1000L
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val pending = array.optJSONObject(index)?.toPendingDownload() ?: continue
                    if (!pending.isPastPendingTtl(now)) {
                        add(pending)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, downloads: List<PendingNexusWebsiteDownload>) {
        val preferences = preferences(context)
        if (downloads.isEmpty()) {
            preferences.edit().remove(PENDING_DOWNLOADS_KEY).apply()
            return
        }
        val array = JSONArray()
        downloads.takeLast(MAX_PENDING_DOWNLOADS).forEach { array.put(it.toJson()) }
        preferences.edit().putString(PENDING_DOWNLOADS_KEY, array.toString()).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun PendingNexusWebsiteDownload.withoutAuthorization(): PendingNexusWebsiteDownload =
        copy(reference = reference.copy(downloadAuthorization = null))

    // NXM callbacks contain no app ID, so only the newest request for an exact file can be routed safely.
    private fun PendingNexusWebsiteDownload.sameFile(other: PendingNexusWebsiteDownload): Boolean =
        matches(other.reference)

    private fun PendingNexusWebsiteDownload.matches(other: NexusModReference): Boolean =
        reference.gameDomain.equals(other.gameDomain, ignoreCase = true) &&
            reference.modId == other.modId &&
            file.fileId == other.fileId

    private fun PendingNexusWebsiteDownload.toJson(): JSONObject =
        JSONObject()
            .put("appId", appId)
            .put("gameDomain", reference.gameDomain)
            .put("modId", reference.modId)
            .put("fileId", file.fileId)
            .put("modName", modInfo.name.take(4096))
            .put("modSummary", modInfo.summary.take(16_384))
            .put("modVersion", modInfo.version.take(1024))
            .put("fileName", file.fileName.take(4096))
            .put("fileDisplayName", file.name.take(4096))
            .put("fileVersion", file.version.take(1024))
            .put("fileSizeBytes", file.sizeBytes)
            .put("uploadedTimestamp", file.uploadedTimestamp)
            .put("createdAtEpochSeconds", createdAtEpochSeconds)
            .apply {
                nexusUserId?.let { put("nexusUserId", it) }
                requestId?.let { put("requestId", it) }
            }

    private fun JSONObject.toPendingDownload(): PendingNexusWebsiteDownload? {
        val appId = optString("appId").takeIf(String::isNotBlank) ?: return null
        val gameDomain = optString("gameDomain").takeIf(String::isNotBlank) ?: return null
        val modId = optLong("modId").takeIf { it > 0L } ?: return null
        val fileId = optLong("fileId").takeIf { it > 0L } ?: return null
        val createdAt = optLong("createdAtEpochSeconds").takeIf { it > 0L } ?: return null
        return PendingNexusWebsiteDownload(
            appId = appId,
            reference = NexusModReference(gameDomain, modId, fileId),
            modInfo = NexusModInfo(
                modId = modId,
                name = optString("modName").ifBlank { "Nexus mod $modId" },
                summary = optString("modSummary"),
                version = optString("modVersion"),
            ),
            file = NexusModFile(
                fileId = fileId,
                name = optString("fileDisplayName").ifBlank { optString("fileName") },
                version = optString("fileVersion"),
                fileName = optString("fileName").ifBlank { "mod_$fileId" },
                sizeBytes = optLong("fileSizeBytes").coerceAtLeast(0L),
                uploadedTimestamp = optLong("uploadedTimestamp").coerceAtLeast(0L),
            ),
            nexusUserId = optLong("nexusUserId").takeIf { it > 0L },
            requestId = optString("requestId").takeIf(String::isNotBlank),
            createdAtEpochSeconds = createdAt,
        )
    }
}
