package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementHeldInputTest {
    private data class Event(val binding: Binding, val down: Boolean, val offset: Float)

    private fun recordingView(): Pair<InputControlsView, MutableList<Event>> {
        val events = mutableListOf<Event>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        doAnswer { invocation ->
            events += Event(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())
        doAnswer { invocation ->
            events += Event(invocation.getArgument(0), invocation.getArgument(1), 0f)
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any())
        return view to events
    }

    private fun element(view: InputControlsView, type: ControlElement.Type, slot: Int, binding: Binding) =
        ControlElement(view).apply {
            setType(type)
            setX(100)
            setY(100)
            setBinding(Binding.NONE) // only the slot under test is bound
            setBindingAt(slot, binding)
        }

    @Test
    fun `d-pad key is pressed once while the finger moves within its direction`() {
        val (view, events) = recordingView()
        // D-pad radius is 70 here: y = 58 is 0.6 up.
        val dpad = element(view, ControlElement.Type.D_PAD, 0, Binding.KEY_W)

        assertTrue(dpad.handleTouchDown(1, 100f, 58f))
        assertTrue(dpad.handleTouchMove(1, 101f, 57f))
        assertTrue(dpad.handleTouchMove(1, 99f, 59f))
        assertTrue(dpad.handleTouchUp(1))

        assertEquals(listOf(Binding.KEY_W to true, Binding.KEY_W to false), events.map { it.binding to it.down })
    }

    @Test
    fun `d-pad stick binding keeps following the finger`() {
        val (view, events) = recordingView()
        val dpad = element(view, ControlElement.Type.D_PAD, 0, Binding.GAMEPAD_LEFT_THUMB_UP)

        assertTrue(dpad.handleTouchDown(1, 100f, 58f))
        assertTrue(dpad.handleTouchMove(1, 100f, 44f))

        val presses = events.filter { it.down }
        assertEquals(2, presses.size)
        assertEquals(-0.6f, presses[0].offset, 0.001f)
        assertEquals(-0.8f, presses[1].offset, 0.001f)
    }

    @Test
    fun `stick key releases only below the release threshold`() {
        val (view, events) = recordingView()
        // Stick radius is 60 here: x = 112 is 0.2 right, 107.2 is 0.12, 103 is 0.05.
        val stick = element(view, ControlElement.Type.STICK, 1, Binding.KEY_D)

        assertTrue(stick.handleTouchDown(1, 112f, 100f))
        assertEquals(listOf(Binding.KEY_D to true), events.map { it.binding to it.down })

        assertTrue(stick.handleTouchMove(1, 107.2f, 100f))
        assertEquals(listOf(Binding.KEY_D to true), events.map { it.binding to it.down })

        assertTrue(stick.handleTouchMove(1, 103f, 100f))
        assertEquals(listOf(Binding.KEY_D to true, Binding.KEY_D to false), events.map { it.binding to it.down })
    }

    @Test
    fun `tuned stick keys press past a small margin and release only at center`() {
        // Digital: press above TUNED_STICK_PRESS_THRESHOLD, hold down to any deflection.
        assertFalse(ControlElement.isStickDirectionActive(0.02f, false, true, true))
        assertTrue(ControlElement.isStickDirectionActive(0.05f, false, true, true))
        assertTrue(ControlElement.isStickDirectionActive(0.01f, true, true, true))
        assertFalse(ControlElement.isStickDirectionActive(0f, true, true, true))
        // Analog: follows any deflection, the user's dead zone being already removed.
        assertTrue(ControlElement.isStickDirectionActive(0.01f, false, false, true))
        assertFalse(ControlElement.isStickDirectionActive(0f, false, false, true))
        // Untuned: the fixed dead zone and release threshold.
        assertFalse(ControlElement.isStickDirectionActive(0.12f, false, true, false))
        assertTrue(ControlElement.isStickDirectionActive(0.12f, true, true, false))
        assertFalse(ControlElement.isStickDirectionActive(0.12f, false, false, false))
    }
}
