package app.gamenative.steamcontroller

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/**
 * The output seam the [ProfileInterpreter] drives. Decoupling the interpreter from [XServer] behind this
 * interface lets the whole mapping engine be unit-tested on the PC (a recording fake sink) by replaying a
 * captured input trace — no device, no Winlator runtime. See docs/AUTOMATION-PLAN.md.
 */
interface ScOutputSink {
    /** Push the current virtual XInput pad state. */
    fun gamepad(state: GamepadState)
    /** Relative mouse motion. */
    fun mouseMove(dx: Int, dy: Int)
    /** Absolute mouse position as a screen fraction (0..1, origin top-left). The sink scales to the X screen. */
    fun mouseMoveAbs(nx: Float, ny: Float)
    /** Mouse button down/up. */
    fun mouseButton(button: Pointer.Button, pressed: Boolean)
    /** Keyboard key down/up. */
    fun key(key: XKeycode, pressed: Boolean)
}

/**
 * Real sink: forwards to GameNative's injection API (virtual pad via WinHandler, mouse/keys via XServer).
 *
 * [gamepadSlot] is the player slot TritonMapper reserved for this session — the same slot it claims rumble
 * for. It is NOT hardcoded to player 1: a physical pad may already own slot 0, and writing there anyway
 * would make both controllers share one gamepad state.
 */
class XServerOutputSink(
    private val xServer: XServer,
    private val gamepadSlot: Int = 0,
) : ScOutputSink {
    override fun gamepad(state: GamepadState) {
        val wh = xServer.winHandler
        wh?.sendVirtualGamepadState(state, gamepadSlot)
        wh?.currentController?.state?.copy(state)
    }

    override fun mouseMove(dx: Int, dy: Int) {
        xServer.injectPointerMoveDelta(dx, dy)
    }

    override fun mouseMoveAbs(nx: Float, ny: Float) {
        val info = xServer.screenInfo ?: return
        val x = (nx.coerceIn(0f, 1f) * (info.width - 1)).toInt()
        val y = (ny.coerceIn(0f, 1f) * (info.height - 1)).toInt()
        xServer.injectPointerMove(x, y)
    }

    override fun mouseButton(button: Pointer.Button, pressed: Boolean) {
        if (pressed) xServer.injectPointerButtonPress(button) else xServer.injectPointerButtonRelease(button)
    }

    override fun key(key: XKeycode, pressed: Boolean) {
        if (pressed) xServer.injectKeyPress(key) else xServer.injectKeyRelease(key)
    }
}
