package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementCancellationTest {
    @Test
    fun `cancelling a held button releases its binding without handling a normal up`() {
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.snappingSize } returns 10
        val element = ControlElement(view).apply {
            setX(50)
            setY(50)
            setBindingAt(0, Binding.KEY_A)
        }

        assertTrue(element.handleTouchDown(7, 50f, 50f))
        assertTrue(element.cancelTouch())
        assertFalse(element.cancelTouch())

        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_A, true) }
        verify(exactly = 1) { view.handleInputEvent(Binding.KEY_A, false) }
    }

    @Test
    fun `cancelling a selected toggle keeps its latched binding active`() {
        val view = mockk<InputControlsView>(relaxed = true)
        every { view.snappingSize } returns 10
        val element = ControlElement(view).apply {
            setX(50)
            setY(50)
            setBindingAt(0, Binding.KEY_A)
            setToggleSwitch(true)
            setSelected(true)
        }

        assertTrue(element.handleTouchDown(8, 50f, 50f))
        assertTrue(element.cancelTouch())

        assertTrue(element.isSelected)
        verify(exactly = 0) { view.handleInputEvent(Binding.KEY_A, true) }
        verify(exactly = 0) { view.handleInputEvent(Binding.KEY_A, false) }
    }
}
