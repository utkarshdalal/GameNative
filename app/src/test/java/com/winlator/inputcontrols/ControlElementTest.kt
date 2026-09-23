package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.same
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementTest {
    @Test
    fun `gyro in an unused button slot does not disable toggle behavior`() {
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        val element = ControlElement(view).apply {
            setX(50)
            setY(50)
            setToggleSwitch(true)
            setBindingAt(0, Binding.KEY_E)
            setBindingAt(2, Binding.GYRO_MODIFIER)
        }

        assertTrue(element.handleTouchDown(7, 50f, 50f))
        assertTrue(element.handleTouchUp(7))

        assertTrue(element.isSelected)
    }

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

        val sourceCaptor = argumentCaptor<Any>()
        verify(view).handleInputEvent(eq(Binding.GYRO_MODIFIER), eq(true), eq(0f), sourceCaptor.capture())
        verify(view).handleInputEvent(
            eq(Binding.GYRO_MODIFIER),
            eq(false),
            eq(0f),
            same(sourceCaptor.firstValue),
        )
    }
}
