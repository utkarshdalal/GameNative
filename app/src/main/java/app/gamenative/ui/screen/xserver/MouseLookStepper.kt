package app.gamenative.ui.screen.xserver

import androidx.annotation.VisibleForTesting
import app.gamenative.PluviaApp
import com.winlator.inputcontrols.Binding

/**
 * Held MOUSE_MOVE_* bindings from touch controls and physical controllers. Sums all held sources and moves
 * the pointer once per display frame by speed x frame time, keeping the sub-pixel remainder, so movement is
 * smooth at any frame or event rate. Follows [InputThrottling]; stops when nothing is held. Main thread only.
 */
class MouseLookStepper(
    private val throttling: InputThrottling,
    private val pointerMover: PointerMover,
) {
    fun interface PointerMover {
        fun move(dx: Int, dy: Int)
    }

    companion object {
        @JvmStatic
        fun isHorizontal(binding: Binding): Boolean = binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT

        // At full deflection and cursor speed 1: the former 10 px per 60 Hz timer tick.
        private const val PX_PER_SECOND = 600f

        // Caps one frame gap after a stall (GC pause, app switch).
        private const val MAX_STEP_SECONDS = 0.1f

        // The first frame after idle has no previous one to measure from.
        private const val FIRST_STEP_SECONDS = 1f / 60f

        private const val NANOS_PER_SECOND = 1_000_000_000f
    }

    private class Contribution(val key: Any, val horizontal: Boolean, var value: Float, var cursorSpeed: Float)

    // A few held sources at most: a list with indexed loops, so neither per-event lookups nor steps allocate.
    private val contributions = ArrayList<Contribution>()
    private var remainderX = 0f
    private var remainderY = 0f

    // Paces steps at the throttling rate.
    private val pacer = throttling.Pacer()

    // Frame time of the last frame, and the time since the last step: with throttling, a step covers several
    // frames. Each frame gap is capped, not the step, so a low rate doesn't slow the pointer down.
    private var lastFrameNanos = 0L
    private var unsteppedSeconds = 0f
    private val frameLoop = FrameCallbackLoop(::onFrame)

    /** Summed horizontal deflection of all held sources, before cursor speed. */
    @VisibleForTesting
    internal val offsetX: Float get() = sum(horizontal = true)

    /** Summed vertical deflection of all held sources, before cursor speed. */
    @VisibleForTesting
    internal val offsetY: Float get() = sum(horizontal = false)

    @VisibleForTesting
    internal val isRunning: Boolean get() = frameLoop.isScheduled

    /**
     * Holds a MOUSE_MOVE_* [binding] for [key] until changed or removed. [offset] is the deflection
     * (-1..1, negative = left/up); 0 means full deflection in the binding's direction.
     */
    fun hold(key: Any, binding: Binding, offset: Float, cursorSpeed: Float) {
        val value = when {
            offset != 0f -> offset
            binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_UP -> -1f
            else -> 1f
        }
        set(key, isHorizontal(binding), value, cursorSpeed)
    }

    private fun set(key: Any, horizontal: Boolean, value: Float, cursorSpeed: Float) {
        val index = indexOf(key)
        if (index < 0) {
            contributions.add(Contribution(key, horizontal, value, cursorSpeed))
        } else {
            val contribution = contributions[index]
            contribution.value = value
            contribution.cursorSpeed = cursorSpeed
        }
        frameLoop.schedule()
    }

    fun remove(key: Any) {
        val index = indexOf(key)
        if (index < 0) return
        contributions.removeAt(index)
        onRemoved()
    }

    fun removeIf(predicate: (Any) -> Boolean) {
        var removed = false
        var i = contributions.size - 1
        while (i >= 0) {
            if (predicate(contributions[i].key)) {
                contributions.removeAt(i)
                removed = true
            }
            i--
        }
        if (removed) onRemoved()
    }

    private fun indexOf(key: Any): Int {
        for (i in contributions.indices) {
            if (contributions[i].key == key) return i
        }
        return -1
    }

    /** Restarts stepping for sources still held when the game was paused. */
    fun resume() {
        if (contributions.isNotEmpty()) frameLoop.schedule()
    }

    private fun onRemoved() {
        if (contributions.isNotEmpty()) return
        remainderX = 0f
        remainderY = 0f
        pacer.reset()
        lastFrameNanos = 0L
        unsteppedSeconds = 0f
        frameLoop.cancel()
    }

    private fun sum(horizontal: Boolean): Float {
        var total = 0f
        for (i in contributions.indices) {
            val contribution = contributions[i]
            if (contribution.horizontal == horizontal) total += contribution.value
        }
        return total
    }

    private fun onFrame(frameTimeNanos: Long) {
        if (contributions.isEmpty()) return
        if (PluviaApp.isOverlayPaused) {
            // resume() restarts; the pause is not a step
            pacer.reset()
            lastFrameNanos = 0L
            unsteppedSeconds = 0f
            return
        }
        unsteppedSeconds += if (lastFrameNanos == 0L) {
            FIRST_STEP_SECONDS
        } else {
            ((frameTimeNanos - lastFrameNanos) / NANOS_PER_SECOND).coerceIn(0f, MAX_STEP_SECONDS)
        }
        lastFrameNanos = frameTimeNanos
        if (pacer.isDue(frameTimeNanos)) {
            pacer.onSent(frameTimeNanos)
            step()
        }
        frameLoop.schedule()
    }

    private fun step() {
        var deflectionX = 0f
        var deflectionY = 0f
        var speedX = 0f
        var speedY = 0f
        for (i in contributions.indices) {
            val contribution = contributions[i]
            if (contribution.horizontal) {
                deflectionX += contribution.value
                speedX += contribution.value * contribution.cursorSpeed
            } else {
                deflectionY += contribution.value
                speedY += contribution.value * contribution.cursorSpeed
            }
        }
        // No noise floor: every source is past its own dead zone already (a tuned stick's is the user's).
        if (deflectionX == 0f && deflectionY == 0f) {
            unsteppedSeconds = 0f
            return
        }

        val dtSeconds = unsteppedSeconds
        unsteppedSeconds = 0f

        val rawDeltaX = speedX * PX_PER_SECOND * dtSeconds + remainderX
        val rawDeltaY = speedY * PX_PER_SECOND * dtSeconds + remainderY
        val moveX = rawDeltaX.toInt()
        val moveY = rawDeltaY.toInt()
        remainderX = rawDeltaX - moveX
        remainderY = rawDeltaY - moveY

        if (moveX != 0 || moveY != 0) pointerMover.move(moveX, moveY)
    }
}
