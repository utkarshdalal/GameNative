package app.gamenative.ui.screen.xserver

import android.view.Choreographer

/**
 * The "Input throttling" setting: caps how often stick and trigger motion goes out, from physical controllers,
 * on-screen controls and gyro aiming as a stick. One instance per game session, shared by
 * [PhysicalControllerHandler], [GamepadStateOutput] and [MouseLookStepper].
 */
class InputThrottling {
    companion object {
        const val DEFAULT_RATE_HZ = 60
        const val MIN_RATE_HZ = 5
        const val MAX_RATE_HZ = 240

        private const val NANOS_PER_SECOND = 1_000_000_000L
    }

    var enabled = false

    private var intervalNanos = NANOS_PER_SECOND / DEFAULT_RATE_HZ

    fun setRateHz(hz: Int) {
        intervalNanos = NANOS_PER_SECOND / hz.coerceIn(MIN_RATE_HZ, MAX_RATE_HZ)
    }

    /**
     * Whether an update last sent at [lastNanos] (0 = never) may go out at [nowNanos]. The 1/8 slack absorbs
     * frame time jitter when the limit equals the display rate.
     */
    fun isDue(lastNanos: Long, nowNanos: Long): Boolean = !enabled ||
        lastNanos == 0L ||
        nowNanos - lastNanos >= intervalNanos - intervalNanos / 8
}

/** Runs [onFrame] on the next display frame after each [schedule]; the owner reschedules to keep going. */
class FrameCallbackLoop(private val onFrame: (frameTimeNanos: Long) -> Unit) {
    // Per thread: owners are created on the main thread.
    private val choreographer: Choreographer = Choreographer.getInstance()
    private val callback = Choreographer.FrameCallback { frameTimeNanos ->
        isScheduled = false
        onFrame(frameTimeNanos)
    }

    var isScheduled = false
        private set

    fun schedule() {
        if (isScheduled) return
        isScheduled = true
        choreographer.postFrameCallback(callback)
    }

    fun cancel() {
        if (!isScheduled) return
        choreographer.removeFrameCallback(callback)
        isScheduled = false
    }
}
