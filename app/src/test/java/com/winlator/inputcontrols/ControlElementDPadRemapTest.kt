package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementDPadRemapTest {
    private data class Fixture(val element: ControlElement, val state: GamepadState)

    private fun fixture(): Fixture {
        val state = GamepadState()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)

        fun applyEvent(binding: Binding, pressed: Boolean, offset: Float) {
            when (binding) {
                Binding.GAMEPAD_LEFT_THUMB_UP,
                Binding.GAMEPAD_LEFT_THUMB_DOWN,
                -> state.thumbLY = if (pressed) offset else 0f
                Binding.GAMEPAD_LEFT_THUMB_LEFT,
                Binding.GAMEPAD_LEFT_THUMB_RIGHT,
                -> state.thumbLX = if (pressed) offset else 0f
                else -> Unit
            }
        }
        doAnswer { invocation ->
            applyEvent(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())
        doAnswer { invocation ->
            applyEvent(invocation.getArgument(0), invocation.getArgument(1), 0f)
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any())

        return Fixture(
            ControlElement(view).apply {
                setType(ControlElement.Type.D_PAD)
                setX(100)
                setY(100)
                setBindingAt(0, Binding.GAMEPAD_LEFT_THUMB_UP)
                setBindingAt(1, Binding.GAMEPAD_LEFT_THUMB_RIGHT)
                setBindingAt(2, Binding.GAMEPAD_LEFT_THUMB_DOWN)
                setBindingAt(3, Binding.GAMEPAD_LEFT_THUMB_LEFT)
            },
            state,
        )
    }

    @Test
    fun `all cardinal directions survive opposing idle bindings`() {
        val directions = listOf(
            Triple(100f to 40f, 0f, -1f),
            Triple(160f to 100f, 1f, 0f),
            Triple(100f to 160f, 0f, 1f),
            Triple(40f to 100f, -1f, 0f),
        )

        directions.forEach { (point, expectedXSign, expectedYSign) ->
            val (element, state) = fixture()
            assertTrue(element.handleTouchDown(1, point.first, point.second))
            assertEquals(expectedXSign, Math.signum(state.thumbLX), 0f)
            assertEquals(expectedYSign, Math.signum(state.thumbLY), 0f)
        }
    }

    @Test
    fun `direct reversals release old direction before applying new direction`() {
        val (element, state) = fixture()

        assertTrue(element.handleTouchDown(1, 100f, 160f))
        assertTrue(state.thumbLY > 0f)
        assertTrue(element.handleTouchMove(1, 100f, 40f))
        assertTrue(state.thumbLY < 0f)
        assertTrue(element.handleTouchMove(1, 160f, 100f))
        assertTrue(state.thumbLX > 0f)
        assertEquals(0f, state.thumbLY, 0f)
        assertTrue(element.handleTouchMove(1, 40f, 100f))
        assertTrue(state.thumbLX < 0f)
    }

    @Test
    fun `lifting finger returns remapped stick to neutral`() {
        val (element, state) = fixture()

        assertTrue(element.handleTouchDown(1, 40f, 40f))
        assertTrue(state.thumbLX < 0f)
        assertTrue(state.thumbLY < 0f)
        assertTrue(element.handleTouchUp(1))
        assertEquals(0f, state.thumbLX, 0f)
        assertEquals(0f, state.thumbLY, 0f)
    }

    @Test
    fun `mouse movement activates only the touched direction and reverses release first`() {
        val events = mutableListOf<Pair<Binding, Boolean>>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        doAnswer { invocation ->
            events += invocation.getArgument<Binding>(0) to invocation.getArgument<Boolean>(1)
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())

        val element = ControlElement(view).apply {
            setType(ControlElement.Type.D_PAD)
            setX(100)
            setY(100)
            setBindingAt(0, Binding.MOUSE_MOVE_UP)
            setBindingAt(2, Binding.MOUSE_MOVE_DOWN)
        }

        assertTrue(element.handleTouchDown(1, 100f, 160f))
        assertTrue(events.contains(Binding.MOUSE_MOVE_DOWN to true))
        assertTrue(events.none { it == Binding.MOUSE_MOVE_UP to true })

        events.clear()
        assertTrue(element.handleTouchMove(1, 100f, 40f))
        assertEquals(
            listOf(Binding.MOUSE_MOVE_DOWN to false, Binding.MOUSE_MOVE_UP to true),
            events,
        )
    }
}
