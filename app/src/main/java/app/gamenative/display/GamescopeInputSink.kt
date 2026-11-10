package app.gamenative.display

import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/** Gamescope-mode input retarget: Android-injected input is forwarded to gamescope's wayland seat via the gndrm injector (installed with XServer.setInputEventSink); X11 mode installs none. */
class GamescopeInputSink(private val screenInfo: ScreenInfo) : XServer.InputEventSink {
    /** A position goes out as a position on the screen's extent, so the seat places its own cursor and this side keeps none. */
    override fun pointerMove(x: Int, y: Int) {
        GameNativeDrm.nativePointerAbsolute(
            x.coerceIn(0, screenInfo.width - 1),
            y.coerceIn(0, screenInfo.height - 1),
            screenInfo.width.toInt(),
            screenInfo.height.toInt(),
        )
    }

    override fun pointerMoveDelta(dx: Int, dy: Int) {
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
