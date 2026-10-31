package app.gamenative.ui.screen.xserver

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.XServer

/**
 * Sends profile gamepad state to Wine (WinHandler UDP and shared memory) for touch controls, physical
 * controllers, gyro and the radial menu. WinHandler skips what Wine already has; this only paces stick and
 * trigger motion by [InputThrottling]. Button and d-pad changes never wait for the pacer. Main thread only.
 */
class GamepadStateOutput(
    throttling: InputThrottling,
    private val sender: Sender,
) {
    fun interface Sender {
        fun send(state: GamepadState?)
    }

    companion object {
        /** Sends through the X server's WinHandler: UDP gamepad clients and the shared-memory gamepad. */
        @JvmStatic
        fun winHandlerSender(xServer: () -> XServer?): Sender = Sender { state ->
            xServer()?.winHandler?.let { winHandler ->
                winHandler.sendGamepadState()
                winHandler.sendVirtualGamepadState(state)
            }
        }
    }

    // Paces motion at the throttling rate, on the display frame clock.
    private val pacer = throttling.Pacer()

    // Motion held back by throttling, sent as it is by the first frame on which it is due.
    private var pendingMotion: GamepadState? = null
    private val frameLoop = FrameCallbackLoop(::flushPendingMotion)

    // Buttons (triggers included) and d-pad as last sent: a change there is sent right away, so a tap that
    // starts and ends within one throttling interval is never lost.
    private var sentButtons: Short = 0
    private val sentDpad = BooleanArray(4)

    /** Sends right away: buttons, releases and resets. */
    fun send(state: GamepadState?) {
        // Whatever is sent now is newer than held-back motion, even another instance (a switched profile).
        pendingMotion = null
        frameLoop.cancel()
        if (state != null) {
            sentButtons = state.buttons
            state.dpad.copyInto(sentDpad)
            pacer.onSent(InputThrottling.nowNanos())
        }
        sender.send(state)
    }

    /** Sends stick, trigger or gyro motion at most as often as throttling allows. */
    fun sendMotion(state: GamepadState?) {
        if (state == null) return
        if (!hasButtonChange(state) && !pacer.isDue(InputThrottling.nowNanos())) {
            pendingMotion = state
            frameLoop.schedule()
            return
        }
        send(state)
    }

    private fun flushPendingMotion(frameTimeNanos: Long) {
        val state = pendingMotion ?: return
        if (!pacer.isDue(frameTimeNanos)) {
            frameLoop.schedule()
            return
        }
        send(state)
    }

    private fun hasButtonChange(state: GamepadState): Boolean =
        state.buttons != sentButtons || !state.dpad.contentEquals(sentDpad)
}
