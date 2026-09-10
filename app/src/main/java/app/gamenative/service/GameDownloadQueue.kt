package app.gamenative.service

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized queue for managing downloads across all game services.
 * Ensures only one download is active at a time across Steam, Epic, GOG, and Amazon.
 */
object GameDownloadQueue {

    data class DownloadEntry(
        val gameSource: GameSource,
        val gameId: String,
        val downloadInfo: DownloadInfo
    )

    /**
     * Listener interface for services to handle resume requests.
     */
    interface ResumeListener {
        fun onResumeRequested(gameSource: GameSource, gameId: String)
    }

    private val activeDownloads = ConcurrentHashMap<String, DownloadEntry>()
    private val resumeListeners = ConcurrentHashMap<GameSource, ResumeListener>()

    /**
     * Serializes every queue state transition (pause-all + register, remove + resume).
     * Without it, two concurrent registrations can each scan before either inserts and
     * BOTH stay active, breaking the one-at-a-time contract.
     */
    private val queueLock = Any()

    private fun makeKey(gameSource: GameSource, gameId: String): String {
        return "${gameSource.name}_$gameId"
    }

    /**
     * Register a resume listener for a specific game source.
     * Each service should register its own listener to handle resume requests.
     */
    fun registerResumeListener(gameSource: GameSource, listener: ResumeListener) {
        resumeListeners[gameSource] = listener
        Timber.i("[GameDownloadQueue] Registered resume listener for $gameSource")
        synchronized(queueLock) {
            // A queued entry of this source may have been waiting for this listener
            // (service was restarted while queued). Only resume when nothing else is
            // active, or the one-at-a-time contract breaks.
            if (activeDownloads.values.none { it.downloadInfo.isActive() }) {
                resumeNextLocked()
            }
        }
    }

    /**
     * Unregister a resume listener for a specific game source.
     */
    fun unregisterResumeListener(gameSource: GameSource) {
        resumeListeners.remove(gameSource)
        Timber.i("[GameDownloadQueue] Unregistered resume listener for $gameSource")
    }

    /**
     * Register a new download. This will auto-pause all other active downloads.
     */
    fun registerDownload(
        gameSource: GameSource,
        gameId: String,
        downloadInfo: DownloadInfo
    ) {
        val key = makeKey(gameSource, gameId)

        synchronized(queueLock) {
            // Set queue identifiers on the DownloadInfo so it can unregister itself
            downloadInfo.setQueueIdentifiers(gameSource, gameId)

            // Auto-pause all other active downloads. Skip entries whose transfer is
            // already done and which are only syncing saves (post-install): pausing
            // one kills its finishing job while the entry stays queued, and the later
            // auto-resume re-runs the whole download (verify 1/N back to 100%) even
            // though the game was complete.
            activeDownloads.forEach { (existingKey, entry) ->
                if (existingKey != key && entry.downloadInfo.isActive() && !entry.downloadInfo.isPostInstallSyncing()) {
                    Timber.i("[GameDownloadQueue] Auto-pausing ${entry.gameSource} download for ${entry.gameId}")
                    entry.downloadInfo.pause(message = "Paused for new download", autoPaused = true)
                }
            }

            // Register the new download
            activeDownloads[key] = DownloadEntry(gameSource, gameId, downloadInfo)
            Timber.i("[GameDownloadQueue] Registered ${gameSource} download for $gameId")
        }
    }

    /**
     * Unregister a download when it completes or is cancelled.
     * Automatically resumes the next paused download if available.
     */
    fun unregisterDownload(gameSource: GameSource, gameId: String) {
        val key = makeKey(gameSource, gameId)
        synchronized(queueLock) {
            // Idempotent: both the success path and the failure/cancel paths may call this for
            // the same download. Without the guard the second call would resume ANOTHER paused
            // download and break the one-at-a-time invariant.
            if (activeDownloads.remove(key) == null) {
                return
            }
            Timber.i("[GameDownloadQueue] Unregistered ${gameSource} download for $gameId")

            // Auto-resume the first paused download (if any)
            resumeNextLocked()
        }
    }

    /**
     * Resume the first auto-paused entry whose service has a resume listener installed.
     * Caller must hold [queueLock]. Entries without a listener stay queued — they are
     * retried when that service registers its listener (service restart path).
     */
    private fun resumeNextLocked() {
        val nextDownload = activeDownloads.values.firstOrNull { entry ->
            entry.downloadInfo.wasAutoPaused() && resumeListeners.containsKey(entry.gameSource)
        } ?: return

        Timber.i("[GameDownloadQueue] Auto-resuming ${nextDownload.gameSource} download for ${nextDownload.gameId}")
        val listener = resumeListeners[nextDownload.gameSource] ?: return
        nextDownload.downloadInfo.setAutoResumeCallback {
            listener.onResumeRequested(nextDownload.gameSource, nextDownload.gameId)
        }
        nextDownload.downloadInfo.triggerAutoResume()
    }

    /**
     * Remove every entry belonging to a service being torn down. If the removed set
     * included the only ACTIVE download, advance the queue: removed entries can no
     * longer be selected, so the resume can only land on another (live) source —
     * without it, a queued download would wait forever for an unregister that
     * already happened.
     */
    fun unregisterAllForSource(gameSource: GameSource) {
        synchronized(queueLock) {
            val keys = activeDownloads.filterValues { it.gameSource == gameSource }.keys
            keys.forEach { activeDownloads.remove(it) }
            if (keys.isNotEmpty()) {
                Timber.i("[GameDownloadQueue] Removed ${keys.size} $gameSource queue entr(ies) on service teardown")
            }
            if (activeDownloads.values.none { it.downloadInfo.isActive() }) {
                resumeNextLocked()
            }
        }
    }

    /**
     * Get all currently active downloads across all services.
     */
    fun getActiveDownloads(): Map<String, DownloadEntry> {
        return HashMap(activeDownloads)
    }

    /**
     * Get the count of active downloads.
     */
    fun getActiveDownloadCount(): Int {
        return activeDownloads.count { it.value.downloadInfo.isActive() }
    }

    /**
     * Check if a specific download is registered.
     */
    fun isDownloadRegistered(gameSource: GameSource, gameId: String): Boolean {
        val key = makeKey(gameSource, gameId)
        return activeDownloads.containsKey(key)
    }

    /**
     * Get download info for a specific game.
     */
    fun getDownloadInfo(gameSource: GameSource, gameId: String): DownloadInfo? {
        val key = makeKey(gameSource, gameId)
        return activeDownloads[key]?.downloadInfo
    }
}
