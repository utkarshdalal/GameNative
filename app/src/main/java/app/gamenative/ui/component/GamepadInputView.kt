package app.gamenative.ui.component

import android.annotation.SuppressLint
import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Invisible focusable view that forwards gamepad input. Compose can't read joystick analog axes
 * (right/left stick) directly, so the screenshot viewer hosts this via AndroidView to get both
 * button [onKey] events and analog-stick [onSticks] motion.
 */
@SuppressLint("ViewConstructor")
class GamepadInputView(context: Context) : View(context) {
    /** Returns true if the key was consumed. */
    var onKey: (keyCode: Int, down: Boolean) -> Boolean = { _, _ -> false }

    /** Left stick (x,y) and right-stick vertical, each deadzoned to [-1f, 1f]. */
    var onSticks: (leftX: Float, leftY: Float, rightY: Float) -> Unit = { _, _, _ -> }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            onSticks(
                deadzone(event.getAxisValue(MotionEvent.AXIS_X)),
                deadzone(event.getAxisValue(MotionEvent.AXIS_Y)),
                deadzone(event.getAxisValue(MotionEvent.AXIS_RZ)), // right-stick vertical on most pads
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        if (onKey(keyCode, true)) true else super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        if (onKey(keyCode, false)) true else super.onKeyUp(keyCode, event)

    private fun deadzone(v: Float): Float = if (abs(v) < 0.15f) 0f else v
}
