package app.gamenative.mods

import java.security.MessageDigest
import java.util.Base64
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

data class BrowserFirstNexusWebsiteDownload(
    val appId: String,
    val reference: NexusModReference,
)

sealed interface NexusNxmSubmission {
    data class Expected(
        val appId: String,
        val reference: NexusModReference,
    ) : NexusNxmSubmission

    data class BrowserFirst(
        val reference: NexusModReference,
    ) : NexusNxmSubmission

    data object Expired : NexusNxmSubmission
    data object NoActiveTarget : NexusNxmSubmission
    data object AmbiguousTarget : NexusNxmSubmission
    data object Replayed : NexusNxmSubmission
    data object Malformed : NexusNxmSubmission
    data object DeliveryFailed : NexusNxmSubmission
}

internal class NexusNxmReceiverRegistration internal constructor(
    internal val token: Any,
) {
    fun unregister() {
        NexusDownloadLinkInbox.unregisterReceiver(this)
    }
}

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
    private const val MAX_CONSUMED_GRANT_FINGERPRINTS = 64
    internal const val PENDING_DOWNLOAD_TTL_SECONDS = 20L * 60L

    private data class FileKey(
        val gameDomain: String,
        val modId: Long,
        val fileId: Long,
    )

    private val pendingLock = Any()
    private val callbackChannels = mutableMapOf<String, Channel<AuthorizedNexusWebsiteDownload>>()
    private val browserFirstChannels = mutableMapOf<String, Channel<BrowserFirstNexusWebsiteDownload>>()
    private val pendingWebsiteDownloads = linkedMapOf<FileKey, PendingNexusWebsiteDownload>()
    private val activeReceivers = mutableMapOf<Any, String>()
    private val consumedGrantFingerprints = linkedMapOf<String, Long>()

    /**
     * Returns callbacks routed to one GameNative library item. A callback is only
     * admitted after it matches the exact file for which that item opened Nexus.
     * This keeps another open game's dialog from consuming a one-use grant.
     */
    fun callbacksFor(appId: String): Flow<AuthorizedNexusWebsiteDownload> =
        synchronized(pendingLock) { callbackChannelFor(appId) }.receiveAsFlow()

    fun browserFirstCallbacksFor(appId: String): Flow<BrowserFirstNexusWebsiteDownload> =
        synchronized(pendingLock) { browserFirstChannelFor(appId) }.receiveAsFlow()

    /** Marks a Manage Nexus Mods dialog as an eligible browser-first destination. */
    internal fun registerReceiver(appId: String): NexusNxmReceiverRegistration {
        require(appId.isNotBlank())
        return synchronized(pendingLock) {
            val registration = NexusNxmReceiverRegistration(Any())
            activeReceivers[registration.token] = appId
            registration
        }
    }

    internal fun unregisterReceiver(registration: NexusNxmReceiverRegistration) {
        synchronized(pendingLock) {
            val appId = activeReceivers.remove(registration.token) ?: return@synchronized
            if (appId !in activeReceivers.values) {
                browserFirstChannels.remove(appId)?.let { channel ->
                    while (channel.tryReceive().isSuccess) {
                        // A later dialog must never receive a grant sent to a closed one.
                    }
                }
            }
        }
    }

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

    /** Legacy expected-only entry point retained for tests and app-initiated hand-offs. */
    fun submit(rawUrl: String): NexusModReference? =
        (submitInternal(rawUrl, allowBrowserFirst = false) as? NexusNxmSubmission.Expected)?.reference

    /** Handles an Android NXM intent, including a safely targeted browser-first hand-off. */
    fun submitIntent(rawUrl: String): NexusNxmSubmission =
        submitInternal(rawUrl, allowBrowserFirst = true)

    private fun submitInternal(rawUrl: String, allowBrowserFirst: Boolean): NexusNxmSubmission {
        val reference = when (
            val parsed = NexusUrlParser.parseNxmDownloadGrant(rawUrl, requireUserId = false)
        ) {
            is NexusUrlParser.NxmDownloadGrantResult.Valid -> parsed.reference
            NexusUrlParser.NxmDownloadGrantResult.Expired -> return NexusNxmSubmission.Expired
            NexusUrlParser.NxmDownloadGrantResult.Malformed -> return NexusNxmSubmission.Malformed
        }
        val authorization = reference.downloadAuthorization ?: return NexusNxmSubmission.Malformed
        val key = reference.fileKey()
        return synchronized(pendingLock) {
            val nowEpochSeconds = System.currentTimeMillis() / 1000L
            removeExpiredPendingDownloads(nowEpochSeconds)
            removeExpiredConsumedGrants(nowEpochSeconds)
            val fingerprint = reference.grantFingerprint(authorization)
            if (consumedGrantFingerprints.containsKey(fingerprint)) {
                return@synchronized NexusNxmSubmission.Replayed
            }
            if (consumedGrantFingerprints.size >= MAX_CONSUMED_GRANT_FINGERPRINTS) {
                return@synchronized NexusNxmSubmission.DeliveryFailed
            }

            val pending = pendingWebsiteDownloads.remove(key)
            if (pending != null) {
                val delivery = callbackChannelFor(pending.appId).trySend(
                    AuthorizedNexusWebsiteDownload(pending, reference),
                )
                if (delivery.isFailure) {
                    pendingWebsiteDownloads.putIfAbsent(key, pending)
                    return@synchronized NexusNxmSubmission.DeliveryFailed
                }
                rememberConsumedGrant(fingerprint, authorization)
                return@synchronized NexusNxmSubmission.Expected(pending.appId, reference)
            }

            if (!allowBrowserFirst) return@synchronized NexusNxmSubmission.NoActiveTarget
            if (authorization.userId?.takeIf { it > 0L } == null) {
                return@synchronized NexusNxmSubmission.Malformed
            }
            val activeAppIds = activeReceivers.values.distinct()
            if (activeAppIds.isEmpty()) return@synchronized NexusNxmSubmission.NoActiveTarget
            if (activeAppIds.size != 1) return@synchronized NexusNxmSubmission.AmbiguousTarget
            val appId = activeAppIds.single()
            val delivery = browserFirstChannelFor(appId).trySend(
                BrowserFirstNexusWebsiteDownload(appId, reference),
            )
            if (delivery.isFailure) return@synchronized NexusNxmSubmission.DeliveryFailed
            rememberConsumedGrant(fingerprint, authorization)
            NexusNxmSubmission.BrowserFirst(reference)
        }
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

    /** Clears account-bound website expectations and any already buffered one-use grants. */
    fun clearAll() {
        synchronized(pendingLock) {
            pendingWebsiteDownloads.clear()
            callbackChannels.values.forEach { channel ->
                while (channel.tryReceive().isSuccess) {
                    // Drain without closing: active dialog collectors remain usable after reconnect.
                }
            }
            browserFirstChannels.values.forEach { channel ->
                while (channel.tryReceive().isSuccess) {
                    // Browser-first grants are memory-only and account-bound.
                }
            }
        }
    }

    private fun callbackChannelFor(appId: String): Channel<AuthorizedNexusWebsiteDownload> =
        callbackChannels.getOrPut(appId) { Channel(MAX_PENDING_DOWNLOADS) }

    private fun browserFirstChannelFor(appId: String): Channel<BrowserFirstNexusWebsiteDownload> =
        browserFirstChannels.getOrPut(appId) { Channel(MAX_PENDING_DOWNLOADS) }

    private fun removeExpiredPendingDownloads(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L) {
        pendingWebsiteDownloads.entries.removeAll { (_, pending) ->
            pending.isPastPendingTtl(nowEpochSeconds)
        }
    }

    private fun PendingNexusWebsiteDownload.fileKey(): FileKey =
        FileKey(reference.gameDomain.lowercase(), reference.modId, file.fileId)

    private fun NexusModReference.fileKey(): FileKey =
        FileKey(gameDomain.lowercase(), modId, requireNotNull(fileId))

    private fun removeExpiredConsumedGrants(nowEpochSeconds: Long) {
        consumedGrantFingerprints.entries.removeAll { (_, expiresAt) -> expiresAt <= nowEpochSeconds }
    }

    private fun rememberConsumedGrant(fingerprint: String, authorization: NexusDownloadAuthorization) {
        consumedGrantFingerprints[fingerprint] = authorization.expires
    }

    private fun NexusModReference.grantFingerprint(authorization: NexusDownloadAuthorization): String {
        val input = buildString {
            append(gameDomain.lowercase())
            append('\u0000')
            append(modId)
            append('\u0000')
            append(requireNotNull(fileId))
            append('\u0000')
            append(authorization.expires)
            append('\u0000')
            append(authorization.userId)
            append('\u0000')
            append(authorization.key)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(digest)
    }

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
