package app.gamenative.display

import com.winlator.xserver.Pointer
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer

/** Gamescope-mode input retarget: Android-injected input is forwarded to gamescope's wayland seat via the gndrm injector (installed with XServer.setInputEventSink); X11 mode installs none. */
class GamescopeInputSink(private val screenInfo: ScreenInfo) : XServer.InputEventSink {
    /** The guest cursor, as this sink has moved it: it starts where the seat's does, and stops at the screen edges as the seat's does. */
    private var cursorX = 0
    private var cursorY = 0

    override fun pointerMove(x: Int, y: Int) {
        val targetX = x.coerceIn(0, screenInfo.width - 1)
        val targetY = y.coerceIn(0, screenInfo.height - 1)
        val dx = targetX - cursorX
        val dy = targetY - cursorY
        cursorX = targetX
        cursorY = targetY
        if (dx != 0 || dy != 0) GameNativeDrm.nativePointerMotion(dx, dy)
    }

    override fun pointerMoveDelta(dx: Int, dy: Int) {
        cursorX = (cursorX + dx).coerceIn(0, screenInfo.width - 1)
        cursorY = (cursorY + dy).coerceIn(0, screenInfo.height - 1)
        GameNativeDrm.nativePointerMotion(dx, dy)
    }

    /** What the seat holds. Callers repeat presses (stick axes, key auto-repeat) and release once; the seat counts every press, so only state changes go through. */
    private val pressedKeys = HashSet<Int>()
    private val pressedButtons = HashSet<Int>()

    override fun pointerButton(button: Pointer.Button, pressed: Boolean) {
        when (button) {
            Pointer.Button.BUTTON_LEFT -> sendButton(BTN_LEFT, pressed)
            Pointer.Button.BUTTON_MIDDLE -> sendButton(BTN_MIDDLE, pressed)
            Pointer.Button.BUTTON_RIGHT -> sendButton(BTN_RIGHT, pressed)
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
        sendKey(evdevCode(xKeycode), true)
    }

    override fun keyRelease(xKeycode: XKeycode) {
        sendKey(evdevCode(xKeycode), false)
    }

    /** Synchronized so a press and a release from different threads reach the seat in the order the state changed. */
    @Synchronized
    private fun sendKey(code: Int, pressed: Boolean) {
        if (if (pressed) pressedKeys.add(code) else pressedKeys.remove(code)) {
            GameNativeDrm.nativeKey(code, pressed)
        }
    }

    @Synchronized
    private fun sendButton(code: Int, pressed: Boolean) {
        if (if (pressed) pressedButtons.add(code) else pressedButtons.remove(code)) {
            GameNativeDrm.nativePointerButton(code, pressed)
        }
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
