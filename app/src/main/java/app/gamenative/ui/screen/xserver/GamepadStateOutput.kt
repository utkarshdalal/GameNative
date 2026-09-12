package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.XServer

/**
 * Sends profile gamepad state to Wine (WinHandler UDP and shared memory) for touch controls, physical
 * controllers, gyro and the radial menu. Every send wakes all Wine processes, so a state equal to the last
 * one sent (at the precision Wine gets) is skipped, and stick motion follows [InputThrottling]. Main thread only.
 */
class GamepadStateOutput(
    private val throttling: InputThrottling,
    private val sender: Sender,
) {
    fun interface Sender {
        fun send(state: GamepadState?)

        /** Whether [send] would reach Wine now; until then, nothing counts as sent. */
        fun canSend(): Boolean = true
    }

    companion object {
        /** Sends through the X server's WinHandler: UDP gamepad clients and the shared-memory gamepad. */
        @JvmStatic
        fun winHandlerSender(xServer: () -> XServer?): Sender = object : Sender {
            override fun send(state: GamepadState?) {
                xServer()?.winHandler?.let { winHandler ->
                    winHandler.sendGamepadState()
                    winHandler.sendVirtualGamepadState(state)
                }
            }

            override fun canSend(): Boolean = xServer()?.winHandler != null
        }

        // WinHandler's shared-memory encoding (sqrt curve, 16 bits), the finer of the two.
        private fun quantizeTrigger(value: Float): Int = Math.round(Math.sqrt(value.coerceIn(0f, 1f).toDouble()) * 65534.0).toInt()
    }

    private val lastSent = GamepadState()
    private var hasSent = false

    // Paces sends at the throttling rate, on the display frame clock.
    private val pacer = throttling.Pacer()

    // Motion held back by throttling, sent as it is by the first frame on which it is due.
    private var pendingMotion: GamepadState? = null
    private val frameLoop = FrameCallbackLoop(::flushPendingMotion)

    /** Sends a discrete change (button, release) right away, if it changes what Wine gets. */
    fun send(state: GamepadState?) {
        if (state != null && hasChanged(state)) transmit(state)
    }

    /** Sends stick, trigger or gyro motion if it changed, at most as often as throttling allows. */
    fun sendMotion(state: GamepadState?) {
        if (state == null) return
        if (!pacer.isDue(InputThrottling.nowNanos())) {
            pendingMotion = state
            frameLoop.schedule()
            return
        }
        send(state)
    }

    /** Sends even if unchanged: releases and resets that must reach Wine. */
    fun sendForced(state: GamepadState?) {
        transmit(state)
    }

    private fun transmit(state: GamepadState?) {
        // Whatever is sent now is newer than held-back motion, even another instance (a switched profile).
        pendingMotion = null
        frameLoop.cancel()
        // Not sent, so not a duplicate later: the first state once Wine is reachable goes out.
        if (!sender.canSend()) return
        if (state != null) {
            lastSent.copy(state)
            hasSent = true
            pacer.onSent(InputThrottling.nowNanos())
        }
        sender.send(state)
    }

    private fun flushPendingMotion(frameTimeNanos: Long) {
        val state = pendingMotion ?: return
        if (!pacer.isDue(frameTimeNanos)) {
            frameLoop.schedule()
            return
        }
        pendingMotion = null
        send(state)
    }

    // At the precision sent, not raw floats: jitter below it is not a change.
    private fun hasChanged(current: GamepadState): Boolean {
        if (!hasSent) return true
        return GamepadState.encodeThumbAxis(current.thumbLX) != GamepadState.encodeThumbAxis(lastSent.thumbLX) ||
            GamepadState.encodeThumbAxis(current.thumbLY) != GamepadState.encodeThumbAxis(lastSent.thumbLY) ||
            GamepadState.encodeThumbAxis(current.thumbRX) != GamepadState.encodeThumbAxis(lastSent.thumbRX) ||
            GamepadState.encodeThumbAxis(current.thumbRY) != GamepadState.encodeThumbAxis(lastSent.thumbRY) ||
            quantizeTrigger(current.triggerL) != quantizeTrigger(lastSent.triggerL) ||
            quantizeTrigger(current.triggerR) != quantizeTrigger(lastSent.triggerR) ||
            current.buttons != lastSent.buttons ||
            !current.dpad.contentEquals(lastSent.dpad)
    }
}
