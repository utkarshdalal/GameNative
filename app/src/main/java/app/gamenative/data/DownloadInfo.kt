package app.gamenative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class DownloadInfo(
    val jobCount: Int = 1,
) {
    private var downloadJob: Job? = null
    private val downloadProgressListeners = mutableListOf<((Float) -> Unit)>()
    private val progresses: Array<Float> = Array(jobCount) { 0f }

    private val weights    = FloatArray(jobCount) { 1f }     // ⇐ new
    private var weightSum  = jobCount.toFloat()

    // === Bytes / speed tracking for more stable ETA ===
    private var totalExpectedBytes: Long = 0L
    private var bytesDownloaded: Long = 0L
    private var persistencePath: String? = null

    private data class SpeedSample(val timeMs: Long, val bytes: Long)

    private val speedSamples = CopyOnWriteArrayList<SpeedSample>()
    private var emaSpeedBytesPerSec: Double = 0.0
    private var hasEmaSpeed: Boolean = false
    private var isActive: Boolean = true
    private val statusMessage = MutableStateFlow<String?>(null)

    fun cancel() {
        // Persist the most recent progress so a resume can pick up where it left off.
        persistProgressSnapshot()
        // Mark as inactive and clear speed tracking so a future resume
        // does not use stale samples.
        setActive(false)
        resetSpeedTracking()
        downloadJob?.cancel(CancellationException("Cancelled by user"))
    }

    fun setDownloadJob(job: Job) {
        downloadJob = job
    }

    fun getProgress(): Float {
        // Always use bytes-based progress when available for accuracy
        if (totalExpectedBytes > 0L) {
            val bytesProgress = (bytesDownloaded.toFloat() / totalExpectedBytes.toFloat()).coerceIn(0f, 1f)
            return bytesProgress
        }

        // Fallback to depot-based progress only if we don't have byte tracking
        var total = 0f
        for (i in progresses.indices) {
            total += progresses[i] * weights[i]   // weight each depot
        }
        return if (weightSum == 0f) 0f else total / weightSum
    }


    fun setProgress(amount: Float, jobIndex: Int = 0) {
        progresses[jobIndex] = amount
        emitProgressChange()
    }

    fun setWeight(jobIndex: Int, weightBytes: Long) {        // tiny helper
        weights[jobIndex] = weightBytes.toFloat()
        weightSum = weights.sum()
    }

    // --- Bytes / speed / ETA helpers ---

    fun setTotalExpectedBytes(bytes: Long) {
        totalExpectedBytes = if (bytes < 0L) 0L else bytes
    }

    /**
     * Initialize bytesDownloaded with a persisted value (used on resume).
     */
    fun initializeBytesDownloaded(value: Long) {
        bytesDownloaded = if (value < 0L) 0L else value
    }

    /**
     * Record that [deltaBytes] have just been downloaded at [timestampMs].
     * This is used to derive recent download speed over a sliding window.
     */
    fun setPersistencePath(appDirPath: String?) {
        persistencePath = appDirPath
    }

    fun persistProgressSnapshot() {
        persistencePath?.let { persistBytesDownloaded(it) }
    }

    fun updateBytesDownloaded(deltaBytes: Long, timestampMs: Long = System.currentTimeMillis()) {
        if (!isActive) return
        if (deltaBytes <= 0L) {
            // Still record a sample to advance the time window, but do not change the count.
            addSpeedSample(timestampMs)
            return
        }

        bytesDownloaded += deltaBytes
        if (bytesDownloaded < 0L) {
            bytesDownloaded = 0L
        }
        addSpeedSample(timestampMs)
    }

    fun updateStatusMessage(message: String?) {
        statusMessage.value = message
    }

    fun getStatusMessageFlow(): StateFlow<String?> = statusMessage

    private fun addSpeedSample(timestampMs: Long) {
        speedSamples.add(SpeedSample(timestampMs, bytesDownloaded))
        trimOldSamples(timestampMs)
    }

    private fun trimOldSamples(nowMs: Long, windowMs: Long = 30_000L) {
        val cutoff = nowMs - windowMs
        while (speedSamples.isNotEmpty() && speedSamples.first().timeMs < cutoff) {
            speedSamples.removeAt(0)
        }
    }

    fun resetSpeedTracking() {
        speedSamples.clear()
        emaSpeedBytesPerSec = 0.0
        hasEmaSpeed = false
    }

    fun setActive(active: Boolean) {
        isActive = active
        if (!active) {
            resetSpeedTracking()
        }
    }

    fun isActive(): Boolean = isActive

    /**
     * Returns the total expected bytes for the download.
     */
    fun getTotalExpectedBytes(): Long = totalExpectedBytes

    /**
     * Returns the cumulative bytes downloaded so far.
     */
    fun getBytesDownloaded(): Long = bytesDownloaded

    /**
     * Returns a pair of (downloaded bytes, total expected bytes).
     * Returns (0, 0) if total expected bytes is 0 or not yet set.
     */
    fun getBytesProgress(): Pair<Long, Long> {
        return if (totalExpectedBytes > 0L) {
            bytesDownloaded.coerceAtMost(totalExpectedBytes) to totalExpectedBytes
        } else {
            0L to 0L
        }
    }

    /**
     * Returns an ETA in milliseconds based on recent download speed, or null if
     * there is not enough information yet (e.g. just started) or download is inactive.
     */
    fun getEstimatedTimeRemaining(windowSeconds: Int = 30): Long? {
        if (!isActive) return null
        if (totalExpectedBytes <= 0L) return null
        if (bytesDownloaded >= totalExpectedBytes) return null

        val now = System.currentTimeMillis()
        trimOldSamples(now, windowSeconds * 1000L)

        if (speedSamples.size < 2) return null

        val first = speedSamples.first()
        val last = speedSamples.last()
        val elapsedMs = last.timeMs - first.timeMs
        if (elapsedMs <= 0L) return null

        val bytesDelta = last.bytes - first.bytes
        if (bytesDelta <= 0L) return null

        val currentSpeedBytesPerSec = bytesDelta.toDouble() / (elapsedMs.toDouble() / 1000.0)
        if (currentSpeedBytesPerSec <= 0.0) return null

        // Exponential moving average to smooth fluctuations.
        val alpha = 0.3
        val smoothedSpeed = if (!hasEmaSpeed) {
            hasEmaSpeed = true
            emaSpeedBytesPerSec = currentSpeedBytesPerSec
            currentSpeedBytesPerSec
        } else {
            emaSpeedBytesPerSec = alpha * currentSpeedBytesPerSec + (1 - alpha) * emaSpeedBytesPerSec
            emaSpeedBytesPerSec
        }

        if (smoothedSpeed <= 0.0) return null

        val remainingBytes = totalExpectedBytes - bytesDownloaded
        if (remainingBytes <= 0L) return null

        val etaSeconds = remainingBytes / smoothedSpeed
        if (etaSeconds.isNaN() || etaSeconds.isInfinite() || etaSeconds <= 0.0) return null

        return (etaSeconds * 1000.0).toLong()
    }

    fun addProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.add(listener)
    }

    fun removeProgressListener(listener: (Float) -> Unit) {
        downloadProgressListeners.remove(listener)
    }

    fun emitProgressChange() {
        for (listener in downloadProgressListeners) {
            listener(getProgress())
        }
    }

    // --- Persistence helpers ---

    companion object {
        private const val PERSISTENCE_DIR = ".DownloadInfo"
        private const val PERSISTENCE_FILE = "bytes_downloaded.txt"
    }

    /**
     * Persist bytesDownloaded to a file in the app directory.
     */
    fun persistBytesDownloaded(appDirPath: String) {
        try {
            val dir = File(appDirPath, PERSISTENCE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, PERSISTENCE_FILE)
            file.writeText(bytesDownloaded.toString())
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist bytes downloaded to $appDirPath")
        }
    }

    /**
     * Load persisted bytesDownloaded from file, returns 0 if file doesn't exist or is unreadable.
     */
    fun loadPersistedBytesDownloaded(appDirPath: String): Long {
        return try {
            val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                content.toLongOrNull() ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted bytes downloaded from $appDirPath")
            0L
        }
    }

    /**
     * Delete the persisted bytes file (called on download completion).
     */
    fun clearPersistedBytesDownloaded(appDirPath: String) {
        try {
            val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear persisted bytes downloaded from $appDirPath")
        }
    }
}
