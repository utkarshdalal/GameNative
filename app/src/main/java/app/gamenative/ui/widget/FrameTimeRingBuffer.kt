package app.gamenative.ui.widget

/**
 * Ring buffer of recent per-frame durations (ns). Producer thread writes via [add]; the HUD
 * reads via [snapshot] on its own collector. capacity defaults to ~10s @60fps.
 *
 * fed by Window.OnFrameMetricsAvailableListener on the html5 path; the wine path stays empty
 * until FrameRating publishes per-frame deltas.
 */
class FrameTimeRingBuffer(private val capacity: Int = 600) {
    private val ns = LongArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(deltaNs: Long) {
        if (deltaNs <= 0L) return
        ns[head] = deltaNs
        head = (head + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun reset() {
        head = 0
        size = 0
    }

    /** Returns null if too few samples to compute meaningful percentiles. */
    @Synchronized
    fun snapshot(): FrameTimeStats? {
        if (size < 30) return null
        val arr = LongArray(size)
        for (i in 0 until size) {
            arr[i] = ns[(head - size + i + capacity) % capacity]
        }
        arr.sort()
        // percentiles on the SLOW end (long frametimes = bad). p99 = 1% of frames slower than this.
        val meanNs = arr.average()
        val p99Ns = arr[((arr.size * 99) / 100).coerceAtMost(arr.size - 1)]
        val p999Ns = arr[((arr.size * 999) / 1000).coerceAtMost(arr.size - 1)]
        return FrameTimeStats(
            meanMs = meanNs / 1_000_000.0,
            p99Ms = p99Ns / 1_000_000.0,
            p999Ms = p999Ns / 1_000_000.0,
        )
    }
}

data class FrameTimeStats(
    val meanMs: Double,
    val p99Ms: Double,
    val p999Ms: Double,
)
