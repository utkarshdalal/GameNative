package app.gamenative.powercontrol.metrics

import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-free, allocation-free per-frame timestamp buffer.
 *
 * [record] is called from the render/present path (FrameRating.update), so it does
 * nothing but a volatile read, an atomic increment and one array store. Readers may
 * race with the writer and lose the oldest entries; at 4096 slots that only happens
 * if a reader stalls for thousands of frames.
 */
object FrameTimeRing {
    private const val CAPACITY = 4096
    private const val MASK = (CAPACITY - 1).toLong()

    private val timestampsNs = LongArray(CAPACITY)
    private val writeIndex = AtomicLong(0L)

    @Volatile
    private var recording = false

    @JvmStatic
    fun record() {
        if (!recording) return
        val index = writeIndex.getAndIncrement()
        timestampsNs[(index and MASK).toInt()] = System.nanoTime()
    }

    fun start() {
        writeIndex.set(0L)
        java.util.Arrays.fill(timestampsNs, 0L)
        recording = true
    }

    fun stop() {
        recording = false
    }

    fun isRecording(): Boolean = recording

    fun capacity(): Int = CAPACITY

    /**
     * Copies every retained timestamp at or after [sinceNanos] into [out], oldest first.
     * Returns the number of entries written.
     */
    fun copySince(sinceNanos: Long, out: LongArray): Int {
        val end = writeIndex.get()
        var index = if (end > CAPACITY) end - CAPACITY else 0L
        var count = 0
        while (index < end && count < out.size) {
            val value = timestampsNs[(index and MASK).toInt()]
            if (value >= sinceNanos) {
                out[count++] = value
            }
            index++
        }
        return count
    }
}

data class FrameWindowStats(
    val fps: Float,
    val p50Ms: Float,
    val p95Ms: Float,
    val maxMs: Float,
    val slowFrameCount: Int,
    val totalFrameCount: Int,
) {
    companion object {
        val EMPTY = FrameWindowStats(0f, 0f, 0f, 0f, 0, 0)
    }
}

/**
 * Frame pacing statistics over [count] ascending timestamps taken from [timestampsNs].
 *
 * [deltaScratch] must hold at least [count] entries; it is sorted in place, so callers
 * can reuse a single array across sampling cycles.
 */
fun computeFrameWindowStats(
    timestampsNs: LongArray,
    count: Int,
    slowFrameThresholdNs: Long,
    deltaScratch: LongArray,
    sampleStride: Int = 1,
): FrameWindowStats {
    val stride = sampleStride.coerceAtLeast(1)
    if (count < stride + 1) return FrameWindowStats.EMPTY

    var deltas = 0
    var slowFrames = 0
    var index = stride
    while (index < count) {
        val delta = timestampsNs[index] - timestampsNs[index - stride]
        index += stride
        if (delta <= 0L) continue
        deltaScratch[deltas++] = delta
        if (slowFrameThresholdNs > 0L && delta > slowFrameThresholdNs) {
            slowFrames++
        }
    }
    if (deltas == 0) return FrameWindowStats.EMPTY

    val spanNs = timestampsNs[count - 1] - timestampsNs[0]
    val fps = if (spanNs > 0L) (deltas.toDouble() * 1_000_000_000.0 / spanNs).toFloat() else 0f

    java.util.Arrays.sort(deltaScratch, 0, deltas)

    return FrameWindowStats(
        fps = fps,
        p50Ms = percentileMs(deltaScratch, deltas, 0.50),
        p95Ms = percentileMs(deltaScratch, deltas, 0.95),
        maxMs = deltaScratch[deltas - 1] / 1_000_000f,
        slowFrameCount = slowFrames,
        totalFrameCount = deltas,
    )
}

private fun percentileMs(sortedDeltas: LongArray, size: Int, percentile: Double): Float {
    if (size <= 0) return 0f
    val index = Math.ceil(percentile * size).toInt() - 1
    return sortedDeltas[index.coerceIn(0, size - 1)] / 1_000_000f
}
