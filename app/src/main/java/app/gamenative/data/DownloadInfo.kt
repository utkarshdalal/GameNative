package app.gamenative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class DownloadInfo(
    val jobCount: Int = 1,
    val gameId: Int,
    var downloadingAppIds: CopyOnWriteArrayList<Int>,
) {
    private var downloadJob: Job? = null
    private val downloadProgressListeners = CopyOnWriteArrayList<((Float) -> Unit)>()
    private val progresses: Array<Float> = Array(jobCount) { 0f }

    private val weights    = FloatArray(jobCount) { 1f }     // ⇐ new
    private var weightSum  = jobCount.toFloat()

    // === Bytes / speed tracking for more stable ETA ===
    private var totalExpectedBytes = AtomicLong(0L)
    private var bytesDownloaded = AtomicLong(0L)
    private var persistencePath: String? = null
    private val persistenceLock = Any()

    private data class SpeedSample(val timeMs: Long, val bytes: Long)

    private val speedSamples = ArrayDeque<SpeedSample>()
    @Volatile private var emaSpeedBytesPerSec: Double = 0.0
    @Volatile private var hasEmaSpeed: Boolean = false
    @Volatile private var isActive: Boolean = true
    private val statusMessage = MutableStateFlow<String?>(null)

    val depotCumulativeUncompressedBytes = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun cancel() {
        cancel("Cancelled by user")
    }

    fun failedToDownload() {
        cancel("Failed to download")
    }

    fun cancel(message: String) {
        // Persist the most recent progress so a resume can pick up where it left off.
        persistProgressSnapshot()
        // Mark as inactive and clear speed tracking so a future resume
        // does not use stale samples.
        setActive(false)
        downloadJob?.cancel(CancellationException(message))
    }

    fun setDownloadJob(job: Job) {
        downloadJob = job
    }

    suspend fun awaitCompletion(timeoutMs: Long = 5000L) {
        withTimeoutOrNull(timeoutMs) { downloadJob?.join() }
    }

    fun getProgress(): Float {
        // Always use bytes-based progress when available for accuracy
        val total = totalExpectedBytes.get()
        if (total > 0L) {
            val bytesProgress = (bytesDownloaded.get().toFloat() / total.toFloat()).coerceIn(0f, 1f)
            return bytesProgress
        }

        // Fallback to depot-based progress only if we don't have byte tracking
        var totalProgress = 0f
        for (i in progresses.indices) {
            totalProgress += progresses[i] * weights[i]   // weight each depot
        }
        return if (weightSum == 0f) 0f else totalProgress / weightSum
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
        totalExpectedBytes.set(if (bytes < 0L) 0L else bytes)
    }

    /**
     * Initialize bytesDownloaded with a persisted value (used on resume).
     */
    fun initializeBytesDownloaded(value: Long) {
        bytesDownloaded.set(if (value < 0L) 0L else value)
    }

    /**
     * Record that [deltaBytes] have just been downloaded at [timestampMs].
     * This is used to derive recent download speed over a sliding window.
     */
    fun setPersistencePath(appDirPath: String?) {
        persistencePath = appDirPath
    }

    fun persistProgressSnapshot() {
        val appDirPath = persistencePath ?: return
        val snapshot = depotCumulativeUncompressedBytes.toMap()
        persistDepotBytes(appDirPath, snapshot)
    }

    fun updateBytesDownloaded(deltaBytes: Long, timestampMs: Long = System.currentTimeMillis()) {
        if (!isActive) return
        if (deltaBytes <= 0L) {
            // Still record a sample to advance the time window, but do not change the count.
            addSpeedSample(timestampMs, bytesDownloaded.get())
            return
        }

        val currentBytes = bytesDownloaded.addAndGet(deltaBytes)
        if (currentBytes < 0L) {
            bytesDownloaded.set(0L)
            addSpeedSample(timestampMs, 0L)
        } else {
            addSpeedSample(timestampMs, currentBytes)
        }
    }

    fun updateStatusMessage(message: String?) {
        statusMessage.value = message
    }

    fun getStatusMessageFlow(): StateFlow<String?> = statusMessage

    private fun addSpeedSample(timestampMs: Long, currentBytes: Long) {
        synchronized(speedSamples) {
            speedSamples.add(SpeedSample(timestampMs, currentBytes))
            trimOldSamples(timestampMs)
        }
    }

    private fun trimOldSamples(nowMs: Long, windowMs: Long = 30_000L) {
        val cutoff = nowMs - windowMs
        // Must be called within synchronized(speedSamples)
        while (speedSamples.isNotEmpty() && speedSamples.first().timeMs < cutoff) {
            speedSamples.removeFirst()
        }
    }

    fun resetSpeedTracking() {
        synchronized(speedSamples) {
            speedSamples.clear()
        }
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
    fun getTotalExpectedBytes(): Long = totalExpectedBytes.get()

    /**
     * Returns the cumulative bytes downloaded so far.
     */
    fun getBytesDownloaded(): Long = bytesDownloaded.get()

    /**
     * Returns a pair of (downloaded bytes, total expected bytes).
     * Returns (0, 0) if total expected bytes is 0 or not yet set.
     */
    fun getBytesProgress(): Pair<Long, Long> {
        val total = totalExpectedBytes.get()
        val downloaded = bytesDownloaded.get()
        return if (total > 0L) {
            downloaded.coerceAtMost(total) to total
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
        val total = totalExpectedBytes.get()
        val downloaded = bytesDownloaded.get()
        if (total <= 0L) return null
        if (downloaded >= total) return null

        val now = System.currentTimeMillis()

        val first: SpeedSample
        val last: SpeedSample
        val size: Int

        synchronized(speedSamples) {
            trimOldSamples(now, windowSeconds * 1000L)
            size = speedSamples.size
            if (size < 2) return null
            first = speedSamples.first()
            last = speedSamples.last()
        }

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

        val remainingBytes = total - downloaded
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
        private const val PERSISTENCE_FILE = "depot_bytes.json"
        private const val LEGACY_PERSISTENCE_FILE = "bytes_downloaded.txt"
    }

    /**
     * Persist bytesDownloaded per depot to a JSON file in the app directory.
     */
    fun persistDepotBytes(appDirPath: String, depotBytes: Map<Int, Long>) {
        try {
            val dir = File(appDirPath, PERSISTENCE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, PERSISTENCE_FILE)
            val json = JSONObject()
            for ((depotId, bytes) in depotBytes) {
                json.put(depotId.toString(), bytes.coerceAtLeast(0L))
            }
            synchronized(persistenceLock) {
                val tempFile = File(dir, "$PERSISTENCE_FILE.tmp")
                val jsonText = json.toString()
                tempFile.writeText(jsonText)
                if (!tempFile.renameTo(file)) {
                    // Fallback for filesystems where rename may fail.
                    file.writeText(jsonText)
                    tempFile.delete()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to persist depot bytes to $appDirPath")
        }
    }

    /**
     * Load persisted bytesDownloaded per depot from file, returns empty map if file doesn't exist or is unreadable.
     */
    fun loadPersistedDepotBytes(appDirPath: String): Map<Int, Long> {
        return try {
            val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
            if (file.exists() && file.canRead()) {
                val content = file.readText().trim()
                if (content.isEmpty()) return emptyMap()
                val json = JSONObject(content)
                val map = mutableMapOf<Int, Long>()
                for (key in json.keys()) {
                    val depotId = key.toIntOrNull() ?: continue
                    map[depotId] = json.getLong(key).coerceAtLeast(0L)
                }
                map
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load persisted depot bytes from $appDirPath")
            emptyMap()
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
            // Also delete the old file if it exists
            val oldFile = File(File(appDirPath, PERSISTENCE_DIR), LEGACY_PERSISTENCE_FILE)
            if (oldFile.exists()) {
                oldFile.delete()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear persisted bytes downloaded from $appDirPath")
        }
    }
}
