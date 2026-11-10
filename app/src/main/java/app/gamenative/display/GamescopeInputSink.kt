package app.gamenative.display

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/** Gamescope-mode input retarget: Android-injected input is forwarded to gamescope's wayland seat via the gndrm injector (installed with XServer.setInputEventSink); X11 mode installs none. */
class GamescopeInputSink : XServer.InputEventSink, Pointer.OnPointerMotionListener {
    /** Last guest cursor position known to Xwayland. */
    private var cursorX = 0
    private var cursorY = 0

    override fun onAttached(xServer: XServer) {
        cursorX = xServer.pointer.x.toInt()
        cursorY = xServer.pointer.y.toInt()
        xServer.pointer.addOnPointerMotionListener(this)
    }

    override fun onDetached(xServer: XServer) {
        xServer.pointer.removeOnPointerMotionListener(this)
    }

    /** Keep absolute Android input based on game-side XWarpPointer changes. */
    override fun onPointerMove(x: Short, y: Short) {
        cursorX = x.toInt()
        cursorY = y.toInt()
    }

    override fun pointerMove(x: Int, y: Int) {
        val dx = x - cursorX
        val dy = y - cursorY
        cursorX = x
        cursorY = y
        if (dx != 0 || dy != 0) GameNativeDrm.nativePointerMotion(dx, dy)
    }

    override fun pointerMoveDelta(dx: Int, dy: Int) {
        cursorX += dx
        cursorY += dy
        GameNativeDrm.nativePointerMotion(dx, dy)
    }

    override fun pointerButton(button: Pointer.Button, pressed: Boolean) {
        when (button) {
            Pointer.Button.BUTTON_LEFT ->
                GameNativeDrm.nativePointerButton(BTN_LEFT, pressed)
            Pointer.Button.BUTTON_MIDDLE ->
                GameNativeDrm.nativePointerButton(BTN_MIDDLE, pressed)
            Pointer.Button.BUTTON_RIGHT ->
                GameNativeDrm.nativePointerButton(BTN_RIGHT, pressed)
            Pointer.Button.BUTTON_SCROLL_UP ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_VERTICAL, -1f)
            Pointer.Button.BUTTON_SCROLL_DOWN ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_VERTICAL, 1f)
            Pointer.Button.BUTTON_SCROLL_CLICK_LEFT ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_HORIZONTAL, -1f)
            Pointer.Button.BUTTON_SCROLL_CLICK_RIGHT ->
                if (pressed) GameNativeDrm.nativePointerAxis(AXIS_HORIZONTAL, 1f)
        }
    }

    override fun keyPress(xKeycode: XKeycode, keysym: Int) {
        GameNativeDrm.nativeKey(evdevCode(xKeycode), true)
    }

    override fun keyRelease(xKeycode: XKeycode) {
        GameNativeDrm.nativeKey(evdevCode(xKeycode), false)
    }

    private companion object {
        // evdev button codes (<linux/input-event-codes.h>)
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112

        const val AXIS_VERTICAL = 0
        const val AXIS_HORIZONTAL = 1

        /** X11 keycode = evdev keycode + 8 (historical Xorg offset). */
        const val X11_KEYCODE_OFFSET = 8

        /** XKeycode.id is a Byte; mask before widening or codes >127 go negative. */
        private fun evdevCode(xKeycode: XKeycode): Int =
            (xKeycode.id.toInt() and 0xFF) - X11_KEYCODE_OFFSET
    }
}
