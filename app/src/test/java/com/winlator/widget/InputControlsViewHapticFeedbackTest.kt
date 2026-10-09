package com.winlator.widget

import android.content.Context
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.ControlElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class InputControlsViewHapticFeedbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val view = InputControlsView(context)
    private val vibrator = shadowOf(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)

    @Test
    fun `touching a control with haptic feedback vibrates the device`() {
        vibrator.setHasAmplitudeControl(true)
        val element = ControlElement(view).apply { isHapticFeedback = true }

        view.performTouchFeedback(element)

        assertTrue(vibrator.isVibrating)
        assertEquals(25L, vibrator.milliseconds)
    }

    @Test
    fun `without amplitude control a weaker strength gives a shorter pulse`() {
        vibrator.setHasAmplitudeControl(false)
        val element = ControlElement(view).apply {
            isHapticFeedback = true
            hapticStrength = 40
        }

        view.performTouchFeedback(element)
        assertEquals(10L, vibrator.milliseconds)

        // A pulse shorter than 5 ms isn't felt, so the lowest strength stops there
        element.hapticStrength = ControlElement.MIN_HAPTIC_STRENGTH
        view.performTouchFeedback(element)
        assertEquals(5L, vibrator.milliseconds)
    }

    @Test
    fun `other controls keep the system touch feedback`() {
        view.performTouchFeedback(ControlElement(view))

        assertFalse(vibrator.isVibrating)
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, shadowOf(view).lastHapticFeedbackPerformed())
    }
}
