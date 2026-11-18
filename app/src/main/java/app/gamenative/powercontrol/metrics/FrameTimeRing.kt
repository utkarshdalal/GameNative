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

/**
 * Whether repeated frames are counted: only in gamescope sessions, where the ring takes one entry
 * per frame gamescope publishes on its vblank grid, and not with frame generation on
 * ([sampleStride] > 1), where generated frames tick the ring. X11 sessions feed the ring from
 * their own present hooks and keep the tuner as it was.
 */
fun repeatCountingActive(gamescopeSession: Boolean, sampleStride: Int): Boolean =
    gamescopeSession && sampleStride <= 1

/**
 * Interval above which a published frame counts as a repeat in a gamescope session, or 0 when
 * the display rate is unknown. Gamescope runs a capped game at a whole number of vblanks per
 * frame, divisor = max(1, floor(refresh / cap)) (cap above refresh, or no cap: 1), so a frame is
 * expected one divisor of display periods after the last, and a repeat adds at least one more
 * vblank: the threshold sits half a display period above the expected interval.
 * Cap 60 @ 60 Hz: 25 ms; cap 60 @ 120: 20.8 ms; cap 30 @ 60: 41.7 ms; cap 40 @ 120: 29.2 ms.
 */
fun repeatThresholdNs(capFps: Int, refreshHz: Int): Long {
    if (refreshHz <= 0) return 0L
    val periodNs = 1_000_000_000.0 / refreshHz
    val divisor = if (capFps > 0) (refreshHz / capFps).coerceAtLeast(1) else 1
    return (periodNs * divisor + periodNs * 0.5).toLong()
}

/**
 * Number of intervals between [count] ascending timestamps in [timestampsNs] (taken
 * [sampleStride] frames apart, as in [computeFrameWindowStats]) longer than [thresholdNs].
 * With [repeatThresholdNs] each such interval is a frame the display showed again.
 */
fun countSlowIntervals(
    timestampsNs: LongArray,
    count: Int,
    thresholdNs: Long,
    sampleStride: Int = 1,
): Int {
    val stride = sampleStride.coerceAtLeast(1)
    if (thresholdNs <= 0L) return 0
    var slow = 0
    var index = stride
    while (index < count) {
        if (timestampsNs[index] - timestampsNs[index - stride] > thresholdNs) slow++
        index += stride
    }
    return slow
}
