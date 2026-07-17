package app.gamenative.mods

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

data class PendingNexusWebsiteDownload(
    val appId: String,
    val reference: NexusModReference,
    val modInfo: NexusModInfo,
    val file: NexusModFile,
    val nexusUserId: Long? = null,
    val requestId: String? = null,
    val createdAtEpochSeconds: Long = System.currentTimeMillis() / 1000L,
)

data class AuthorizedNexusWebsiteDownload(
    val pending: PendingNexusWebsiteDownload,
    val reference: NexusModReference,
)

internal fun PendingNexusWebsiteDownload.isPastPendingTtl(
    nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
): Boolean = nowEpochSeconds - createdAtEpochSeconds >= NexusDownloadLinkInbox.PENDING_DOWNLOAD_TTL_SECONDS

/**
 * Bridges Android's NXM intent callback to the currently open Nexus dialog.
 *
 * The callback channel is process-local on purpose: signed download grants are
 * short-lived secrets and must not be persisted. Only the expected app/file tuple
 * is stored separately so MainActivity can route a cold NXM intent after process death.
 */
object NexusDownloadLinkInbox {
    private const val MAX_PENDING_DOWNLOADS = 16
    internal const val PENDING_DOWNLOAD_TTL_SECONDS = 20L * 60L

    private data class FileKey(
        val gameDomain: String,
        val modId: Long,
        val fileId: Long,
    )

    private val pendingLock = Any()
    private val callbackChannels = mutableMapOf<String, Channel<AuthorizedNexusWebsiteDownload>>()
    private val pendingWebsiteDownloads = linkedMapOf<FileKey, PendingNexusWebsiteDownload>()

    /**
     * Returns callbacks routed to one GameNative library item. A callback is only
     * admitted after it matches the exact file for which that item opened Nexus.
     * This keeps another open game's dialog from consuming a one-use grant.
     */
    fun callbacksFor(appId: String): Flow<AuthorizedNexusWebsiteDownload> =
        synchronized(pendingLock) { callbackChannelFor(appId) }.receiveAsFlow()

    fun expect(
        download: PendingNexusWebsiteDownload,
        onAccepted: () -> Unit = {},
    ): Boolean =
        synchronized(pendingLock) {
            removeExpiredPendingDownloads()
            val key = download.fileKey()
            val existing = pendingWebsiteDownloads[key]
            val canReplaceManualRequest = existing?.appId == download.appId &&
                existing.requestId == null
            if (
                (existing != null && !canReplaceManualRequest) ||
                (existing == null && pendingWebsiteDownloads.size >= MAX_PENDING_DOWNLOADS)
            ) {
                return@synchronized false
            }
            onAccepted()
            pendingWebsiteDownloads[key] = download
            true
        }

    fun submit(rawUrl: String): NexusModReference? {
        if (rawUrl.length > 8192) return null
        val reference = NexusUrlParser.parse(rawUrl)
            ?.takeIf {
                it.modId > 0L &&
                    (it.fileId ?: 0L) > 0L &&
                    it.downloadAuthorization != null
            }
            ?: return null
        val key = reference.fileKey()
        val (callbackChannel, pending) = synchronized(pendingLock) {
            removeExpiredPendingDownloads()
            val pending = pendingWebsiteDownloads.remove(key) ?: return null
            callbackChannelFor(pending.appId) to pending
        }
        val delivery = callbackChannel.trySend(AuthorizedNexusWebsiteDownload(pending, reference))
        if (delivery.isFailure) {
            synchronized(pendingLock) { pendingWebsiteDownloads.putIfAbsent(key, pending) }
            return null
        }
        return reference
    }

    fun cancelExpected(
        appId: String,
        reference: NexusModReference,
        requestId: String? = null,
    ) {
        synchronized(pendingLock) {
            val key = reference.fileKey()
            val pending = pendingWebsiteDownloads[key] ?: return@synchronized
            if (
                pending.appId == appId &&
                (requestId == null || pending.requestId == requestId)
            ) {
                pendingWebsiteDownloads.remove(key)
            }
        }
    }

    private fun callbackChannelFor(appId: String): Channel<AuthorizedNexusWebsiteDownload> =
        callbackChannels.getOrPut(appId) { Channel(MAX_PENDING_DOWNLOADS) }

    private fun removeExpiredPendingDownloads(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L) {
        pendingWebsiteDownloads.entries.removeAll { (_, pending) ->
            pending.isPastPendingTtl(nowEpochSeconds)
        }
    }

    private fun PendingNexusWebsiteDownload.fileKey(): FileKey =
        FileKey(reference.gameDomain.lowercase(), reference.modId, file.fileId)

    private fun NexusModReference.fileKey(): FileKey =
        FileKey(gameDomain.lowercase(), modId, requireNotNull(fileId))

    fun websiteDownloadUrl(reference: NexusModReference, fileId: Long): String =
        "https://www.nexusmods.com".toHttpUrl().newBuilder()
            .addPathSegment(reference.gameDomain)
            .addPathSegment("mods")
            .addPathSegment(reference.modId.toString())
            .addQueryParameter("tab", "files")
            .addQueryParameter("file_id", fileId.toString())
            .addQueryParameter("nmm", "1")
            .build()
            .toString()
}
