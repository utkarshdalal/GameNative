package app.gamenative.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class DownloadFailedException(message: String) : CancellationException(message)

enum class DownloadPhase {
    UNKNOWN,
    PREPARING,
    DOWNLOADING,
    PAUSED,
    FAILED,
    VERIFYING,
    PATCHING,
    APPLYING_DATA,
    FINALIZING,
    COMPLETE
}

class DownloadInfo(
    val jobCount: Int = 1,
    val gameId: Int,
    var downloadingAppIds: CopyOnWriteArrayList<Int>,
) {
    @Volatile var isDeleting: Boolean = false
    private var downloadJob: Job? = null
    private val downloadProgressListeners = CopyOnWriteArrayList<((Float) -> Unit)>()
    private val progresses: Array<Float> = Array(jobCount) { 0f }

    private val weights    = FloatArray(jobCount) { 1f }  
    private var weightSum  = jobCount.toFloat()

    // Bytes / speed tracking for more stable ETA
    private var totalExpectedBytes = AtomicLong(0L)
    private var bytesDownloaded = AtomicLong(0L)
    @Volatile private var persistencePath: String? = null
    private val lastPersistTimestampMs = AtomicLong(0L)
    private val hasDirtyProgressSnapshot = AtomicBoolean(false)
    private val isPersistEnqueued = AtomicBoolean(false)
    private val snapshotWriteGeneration = AtomicLong(0L)

    private data class SpeedSample(val timeMs: Long, val bytes: Long)
    private val speedSamples = ArrayDeque<SpeedSample>()
    @Volatile private var etaEmaSpeedBytesPerSec: Double = 0.0
    @Volatile private var hasEtaEmaSpeed: Boolean = false
    @Volatile private var isActive: Boolean = true
    private val status = MutableStateFlow(DownloadPhase.UNKNOWN)
    private val statusMessage = MutableStateFlow<String?>(null)

    private val emitLock = Any()
    private var lastProgressEmitTimeMs = 0L
    private var lastEmittedProgress = -1f

    private val completedDepotIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private val depotSizesById = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    fun markDepotCompleted(depotId: Int) {
        completedDepotIds.add(depotId)
    }

    fun getCompletedDepotIdsSnapshot(): Set<Int> {
        return completedDepotIds.toSet()
    }

    fun initializeCompletedDepotIds(ids: Collection<Int>) {
        completedDepotIds.clear()
        completedDepotIds.addAll(ids)
    }

    fun cancel() {
        cancel("Cancelled by user")
    }

    fun failedToDownload() {
        persistProgressSnapshot(force = true)
        status.value = DownloadPhase.FAILED
        setActive(false)
        downloadJob?.cancel(DownloadFailedException("Failed to download"))
    }

    fun cancel(message: String) {
        // Persist the most recent progress so a resume can pick up where it left off.
        persistProgressSnapshot(force = true)
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
            val downloaded = bytesDownloaded.get().coerceAtMost(total)
            val rawBytesProgress = (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)

            if (rawBytesProgress >= 1f && hasIncompleteTrackedDepots()) {
                return 0.99f
            }

            return rawBytesProgress
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

    fun setDepotSizes(sizeBytesByDepotId: Map<Int, Long>) {
        depotSizesById.clear()
        sizeBytesByDepotId.forEach { (depotId, sizeBytes) ->
            depotSizesById[depotId] = sizeBytes.coerceAtLeast(1L)
        }
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
        if (persistencePath != appDirPath) {
            lastPersistTimestampMs.set(0L)
            hasDirtyProgressSnapshot.set(false)
            persistencePath = appDirPath
            snapshotWriteGeneration.incrementAndGet()
        }
    }

    fun persistProgressSnapshot(force: Boolean = false) {
        val appDirPath = persistencePath ?: return
        val nowMs = System.currentTimeMillis()

        if (force) {
            val expectedGeneration = snapshotWriteGeneration.get()
            try {
                val persisted = persistResumeSnapshotInternal(
                    appDirPath = appDirPath,
                    expectedGeneration = expectedGeneration,
                )
                if (persisted) {
                    lastPersistTimestampMs.set(nowMs)
                    hasDirtyProgressSnapshot.set(false)
                }
            } catch (e: Exception) {
                hasDirtyProgressSnapshot.set(true)
            }
            return
        }

        if (!hasDirtyProgressSnapshot.get()) {
            return
        }

        if (nowMs - lastPersistTimestampMs.get() < PROGRESS_SNAPSHOT_MIN_INTERVAL_MS) {
            return
        }

        val expectedGeneration = snapshotWriteGeneration.get()
        if (isPersistEnqueued.compareAndSet(false, true)) {
            SNAPSHOT_PERSIST_EXECUTOR.execute {
                try {
                    if (!hasDirtyProgressSnapshot.getAndSet(false)) return@execute
                    val persisted = persistResumeSnapshotInternal(
                        appDirPath = appDirPath,
                        expectedGeneration = expectedGeneration,
                    )
                    if (persisted) {
                        lastPersistTimestampMs.set(System.currentTimeMillis())
                    }
                } catch (e: Exception) {
                    hasDirtyProgressSnapshot.set(true)
                } finally {
                    isPersistEnqueued.set(false)
                }
            }
        }
    }

    fun markProgressSnapshotDirty() {
        hasDirtyProgressSnapshot.set(true)
    }

    fun updateBytesDownloaded(
        deltaBytes: Long,
        timestampMs: Long = System.currentTimeMillis(),
        trackSpeed: Boolean = true,
    ) {
        if (!isActive) return
        if (deltaBytes <= 0L) {
            // Still record a sample to advance the time window, but do not change the count.
            if (trackSpeed) {
                addSpeedSample(timestampMs, bytesDownloaded.get())
            }
            return
        }

        val currentBytes = bytesDownloaded.addAndGet(deltaBytes)
        if (currentBytes < 0L) {
            bytesDownloaded.set(0L)
            addSpeedSample(timestampMs, 0L)
        } else {
            hasDirtyProgressSnapshot.set(true)
            if (trackSpeed) {
                addSpeedSample(timestampMs, currentBytes)
            }
        }
    }

    fun updateStatusMessage(message: String?) {
        statusMessage.value = message
    }

    fun mapToDownloadPhase(phaseName: String): DownloadPhase =
        runCatching { DownloadPhase.valueOf(phaseName) }.getOrDefault(DownloadPhase.UNKNOWN)

    fun updateStatus(status: DownloadPhase, message: String? = null) {
        val previousStatus = this.status.value
        this.status.value = status

        // When returning to active downloading after a different phase, drop old speed history
        if (status == DownloadPhase.DOWNLOADING &&
            previousStatus != DownloadPhase.DOWNLOADING &&
            previousStatus != DownloadPhase.UNKNOWN
        ) {
            resetSpeedTracking()
        }

        if (message != null) {
            statusMessage.value = message
        } else if (previousStatus != status) {
            statusMessage.value = null
        }
    }

    fun getStatusFlow(): StateFlow<DownloadPhase> = status

    fun getStatusMessageFlow(): StateFlow<String?> = statusMessage

    private fun addSpeedSample(timestampMs: Long, currentBytes: Long) {
        synchronized(speedSamples) {
            speedSamples.add(SpeedSample(timestampMs, currentBytes))
            trimOldSamples(timestampMs, SPEED_SAMPLE_RETENTION_MS)
        }
    }

    private fun trimOldSamples(nowMs: Long, windowMs: Long) {
        val cutoff = nowMs - windowMs
        // Must be called within synchronized(speedSamples)
        while (speedSamples.isNotEmpty() && speedSamples.first().timeMs < cutoff) {
            speedSamples.removeFirst()
        }
    }

    private fun getLastSampleAgeMs(nowMs: Long = System.currentTimeMillis()): Long? {
        synchronized(speedSamples) {
            if (speedSamples.isEmpty()) return null
            return (nowMs - speedSamples.last().timeMs).coerceAtLeast(0L)
        }
    }

    fun resetSpeedTracking() {
        synchronized(speedSamples) {
            speedSamples.clear()
        }
        etaEmaSpeedBytesPerSec = 0.0
        hasEtaEmaSpeed = false
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

    private fun hasIncompleteTrackedDepots(): Boolean {
        if (depotSizesById.isEmpty()) return false
        return !completedDepotIds.containsAll(depotSizesById.keys)
    }

    private fun getSpeedOverWindow(windowMs: Long): Double? {
        val now = System.currentTimeMillis()
        val cutoff = now - windowMs

        val first: SpeedSample
        val last: SpeedSample

        synchronized(speedSamples) {
            if (speedSamples.size < 2) return null
            last = speedSamples.last()

            var foundFirst = last
            val iterator = speedSamples.descendingIterator()
            while (iterator.hasNext()) {
                val sample = iterator.next()
                if (sample.timeMs < cutoff) {
                    break
                }
                foundFirst = sample
            }
            first = foundFirst
        }

        val elapsedMs = last.timeMs - first.timeMs
        if (elapsedMs <= 500L) return null // Need at least half a second of data

        val bytesDelta = last.bytes - first.bytes
        if (bytesDelta <= 0L) return 0.0

        return bytesDelta.toDouble() / (elapsedMs.toDouble() / 1000.0)
    }

    // Returns the current download speed in bytes per second, or null if not enough data.
    fun getCurrentDownloadSpeed(): Long? {
        if (!isActive) return null
        val speed = getSpeedOverWindow(CURRENT_SPEED_WINDOW_MS) ?: return null
        return speed.toLong()
    }

    // Returns an ETA in milliseconds based on recent download speed, or null if
    fun getEstimatedTimeRemaining(): Long? {
        if (!isActive) return null
        val currentStatus = status.value
        if (currentStatus != DownloadPhase.UNKNOWN && currentStatus != DownloadPhase.DOWNLOADING) {
            return null
        }
        val total = totalExpectedBytes.get()
        val downloaded = bytesDownloaded.get()
        if (total <= 0L) return null
        if (downloaded >= total) return null

        val nowMs = System.currentTimeMillis()
        val rawSpeedBytesPerSec = getSpeedOverWindow(ETA_SPEED_WINDOW_MS)

        // Smooth ETA input speed to reduce jumpiness from chunk burst patterns.
        val speedBytesPerSec = when {
            rawSpeedBytesPerSec != null && rawSpeedBytesPerSec > 0.0 -> {
                if (!hasEtaEmaSpeed || etaEmaSpeedBytesPerSec <= 0.0) {
                    hasEtaEmaSpeed = true
                    etaEmaSpeedBytesPerSec = rawSpeedBytesPerSec
                    rawSpeedBytesPerSec
                } else {
                    val alpha = 0.2
                    etaEmaSpeedBytesPerSec =
                        alpha * rawSpeedBytesPerSec + (1.0 - alpha) * etaEmaSpeedBytesPerSec
                    etaEmaSpeedBytesPerSec
                }
            }
            rawSpeedBytesPerSec == 0.0 -> {
                return null
            }
            hasEtaEmaSpeed && etaEmaSpeedBytesPerSec > 0.0 -> {
                val lastSampleAgeMs = getLastSampleAgeMs(nowMs) ?: return null
                if (lastSampleAgeMs > ETA_SAMPLE_STALE_TIMEOUT_MS) return null
                etaEmaSpeedBytesPerSec
            }
            else -> return null
        }
        if (speedBytesPerSec <= 0.0) return null

        val remainingBytes = total - downloaded
        if (remainingBytes <= 0L) return null

        val etaSeconds = remainingBytes / speedBytesPerSec
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
        val currentProgress = getProgress()
        val now = System.currentTimeMillis()

        var shouldEmit = false
        synchronized(emitLock) {
            val progressChanged = abs(currentProgress - lastEmittedProgress) > 0f
            val boundaryChanged = (currentProgress <= 0f || currentProgress >= 1f) && progressChanged
            val periodicChanged = (now - lastProgressEmitTimeMs >= 100L) && progressChanged
            if (boundaryChanged || periodicChanged) {
                lastProgressEmitTimeMs = now
                lastEmittedProgress = currentProgress
                shouldEmit = true
            }
        }

        if (shouldEmit) {
            for (listener in downloadProgressListeners) {
                listener(currentProgress)
            }
        }
    }

    data class ResumeSnapshot(
        val bytesDownloaded: Long,
        val completedDepotIds: Set<Int>,
    )


    companion object {
        private const val SPEED_SAMPLE_RETENTION_MS = 120_000L
        private const val CURRENT_SPEED_WINDOW_MS = 5_000L
        private const val ETA_SPEED_WINDOW_MS = 60_000L
        private const val ETA_SAMPLE_STALE_TIMEOUT_MS = 120_000L
        private const val PERSISTENCE_DIR = ".DownloadInfo"
        private const val PERSISTENCE_FILE = "depot_bytes.json"
        private const val PROGRESS_SNAPSHOT_MIN_INTERVAL_MS = 5_000L
        private val PERSISTENCE_IO_LOCK = Any()
        private val SNAPSHOT_PERSIST_EXECUTOR: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "DownloadInfoSnapshotWriter").apply {
                    isDaemon = true
                }
            }

        fun loadPersistedResumeSnapshot(appDirPath: String): ResumeSnapshot? {
            return try {
                val dir = File(appDirPath, PERSISTENCE_DIR)
                val file = File(dir, PERSISTENCE_FILE)

                if (file.exists() && file.canRead()) {
                    val content = file.readText().trim()
                    if (content.isEmpty()) return null

                    val json = org.json.JSONObject(content)

                    val bytesDownloaded = json.optLong("bytesDownloaded", 0L).coerceAtLeast(0L)
                    val completedArray = json.optJSONArray("completedDepotIds")
                    val completedSet = mutableSetOf<Int>()
                    if (completedArray != null) {
                        for (i in 0 until completedArray.length()) {
                            completedSet.add(completedArray.getInt(i))
                        }
                    }
                    return ResumeSnapshot(bytesDownloaded, completedSet)
                }

                null
            } catch (e: Exception) {
                Timber.e(e, "Failed to load persisted snapshot from $appDirPath")
                try {
                    val file = File(File(appDirPath, PERSISTENCE_DIR), PERSISTENCE_FILE)
                    if (file.exists()) file.delete()
                } catch (e2: Exception) {
                    // Ignore deletion errors
                }
                null
            }
        }

        // Delete the persisted bytes file (called on download completion).
        private fun deletePersistedFiles(appDirPath: String) {
            synchronized(PERSISTENCE_IO_LOCK) {
                try {
                    val dir = File(appDirPath, PERSISTENCE_DIR)
                    val file = File(dir, PERSISTENCE_FILE)
                    if (file.exists()) {
                        file.delete()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to clear persisted bytes downloaded from $appDirPath")
                }
            }
        }
    }

    // Delete the persisted bytes file (called on download completion).
    fun clearPersistedBytesDownloaded(appDirPath: String, sync: Boolean = false) {
        lastPersistTimestampMs.set(0L)
        hasDirtyProgressSnapshot.set(false)
        snapshotWriteGeneration.incrementAndGet()
        if (sync) {
            deletePersistedFiles(appDirPath)
        } else {
            SNAPSHOT_PERSIST_EXECUTOR.execute {
                deletePersistedFiles(appDirPath)
            }
        }
    }

    private fun persistResumeSnapshotInternal(
        appDirPath: String,
        expectedGeneration: Long? = null,
    ): Boolean {
        synchronized(PERSISTENCE_IO_LOCK) {
            if (expectedGeneration != null && expectedGeneration != snapshotWriteGeneration.get()) {
                return false
            }
            val dir = File(appDirPath, PERSISTENCE_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, PERSISTENCE_FILE)

            val completedList = completedDepotIds.toList()
            val snapshotBytes = bytesDownloaded.get().coerceAtLeast(0L)

            // Manually build JSON string to avoid JSONObject overhead
            val sb = java.lang.StringBuilder()
            sb.append("{\"bytesDownloaded\":").append(snapshotBytes).append(",\"completedDepotIds\":[")
            completedList.forEachIndexed { index, id ->
                if (index > 0) sb.append(",")
                sb.append(id)
            }
            sb.append("]}")
            val jsonText = sb.toString()

            val tempFile = File(dir, "$PERSISTENCE_FILE.tmp")
            tempFile.writeText(jsonText)
            if (!tempFile.renameTo(file)) {
                // Fallback for filesystems where rename may fail.
                file.writeText(jsonText)
                tempFile.delete()
            }
        }
        return true
    }
}
