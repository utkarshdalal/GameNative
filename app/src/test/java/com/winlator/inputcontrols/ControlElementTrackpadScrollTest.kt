package com.winlator.inputcontrols

import android.app.Activity
import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.winlator.widget.InputControlsView
import com.winlator.widget.TouchpadView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import com.winlator.xserver.extensions.XInput2Extension
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric.buildActivity
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ControlElementTrackpadScrollTest {
    private class Fixture {
        val xServer = mock<XServer>()
        val pointer = Pointer(xServer)
        val pointerEvents = mutableListOf<Pair<Pointer.Button, Boolean>>()
        val rawEvents = mutableListOf<Pair<Int, Boolean>>()
        private var delta = floatArrayOf(0f, 0f)
        private var x = 100f
        private var y = 100f
        val view: InputControlsView
        val element: ControlElement

        init {
            // Run the real view -> XServer -> Pointer dispatch without starting a native server.
            ReflectionHelpers.setField(xServer, "pointer", pointer)
            doCallRealMethod().whenever(xServer).injectPointerButtonPress(any())
            doCallRealMethod().whenever(xServer).injectPointerButtonRelease(any())
            pointer.addOnPointerMotionListener(object : Pointer.OnPointerMotionListener {
                override fun onPointerButtonPress(button: Pointer.Button) {
                    pointerEvents += button to true
                }

                override fun onPointerButtonRelease(button: Pointer.Button) {
                    pointerEvents += button to false
                }
            })
            val xi = mock<XInput2Extension>()
            whenever(xServer.getExtension<XInput2Extension>(XInput2Extension.MAJOR_OPCODE.toInt())).thenReturn(xi)
            doAnswer { invocation ->
                rawEvents += invocation.getArgument<Int>(1) to invocation.getArgument<Boolean>(2)
                null
            }.whenever(xi).emitRawButton(any(), any(), any())

            val touchpad = mock<TouchpadView>()
            whenever(touchpad.computeDeltaPoint(any(), any(), any(), any())).thenAnswer { delta }
            view = object : InputControlsView(ApplicationProvider.getApplicationContext()) {
                override fun getSnappingSize() = 10
            }
            view.setTouchpadView(touchpad)
            view.setXServer(xServer)
            element = ControlElement(view).apply {
                setType(ControlElement.Type.TRACKPAD)
                setX(100)
                setY(100)
                setBinding(Binding.NONE)
                setBindingAt(0, Binding.MOUSE_SCROLL_UP)
                setBindingAt(2, Binding.MOUSE_SCROLL_DOWN)
            }
        }

        fun down() {
            delta = floatArrayOf(0f, 0f)
            assertTrue(element.handleTouchDown(1, x, y))
        }

        fun move(dx: Float = 0f, dy: Float) {
            delta = floatArrayOf(dx, dy)
            x += dx
            y += dy
            assertTrue(element.handleTouchMove(1, x, y))
        }

        fun assertPulses(vararg buttons: Pointer.Button) {
            val expected = buttons.flatMap { listOf(it to true, it to false) }
            assertEquals(expected, pointerEvents)
            assertEquals(expected.map { it.first.code().toInt() to it.second }, rawEvents)
            assertFalse(pointer.isButtonPressed(Pointer.Button.BUTTON_SCROLL_UP))
            assertFalse(pointer.isButtonPressed(Pointer.Button.BUTTON_SCROLL_DOWN))
        }
    }

    @Test
    fun `continued upward and downward movement emits complete wheel ticks`() {
        for ((dy, button) in listOf(-2f to Pointer.Button.BUTTON_SCROLL_UP, 2f to Pointer.Button.BUTTON_SCROLL_DOWN)) {
            val fixture = Fixture()
            fixture.down()
            fixture.assertPulses()
            repeat(3) { fixture.move(dy = dy) }
            fixture.assertPulses(button, button, button)
        }
    }

    @Test
    fun `reversing without lifting immediately changes scroll direction`() {
        val fixture = Fixture()
        fixture.down()
        repeat(2) { fixture.move(dy = -2f) }
        repeat(2) { fixture.move(dy = 2f) }
        fixture.move(dy = -2f)
        fixture.assertPulses(
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_DOWN,
            Pointer.Button.BUTTON_SCROLL_DOWN,
            Pointer.Button.BUTTON_SCROLL_UP,
        )
    }

    @Test
    fun `stationary and sub-threshold motion do not scroll and the threshold is inclusive`() {
        val fixture = Fixture()
        fixture.down()
        for (dy in listOf(0f, -0.79f, 0.79f)) fixture.move(dy = dy)
        fixture.assertPulses()
        fixture.move(dy = -ControlElement.TRACKPAD_MIN_SPEED)
        fixture.move(dy = 0f)
        fixture.move(dy = -0.79f)
        fixture.move(dy = ControlElement.TRACKPAD_MIN_SPEED)
        fixture.move(dy = 0f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        fixture.assertPulses(Pointer.Button.BUTTON_SCROLL_UP, Pointer.Button.BUTTON_SCROLL_DOWN)
    }

    @Test
    fun `lifting or cancelling leaves no held wheel or extra release and allows another swipe`() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture()
            fixture.down()
            fixture.move(dy = -2f)
            if (cancel) {
                assertTrue(fixture.element.cancelTouch())
                assertFalse(fixture.element.cancelTouch())
            } else {
                assertTrue(fixture.element.handleTouchUp(1))
            }
            fixture.assertPulses(Pointer.Button.BUTTON_SCROLL_UP)
            fixture.down()
            repeat(2) { fixture.move(dy = -2f) }
            fixture.assertPulses(
                Pointer.Button.BUTTON_SCROLL_UP,
                Pointer.Button.BUTTON_SCROLL_UP,
                Pointer.Button.BUTTON_SCROLL_UP,
            )
        }
    }

    @Test
    fun `wheel bindings also repeat on horizontal trackpad directions`() {
        val fixture = Fixture()
        fixture.element.setBinding(Binding.NONE)
        fixture.element.setBindingAt(1, Binding.MOUSE_SCROLL_UP)
        fixture.element.setBindingAt(3, Binding.MOUSE_SCROLL_DOWN)
        fixture.down()
        repeat(2) { fixture.move(dx = 2f, dy = 0f) }
        repeat(2) { fixture.move(dx = -2f, dy = 0f) }
        fixture.assertPulses(
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_DOWN,
            Pointer.Button.BUTTON_SCROLL_DOWN,
        )
    }

    @Test
    fun `diagonal movement preserves cursor motion alongside wheel ticks`() {
        val fixture = Fixture()
        fixture.element.setBindingAt(1, Binding.MOUSE_MOVE_RIGHT)
        fixture.down()
        repeat(3) { fixture.move(dx = 2f, dy = -2f) }
        fixture.assertPulses(
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_UP,
            Pointer.Button.BUTTON_SCROLL_UP,
        )
        verify(fixture.xServer, times(3)).injectPointerMoveDelta(2, 0)
    }

    @Test
    fun `another finger cannot move or release the active trackpad`() {
        val fixture = Fixture()
        fixture.down()
        fixture.move(dy = -2f)
        assertFalse(fixture.element.handleTouchMove(2, 100f, 90f))
        assertFalse(fixture.element.handleTouchUp(2))
        fixture.move(dy = -2f)
        fixture.assertPulses(Pointer.Button.BUTTON_SCROLL_UP, Pointer.Button.BUTTON_SCROLL_UP)
    }

    @Test
    fun `ordinary keys remain pressed once per directional activation`() {
        val fixture = Fixture()
        fixture.element.setBindingAt(0, Binding.KEY_E)
        fixture.down()
        repeat(3) { fixture.move(dy = -2f) }
        verify(fixture.xServer, times(1)).injectKeyPress(Binding.KEY_E.keycode)
        fixture.move(dy = 0f)
        verify(fixture.xServer, times(1)).injectKeyRelease(Binding.KEY_E.keycode)
        fixture.assertPulses()
    }

    @Test
    fun `simultaneous wheel combos do not repeat their modifier or change release behavior`() {
        val fixture = Fixture()
        fixture.element.setBindingComboAt(0, BindingCombo.fromBindings(listOf(Binding.KEY_CTRL_L, Binding.MOUSE_SCROLL_UP)))
        fixture.down()
        repeat(3) { fixture.move(dy = -2f) }
        verify(fixture.xServer, times(1)).injectKeyPress(Binding.KEY_CTRL_L.keycode)
        assertEquals(listOf(Pointer.Button.BUTTON_SCROLL_UP to true), fixture.pointerEvents)
        fixture.element.cancelTouch()
        verify(fixture.xServer, times(1)).injectKeyRelease(Binding.KEY_CTRL_L.keycode)
        fixture.assertPulses(Pointer.Button.BUTTON_SCROLL_UP)
    }

    @Test
    fun `wheel sequences still execute once per directional activation`() {
        val fixture = Fixture()
        fixture.element.setBindingComboAt(
            0,
            BindingCombo.fromBindings(listOf(Binding.KEY_E, Binding.MOUSE_SCROLL_UP), BindingCombo.Mode.SEQUENCE),
        )
        val activity = buildActivity(Activity::class.java).setup()
        try {
            // View.postDelayed requires an attached view to execute sequence members.
            activity.get().setContentView(fixture.view)
            fixture.down()
            repeat(3) { fixture.move(dy = -2f) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            verify(fixture.xServer, times(1)).injectKeyPress(Binding.KEY_E.keycode)
            verify(fixture.xServer, times(1)).injectKeyRelease(Binding.KEY_E.keycode)
            fixture.assertPulses(Pointer.Button.BUTTON_SCROLL_UP)
            fixture.element.handleTouchUp(1)
        } finally {
            activity.pause().stop().destroy()
        }
    }
}
