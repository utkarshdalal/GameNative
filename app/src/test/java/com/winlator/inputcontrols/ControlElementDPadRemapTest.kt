package com.winlator.inputcontrols

import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ControlElementDPadRemapTest {
    private data class Fixture(val element: ControlElement, val state: GamepadState)

    private fun mixedGamepadBindingOrders() = listOf(
        listOf(Binding.GAMEPAD_LEFT_THUMB_RIGHT, Binding.KEY_E),
        listOf(Binding.KEY_E, Binding.GAMEPAD_LEFT_THUMB_RIGHT),
    )

    private fun captureRightAxisEvents(view: InputControlsView): MutableList<Pair<Boolean, Float>> {
        val events = mutableListOf<Pair<Boolean, Float>>()
        doAnswer { invocation ->
            if (invocation.getArgument<Binding>(0) == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                events += invocation.getArgument<Boolean>(1) to invocation.getArgument<Float>(2)
            }
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())
        return events
    }

    private fun stickRightOffset(bindings: List<Binding>): Float {
        val view = mock<InputControlsView>()
        val events = captureRightAxisEvents(view)
        whenever(view.snappingSize).thenReturn(10)
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.STICK)
            setX(100)
            setY(100)
            setBindingComboAt(1, BindingCombo.fromBindings(bindings))
        }

        assertTrue(element.handleTouchDown(1, 115f, 100f))
        return events.last { it.first }.second
    }

    private fun trackpadRightOffset(bindings: List<Binding>): Float {
        val touchpad = mock<TouchpadView>()
        val view = mock<InputControlsView>()
        val events = captureRightAxisEvents(view)
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(view.xServer).thenReturn(mock<XServer>())
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0f, 0f),
            floatArrayOf(2f, 0f),
        )
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
            setBindingComboAt(1, BindingCombo.fromBindings(bindings))
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        events.clear()
        assertTrue(element.handleTouchMove(1, 102f, 100f))
        return events.last { it.first }.second
    }

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

    @Test
    fun `trackpad mouse movement combo dispatches companion action`() {
        val events = mutableListOf<Pair<Binding, Boolean>>()
        val offsets = mutableListOf<Pair<Binding, Float>>()
        val touchpad = mock<TouchpadView>()
        val xServer = mock<XServer>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(view.xServer).thenReturn(xServer)
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0f, 0f),
            floatArrayOf(7f, 0f),
            floatArrayOf(0f, 0f),
        )
        doAnswer { invocation ->
            val binding = invocation.getArgument<Binding>(0)
            events += binding to invocation.getArgument<Boolean>(1)
            offsets += binding to invocation.getArgument<Float>(2)
            null
        }.whenever(view).handleInputEvent(any<Binding>(), any(), any())

        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(
                    listOf(
                        Binding.KEY_CTRL_L,
                        Binding.GAMEPAD_LEFT_THUMB_RIGHT,
                        Binding.MOUSE_MOVE_RIGHT,
                    ),
                ),
            )
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(element.handleTouchMove(1, 107f, 100f))
        assertEquals(
            listOf(Binding.KEY_CTRL_L to true, Binding.GAMEPAD_LEFT_THUMB_RIGHT to true),
            events,
        )
        assertEquals(1f, offsets.first { it.first == Binding.GAMEPAD_LEFT_THUMB_RIGHT }.second, 0f)
        verify(xServer).injectPointerMoveDelta(any(), any())

        assertTrue(element.handleTouchMove(1, 107f, 100f))
        assertEquals(
            listOf(
                Binding.KEY_CTRL_L to true,
                Binding.GAMEPAD_LEFT_THUMB_RIGHT to true,
                Binding.GAMEPAD_LEFT_THUMB_RIGHT to false,
                Binding.KEY_CTRL_L to false,
            ),
            events,
        )
    }

    @Test
    fun `stick mixed combo scaling does not depend on binding order`() {
        val baseline = stickRightOffset(listOf(Binding.GAMEPAD_LEFT_THUMB_RIGHT))
        val scaledOffsets = mixedGamepadBindingOrders().map(::stickRightOffset)

        scaledOffsets.forEach { assertEquals(baseline, it, 0f) }
        assertTrue(baseline in 0f..1f)
    }

    @Test
    fun `trackpad mixed combo interpolation does not depend on binding order`() {
        val baseline = trackpadRightOffset(listOf(Binding.GAMEPAD_LEFT_THUMB_RIGHT))
        val interpolatedOffsets = mixedGamepadBindingOrders().map(::trackpadRightOffset)

        interpolatedOffsets.forEach { assertEquals(baseline, it, 0f) }
        assertTrue(baseline in 0f..1f)
    }

    @Test
    fun `stick releases sub-threshold mixed axis on touch up`() {
        val view = mock<InputControlsView>()
        val events = captureRightAxisEvents(view)
        whenever(view.snappingSize).thenReturn(10)
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.STICK)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(listOf(Binding.GAMEPAD_LEFT_THUMB_RIGHT, Binding.KEY_E)),
            )
        }

        assertTrue(element.handleTouchDown(1, 102f, 100f))
        assertTrue(events.last().first)
        assertTrue(events.last().second > 0f)
        assertTrue(element.handleTouchUp(1))
        assertEquals(false, events.last().first)
        assertEquals(0f, events.last().second, 0f)
    }

    @Test
    fun `trackpad releases sub-threshold mixed axis on cancellation`() {
        val touchpad = mock<TouchpadView>()
        val view = mock<InputControlsView>()
        val events = captureRightAxisEvents(view)
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(view.xServer).thenReturn(mock<XServer>())
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0.5f, 0f),
        )
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(listOf(Binding.GAMEPAD_LEFT_THUMB_RIGHT, Binding.KEY_E)),
            )
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(events.last().first)
        assertTrue(events.last().second > 0f)
        assertTrue(element.cancelTouch())
        assertEquals(false, events.last().first)
        assertEquals(0f, events.last().second, 0f)
    }

    @Test
    fun `digital gamepad combo waits for stick direction transition`() {
        val states = mutableListOf<Boolean>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        doAnswer { invocation ->
            states += invocation.getArgument<Boolean>(1)
            null
        }.whenever(view).handleInputEvent(any<BindingCombo>(), any(), any())
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.STICK)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(listOf(Binding.GAMEPAD_BUTTON_A, Binding.KEY_E)),
            )
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(states.isEmpty())
        assertTrue(element.handleTouchMove(1, 115f, 100f))
        assertTrue(element.handleTouchMove(1, 116f, 100f))
        assertTrue(element.handleTouchMove(1, 100f, 100f))
        assertEquals(listOf(true, false), states)
    }

    @Test
    fun `stick sequence fires once per directional activation`() {
        val states = mutableListOf<Boolean>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        doAnswer { invocation ->
            states += invocation.getArgument<Boolean>(1)
            null
        }.whenever(view).handleInputEvent(any<BindingCombo>(), any(), any())
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.STICK)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(
                    listOf(Binding.KEY_E, Binding.GAMEPAD_BUTTON_A),
                    BindingCombo.Mode.SEQUENCE,
                ),
            )
        }

        assertTrue(element.handleTouchDown(1, 115f, 100f))
        assertTrue(element.handleTouchMove(1, 116f, 100f))
        assertTrue(element.handleTouchMove(1, 100f, 100f))
        assertTrue(element.handleTouchMove(1, 115f, 100f))
        assertEquals(listOf(true, false, true), states)
    }

    @Test
    fun `trackpad sequence fires once per directional activation`() {
        val states = mutableListOf<Boolean>()
        val touchpad = mock<TouchpadView>()
        val view = mock<InputControlsView>()
        whenever(view.snappingSize).thenReturn(10)
        whenever(view.touchpadView).thenReturn(touchpad)
        whenever(view.xServer).thenReturn(mock<XServer>())
        whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenReturn(
            floatArrayOf(0f, 0f),
            floatArrayOf(2f, 0f),
            floatArrayOf(2f, 0f),
            floatArrayOf(0f, 0f),
            floatArrayOf(2f, 0f),
        )
        doAnswer { invocation ->
            states += invocation.getArgument<Boolean>(1)
            null
        }.whenever(view).handleInputEvent(any<BindingCombo>(), any(), any())
        val element = ControlElement(view).apply {
            setType(ControlElement.Type.TRACKPAD)
            setX(100)
            setY(100)
            setBindingComboAt(
                1,
                BindingCombo.fromBindings(
                    listOf(Binding.KEY_E, Binding.GAMEPAD_BUTTON_A),
                    BindingCombo.Mode.SEQUENCE,
                ),
            )
        }

        assertTrue(element.handleTouchDown(1, 100f, 100f))
        assertTrue(states.isEmpty())
        assertTrue(element.handleTouchMove(1, 102f, 100f))
        assertTrue(element.handleTouchMove(1, 104f, 100f))
        assertTrue(element.handleTouchMove(1, 104f, 100f))
        assertTrue(element.handleTouchMove(1, 106f, 100f))
        assertEquals(listOf(true, false, true), states)
    }
}
