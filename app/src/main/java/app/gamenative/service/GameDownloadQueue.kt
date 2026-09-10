package app.gamenative.service

import android.content.Context
import app.gamenative.data.DownloadInfo
import app.gamenative.data.GameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // ── Automatic retry of transient failures ────────────────────────────────
    // A download that fails with a TRANSIENT error (timeout, reset, 5xx/429)
    // is restarted automatically up to MAX_AUTO_RETRIES times with backoff,
    // keeping its queue slot. Permanent errors (404/401/403, no depot key,
    // disk full, parse/decrypt, …) fail immediately, as before.
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val retryAttempts = ConcurrentHashMap<String, Int>()
    private val retryJobs = ConcurrentHashMap<String, Job>()

    private const val MAX_AUTO_RETRIES = 2
    private const val RETRY_BACKOFF_FIRST_MS = 30_000L
    private const val RETRY_BACKOFF_LATER_MS = 120_000L

    /**
     * Classify a failure message. Default is NOT transient: an unknown error
     * fails fast instead of looping. Permanent markers are checked first so a
     * message containing both (e.g. "non-200 HTTP status (404)") fails fast.
     */
    fun isTransientFailure(message: String?): Boolean {
        val msg = message?.lowercase() ?: return false
        val permanent = listOf(
            "(401", "(403", "(404", " 401", " 403", " 404",
            "no depot key", "no manifest gid", "unsafe path",
            "no space", "disk full", "enospc",
            "decrypt", "parse failed", "manifest parse",
            "cancelled", "canceled",
        )
        if (permanent.any { msg.contains(it) }) return false
        val transient = listOf(
            "timed out", "timeout", "connection reset", "connection refused",
            "connection aborted", "broken pipe", "unexpected eof", "eof while",
            "dns", "unreachable", "network", "temporarily", "stalled",
            "(429", "(500", "(502", "(503", "(504",
            " 429", " 500", " 502", " 503", " 504",
            "no process-pool verdict", "no cdn servers",
        )
        return transient.any { msg.contains(it) }
    }

    private fun retryBackoffMs(attempt: Int): Long {
        return if (attempt <= 1) RETRY_BACKOFF_FIRST_MS else RETRY_BACKOFF_LATER_MS
    }

    /**
     * Report a download failure. Returns true when an automatic retry was
     * scheduled — the caller must then KEEP the queue entry (and its active-map
     * entry, so the UI shows the download as Queued) and skip its own
     * failure/unregister handling. Returns false for permanent failures and
     * after [MAX_AUTO_RETRIES] attempts; the caller then fails the download
     * exactly as before (which unregisters and advances the queue).
     *
     * Must be called BEFORE the store removes its active-map entry: the retry
     * marker sets wasAutoPaused, which is what keeps that entry.
     */
    fun reportFailure(gameSource: GameSource, gameId: String, errorMessage: String?): Boolean {
        val key = makeKey(gameSource, gameId)
        synchronized(queueLock) {
            val entry = activeDownloads[key] ?: return false
            if (!isTransientFailure(errorMessage)) {
                retryAttempts.remove(key)
                return false
            }
            val attempt = (retryAttempts[key] ?: 0) + 1
            val listener = resumeListeners[gameSource]
            if (attempt > MAX_AUTO_RETRIES || listener == null) {
                Timber.w("[GameDownloadQueue] Not retrying $gameSource $gameId (attempt $attempt): $errorMessage")
                retryAttempts.remove(key)
                return false
            }
            retryAttempts[key] = attempt
            val backoffMs = retryBackoffMs(attempt)
            Timber.i("[GameDownloadQueue] Transient failure for $gameSource $gameId; auto-retry $attempt/$MAX_AUTO_RETRIES in ${backoffMs}ms: $errorMessage")
            entry.downloadInfo.markQueuedForRetry("Download interrupted — retrying in ${backoffMs / 1000}s ($attempt/$MAX_AUTO_RETRIES)")
            entry.downloadInfo.setAutoResumeCallback {
                listener.onResumeRequested(gameSource, gameId)
            }
            val job = retryScope.launch {
                delay(backoffMs)
                synchronized(queueLock) {
                    retryJobs.remove(key)
                    // Fire only if the entry is still registered (user may have
                    // cancelled during the backoff) and nothing else is active.
                    // Otherwise it stays queued and normal progression resumes it.
                    if (activeDownloads[key] == entry &&
                        activeDownloads.values.none { it.downloadInfo.isActive() }
                    ) {
                        Timber.i("[GameDownloadQueue] Auto-retrying $gameSource download for $gameId")
                        entry.downloadInfo.triggerAutoResume()
                    }
                }
            }
            retryJobs[key] = job
            return true
        }
    }

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

            // A fresh registration supersedes any pending retry of the previous
            // entry for this key. Attempt history is intentionally KEPT: the
            // auto-retry's own resume listener re-registers through this path,
            // and resetting here would retry forever.
            retryJobs.remove(key)?.cancel()

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
            // Terminal state for this download (success, cancel, permanent
            // failure): clear retry bookkeeping and any pending backoff job.
            retryAttempts.remove(key)
            retryJobs.remove(key)?.cancel()

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
            keys.forEach {
                activeDownloads.remove(it)
                retryAttempts.remove(it)
                retryJobs.remove(it)?.cancel()
            }
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
