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

        // Set queue identifiers on the DownloadInfo so it can unregister itself
        downloadInfo.setQueueIdentifiers(gameSource, gameId)

        // Auto-pause all other active downloads
        activeDownloads.forEach { (existingKey, entry) ->
            if (existingKey != key && entry.downloadInfo.isActive()) {
                Timber.i("[GameDownloadQueue] Auto-pausing ${entry.gameSource} download for ${entry.gameId}")
                entry.downloadInfo.pause(message = "Paused for new download", autoPaused = true)
            }
        }

        // Register the new download
        activeDownloads[key] = DownloadEntry(gameSource, gameId, downloadInfo)
        Timber.i("[GameDownloadQueue] Registered ${gameSource} download for $gameId")
    }

    /**
     * Unregister a download when it completes or is cancelled.
     * Automatically resumes the next paused download if available.
     */
    fun unregisterDownload(gameSource: GameSource, gameId: String) {
        val key = makeKey(gameSource, gameId)
        activeDownloads.remove(key)
        Timber.i("[GameDownloadQueue] Unregistered ${gameSource} download for $gameId")

        // Auto-resume the first paused download (if any)
        val nextDownload = activeDownloads.values.firstOrNull { entry ->
            entry.downloadInfo.wasAutoPaused()
        }

        if (nextDownload != null) {
            Timber.i("[GameDownloadQueue] Auto-resuming ${nextDownload.gameSource} download for ${nextDownload.gameId}")
            val listener = resumeListeners[nextDownload.gameSource]
            if (listener != null) {
                nextDownload.downloadInfo.setAutoResumeCallback {
                    listener.onResumeRequested(nextDownload.gameSource, nextDownload.gameId)
                }
                nextDownload.downloadInfo.triggerAutoResume()
            } else {
                Timber.w("[GameDownloadQueue] No resume listener registered for ${nextDownload.gameSource}")
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
