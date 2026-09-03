package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementTest {
    @Test
    fun `cancelling a selected gyro button releases its dispatched binding`() {
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        val element = ControlElement(view).apply {
            setX(50)
            setY(50)
            setToggleSwitch(true)
            setSelected(true)
            setBindingAt(0, Binding.GYRO_MODIFIER)
        }

        assertTrue(element.handleTouchDown(7, 50f, 50f))
        assertTrue(element.cancelTouch())

        verify(view).handleInputEvent(eq(Binding.GYRO_MODIFIER), eq(true), eq(0f), any())
        verify(view).handleInputEvent(eq(Binding.GYRO_MODIFIER), eq(false), eq(0f), any())
    }
}
