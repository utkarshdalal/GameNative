package app.gamenative.ui.screen.xserver

import android.view.Choreographer
import android.view.animation.AnimationUtils

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
        private const val NANOS_PER_MILLI = 1_000_000L

        // Absorbs the millisecond rounding of nowNanos(); a quarter interval at most for the highest rates.
        private const val MAX_EARLY_NANOS = 2 * NANOS_PER_MILLI

        /**
         * Now on the input clock: the display frame's VSYNC time while Choreographer runs a frame (batched motion
         * dispatch and frame callbacks), otherwise uptime. Same base as Choreographer's frameTimeNanos. Pacing by the
         * frame, not by when the main thread got to it, keeps a busy frame from pushing a send to the next one.
         */
        @JvmStatic
        fun nowNanos(): Long = AnimationUtils.currentAnimationTimeMillis() * NANOS_PER_MILLI
    }

    var enabled = false
        set(value) {
            if (field != value) settingsVersion++
            field = value
        }

    private var intervalNanos = NANOS_PER_SECOND / DEFAULT_RATE_HZ

    // Bumped on every setting change: pacers restart their schedule.
    private var settingsVersion = 0

    fun setRateHz(hz: Int) {
        val nanos = NANOS_PER_SECOND / hz.coerceIn(MIN_RATE_HZ, MAX_RATE_HZ)
        if (nanos != intervalNanos) settingsVersion++
        intervalNanos = nanos
    }

    private fun earlyToleranceNanos(): Long = minOf(intervalNanos / 4, MAX_EARLY_NANOS)

    /**
     * One sender's pace at the throttling rate. Sends follow a schedule, not the last send: the next one is due an
     * interval after the previous due time, so a rate that doesn't divide the display rate still averages out
     * (45 Hz on a 60 Hz display: 3 of every 4 frames), and one that does is evenly spaced. After more than an
     * interval without a send (idle), the next one goes out right away and the schedule restarts from it.
     */
    inner class Pacer {
        // When the next send is due on the nowNanos() clock; 0 = none booked.
        private var nextDueNanos = 0L
        private var seenSettingsVersion = settingsVersion

        /** Whether a send at [nowNanos] keeps to the rate; always while throttling is off. */
        fun isDue(nowNanos: Long): Boolean {
            if (!enabled) return true
            syncSettings()
            return nextDueNanos == 0L || nowNanos >= nextDueNanos - earlyToleranceNanos()
        }

        /**
         * Records a send at [nowNanos]. One ahead of its due time (forced) leaves the schedule as it is; one a whole
         * interval or more late restarts it, rather than sending again on the next frame to catch up.
         */
        fun onSent(nowNanos: Long) {
            syncSettings()
            if (nextDueNanos == 0L || nowNanos - nextDueNanos >= intervalNanos) {
                nextDueNanos = nowNanos + intervalNanos
            } else if (nowNanos >= nextDueNanos - earlyToleranceNanos()) {
                nextDueNanos += intervalNanos
            }
        }

        /** Forgets the schedule (idle, pause): the next send goes out right away. */
        fun reset() {
            nextDueNanos = 0L
        }

        private fun syncSettings() {
            if (seenSettingsVersion == settingsVersion) return
            seenSettingsVersion = settingsVersion
            nextDueNanos = 0L
        }
    }
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
