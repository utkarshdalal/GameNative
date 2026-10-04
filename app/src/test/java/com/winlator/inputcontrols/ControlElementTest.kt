package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.same
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class ControlElementTest {
    @Test
    fun `continued upward trackpad movement emits complete wheel pulses`() {
        assertTrackpadWheelPulses(Binding.MOUSE_SCROLL_UP, 0, -2f)
    }

    @Test
    fun `continued downward trackpad movement emits complete wheel pulses`() {
        assertTrackpadWheelPulses(Binding.MOUSE_SCROLL_DOWN, 2, 2f)
    }

    private fun assertTrackpadWheelPulses(binding: Binding, direction: Int, dy: Float) {
        val view = mock<InputControlsView>()
        val touchpad = mock<TouchpadView>()
        val events = mutableListOf<Pair<Binding, Boolean>>()
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0f, 0f),
            floatArrayOf(0f, dy),
        )
        doAnswer { invocation ->
            events += invocation.getArgument<Binding>(0) to invocation.getArgument<Boolean>(1)
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
            setBinding(Binding.NONE)
            setBindingAt(direction, binding)
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(events.isEmpty())
        repeat(3) { step ->
            assertTrue(element.handleTouchMove(1, 100f, 100f + (step + 1) * dy))
            assertEquals(
                "$binding movement ${step + 1}",
                listOf(binding to true, binding to false),
                events,
            )
            assertFalse(ReflectionHelpers.getField<BooleanArray>(element, "states")[direction])
            events.clear()
        }
    }

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
