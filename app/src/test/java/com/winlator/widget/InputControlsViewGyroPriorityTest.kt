package com.winlator.widget

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.GyroSettings
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.math.XForm
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.times
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@RunWith(RobolectricTestRunner::class)
class InputControlsViewGyroPriorityTest {
    @Test
    fun mousePriorityTracksAbsoluteTouchAndStylusMovement() {
        val server = mock<XServer>()
        val view = mock<TouchpadView>(defaultAnswer = CALLS_REAL_METHODS)
        for ((field, value) in listOf("xServer" to server, "xform" to XForm.getInstance())) {
            TouchpadView::class.java.getDeclaredField(field).apply { isAccessible = true }.set(view, value)
        }
        val priority = GyroInputPriority()
        priority.setSettings(GyroSettings(mode = GyroSettings.MODE_MOUSE, inputPriority = GyroSettings.PRIORITY_STICK_TOUCH))
        var now = 1000L
        view.setMouseMovementListener { x, y -> priority.onTouchMouseMovement(x, y, now) }
        val touch = TouchpadView::class.java.getDeclaredMethod("moveCursorTo", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        touch.invoke(view, 10, 0)
        assertFalse(priority.allowGyroMouse(now))
        now += GyroInputPriority.TOUCH_MOTION_GRACE_MS
        touch.invoke(view, 10, 0)
        assertTrue(priority.allowGyroMouse(now))
        val stylus = TouchpadView::class.java.getDeclaredMethod("handleStylusMove", MotionEvent::class.java).apply { isAccessible = true }
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 20f, 0f, 0)
        try {
            stylus.invoke(view, event)
            assertFalse(priority.allowGyroMouse(now))
            now += GyroInputPriority.TOUCH_MOTION_GRACE_MS
            stylus.invoke(view, event)
            assertTrue(priority.allowGyroMouse(now))
        } finally { event.recycle() }
        verify(server, times(2)).injectPointerMove(10, 0)
        verify(server, times(2)).injectPointerMove(20, 0)
        val first = Any()
        val second = Any()
        priority.setManualMouseMoving(first, true)
        priority.setManualMouseMoving(second, true)
        priority.setManualMouseMoving(first, false)
        assertFalse(priority.allowGyroMouse(now))
        priority.setSettings(GyroSettings(mode = GyroSettings.MODE_MOUSE, inputPriority = GyroSettings.PRIORITY_COMBINED))
        assertTrue(priority.allowGyroMouse(now))
        priority.reset()
        priority.setSettings(GyroSettings(mode = GyroSettings.MODE_MOUSE, inputPriority = GyroSettings.PRIORITY_STICK_TOUCH))
        assertTrue(priority.allowGyroMouse(now))
    }

    private fun gyro(view: InputControlsView, right: Boolean, x: Float, y: Float) {
        InputControlsView::class.java.getDeclaredMethod(
            "updateGyroStick", Boolean::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        ).apply { isAccessible = true }.invoke(view, right, x, y)
    }

    @Test
    fun combinedAndStickPriorityPreserveBothAxesDuringHandoffs() {
        for (priority in listOf(GyroSettings.PRIORITY_COMBINED, GyroSettings.PRIORITY_STICK_TOUCH)) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val view = InputControlsView(context)
            val profile = ControlsProfile(context, 1)
            view.setProfile(profile)
            view.setGyroSettings(GyroSettings(mode = GyroSettings.MODE_RIGHT_STICK, inputPriority = priority))
            val state = profile.gamepadState
            val stickWins = priority == GyroSettings.PRIORITY_STICK_TOUCH
            gyro(view, true, -0.6f, 0.4f)
            view.handleInputEvent(Binding.GAMEPAD_RIGHT_THUMB_RIGHT, true, 0.6f)
            assertEquals(if (stickWins) 0.6f else 0f, state.thumbRX, 0f)
            assertEquals(if (stickWins) 0f else 0.4f, state.thumbRY, 0f)
            gyro(view, true, -0.3f, 0.2f)
            assertEquals(if (stickWins) 0.6f else 0.3f, state.thumbRX, 0.0001f)
            assertEquals(if (stickWins) 0f else 0.2f, state.thumbRY, 0f)
            view.handleInputEvent(Binding.GAMEPAD_RIGHT_THUMB_RIGHT, false, 0f)
            assertEquals(-0.3f, state.thumbRX, 0f)
            assertEquals(0.2f, state.thumbRY, 0f)
            view.setGyroOverlaySuppressed(true)
            assertEquals(0f, state.thumbRX, 0f)
            assertEquals(0f, state.thumbRY, 0f)
        }
    }
}
