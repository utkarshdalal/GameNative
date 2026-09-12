package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.PluviaApp
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.GamepadState
import com.winlator.widget.InputControlsView
import com.winlator.xserver.Pointer
import com.winlator.xserver.XServer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class PhysicalControllerHandlerTest {
    @Test
    fun `cardinal stick output activates only the selected digital binding`() {
        val deviceId = 42
        val rightSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1)
        val downSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
        val controller = motionController(rightSource, Binding.GAMEPAD_DPAD_RIGHT).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(downSource)
                    setBinding(Binding.GAMEPAD_DPAD_DOWN)
                },
            )
        }
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        val handler = PhysicalControllerHandler(profile, mock<XServer>(), gamepadStateSender = {})
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLX = 0.8f
            controller.state.thumbLY = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            assertTrue(gamepadState.dpad[1])
            assertFalse(gamepadState.dpad[2])

            controller.state.thumbLX = 0f
            controller.state.thumbLY = 0.8f
            assertTrue(handler.onGenericMotionEvent(event))
            assertFalse(gamepadState.dpad[1])
            assertTrue(gamepadState.dpad[2])
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `sub-pixel stick mouse movement accumulates into pointer output`() {
        val deviceId = 42
        val rightSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1)
        val controller = motionController(rightSource, Binding.MOUSE_MOVE_RIGHT).apply {
            state.thumbLX = 0.05f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.isStickTuningConfigured).thenReturn(true)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer, gamepadStateSender = {})

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            // Below a pixel per frame: only the carried remainder moves the pointer.
            shadowOf(Looper.getMainLooper()).idleFor(500, MILLISECONDS)
            verify(xServer, atLeastOnce()).injectPointerMoveDelta(1, 0)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `untuned legacy profile retains the original stick binding threshold`() {
        val deviceId = 42
        val rightSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1)
        val controller = motionController(rightSource, Binding.GAMEPAD_DPAD_RIGHT).apply {
            state.thumbLX = 0.05f
        }
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        val handler = PhysicalControllerHandler(profile, mock<XServer>(), gamepadStateSender = {})

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            assertFalse(gamepadState.dpad[1])
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `tuned stick key presses past a small margin and holds until the stick centers`() {
        val deviceId = 42
        val rightSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1)
        val controller = motionController(rightSource, Binding.GAMEPAD_DPAD_RIGHT)
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        whenever(profile.isStickTuningConfigured).thenReturn(true)
        val handler = PhysicalControllerHandler(profile, mock<XServer>(), gamepadStateSender = {})
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLX = 0.02f
            assertTrue(handler.onGenericMotionEvent(event))
            assertFalse(gamepadState.dpad[1])

            controller.state.thumbLX = 0.05f
            assertTrue(handler.onGenericMotionEvent(event))
            assertTrue(gamepadState.dpad[1])

            controller.state.thumbLX = 0.01f
            assertTrue(handler.onGenericMotionEvent(event))
            assertTrue(gamepadState.dpad[1])

            controller.state.thumbLX = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            assertFalse(gamepadState.dpad[1])
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `vertical physical stick release clears gyro mixed gamepad state`() {
        val deviceId = 42
        val rawDownSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, 1)
        val rawUpSource = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_Y, -1)
        val controller = motionController(rawDownSource, Binding.GAMEPAD_LEFT_THUMB_UP).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(rawUpSource)
                    setBinding(Binding.GAMEPAD_LEFT_THUMB_DOWN)
                },
            )
        }
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        var baseAxis = 0f
        val handler = PhysicalControllerHandler(
            profile = profile,
            xServer = mock<XServer>(),
            gyroStickMixer = { _, isDown, offset, sourceKeyCode ->
                InputControlsView.updatePhysicalBaseAxis(baseAxis, isDown, offset, sourceKeyCode)
                    .also { baseAxis = it }
            },
            gamepadStateSender = {},
        )
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLY = 0.8f
            assertTrue(handler.onGenericMotionEvent(event))
            runInputTicks()
            assertEquals(0.8f, gamepadState.thumbLY, 0f)

            controller.state.thumbLY = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            runInputTicks()
            assertEquals(0f, gamepadState.thumbLY, 0f)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `switching profiles transmits the released old gamepad state`() {
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBinding(Binding.GAMEPAD_BUTTON_A)
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val oldGamepadState = GamepadState()
        val oldProfile = mock<ControlsProfile>()
        whenever(oldProfile.getController(deviceId)).thenReturn(controller)
        whenever(oldProfile.gamepadState).thenReturn(oldGamepadState)
        val newProfile = mock<ControlsProfile>()
        val xServer = mock<XServer>()
        val transmittedButtonStates = mutableListOf<Boolean>()
        val handler = PhysicalControllerHandler(
            profile = oldProfile,
            xServer = xServer,
            gamepadStateSender = { state ->
                transmittedButtonStates.add(
                    state?.isPressed(ExternalController.IDX_BUTTON_A.toInt()) == true,
                )
            },
        )
        val downEvent = mock<KeyEvent>()
        whenever(downEvent.repeatCount).thenReturn(0)
        whenever(downEvent.deviceId).thenReturn(deviceId)
        whenever(downEvent.keyCode).thenReturn(keyCode)
        whenever(downEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        try {
            assertTrue(handler.onKeyEvent(downEvent))
            assertTrue(oldGamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
            transmittedButtonStates.clear()

            handler.setProfile(newProfile)

            assertFalse(oldGamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
            assertEquals(listOf(false), transmittedButtonStates)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `opening radial menu releases matching axes from other controllers`() {
        val radialDeviceId = 41
        val otherDeviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val radialController = motionController(axisKeyCode, Binding.OPEN_RADIAL_MENU).apply {
            state.thumbLX = 1f
        }
        val otherController = motionController(axisKeyCode, Binding.KEY_E).apply {
            state.thumbLX = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(radialDeviceId)).thenReturn(radialController)
        whenever(profile.getController(otherDeviceId)).thenReturn(otherController)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val otherMotion = motionEvent(otherDeviceId)
        val radialMotion = motionEvent(radialDeviceId)

        try {
            assertTrue(handler.onGenericMotionEvent(otherMotion))
            runInputTicks()
            assertTrue(handler.onGenericMotionEvent(radialMotion))
            runInputTicks()

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `radial menu accepts tuned stick movement below the legacy deadzone`() {
        val deviceId = 42
        val controller = motionController(KeyEvent.KEYCODE_BUTTON_A, Binding.OPEN_RADIAL_MENU).apply {
            state.thumbRX = 0.05f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.isStickTuningConfigured).thenReturn(true)
        val vectors = mutableListOf<Pair<Float, Float>>()
        val handler = PhysicalControllerHandler(
            profile = profile,
            xServer = mock<XServer>(),
            onRadialMenuVectorChanged = { x, y -> vectors.add(x to y) },
        )
        val openEvent = mock<KeyEvent>()
        whenever(openEvent.repeatCount).thenReturn(0)
        whenever(openEvent.deviceId).thenReturn(deviceId)
        whenever(openEvent.keyCode).thenReturn(KeyEvent.KEYCODE_BUTTON_A)
        whenever(openEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        try {
            assertTrue(handler.onKeyEvent(openEvent))
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))

            assertEquals(0.05f, vectors.last().first, 0f)
            assertEquals(0f, vectors.last().second, 0f)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `removing a controller releases its held digital bindings`() {
        val deviceId = 42
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val bindingCombo = BindingCombo.fromBindings(
            listOf(Binding.KEY_E, Binding.MOUSE_LEFT_BUTTON, Binding.GAMEPAD_BUTTON_A),
        )
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(bindingCombo)
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val gamepadState = GamepadState()
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.gamepadState).thenReturn(gamepadState)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val downEvent = mock<KeyEvent>()
        whenever(downEvent.repeatCount).thenReturn(0)
        whenever(downEvent.deviceId).thenReturn(deviceId)
        whenever(downEvent.keyCode).thenReturn(keyCode)
        whenever(downEvent.action).thenReturn(KeyEvent.ACTION_DOWN)

        try {
            assertTrue(handler.onKeyEvent(downEvent))
            assertTrue(gamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))

            handler.onInputDeviceRemoved(deviceId)

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
            verify(xServer).injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            verify(xServer).injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
            assertFalse(gamepadState.isPressed(ExternalController.IDX_BUTTON_A.toInt()))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `removing a controller releases its held analog trigger binding`() {
        val deviceId = 42
        val controller = motionController(KeyEvent.KEYCODE_BUTTON_L2, Binding.KEY_E).apply {
            state.triggerL = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            runInputTicks()
            handler.onInputDeviceRemoved(deviceId)

            verify(xServer).injectKeyPress(XKeycode.KEY_E)
            verify(xServer).injectKeyRelease(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `sequence releases mouse movement without another motion event`() {
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val sequenceDelayMs = 150
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(
                BindingCombo.fromBindings(
                    listOf(Binding.MOUSE_MOVE_RIGHT, Binding.KEY_E),
                    BindingCombo.Mode.SEQUENCE,
                    sequenceDelayMs,
                ),
            )
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(KeyEvent(KeyEvent.ACTION_DOWN, keyCode).deviceId)).thenReturn(controller)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val handler = PhysicalControllerHandler(profile, mock<XServer>())

        try {
            handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            shadowOf(Looper.getMainLooper()).idleFor(1, MILLISECONDS)
            assertEquals(1f, mouseMoveOffset(handler).x, 0f)

            shadowOf(Looper.getMainLooper()).idleFor(sequenceDelayMs.toLong() - 1, MILLISECONDS)
            assertEquals(0f, mouseMoveOffset(handler).x, 0f)
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `stick sequence binding fires once per deflection, not on every tick`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(
            axisKeyCode,
            BindingCombo.fromBindings(listOf(Binding.MOUSE_MOVE_RIGHT, Binding.KEY_E), BindingCombo.Mode.SEQUENCE, 100),
        )
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val event = motionEvent(deviceId)

        try {
            for (value in listOf(0.5f, 0.8f, 1f)) {
                controller.state.thumbLX = value
                assertTrue(handler.onGenericMotionEvent(event))
                runInputTicks()
            }
            shadowOf(Looper.getMainLooper()).idleFor(300, MILLISECONDS)

            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `held stick keeps updating the analog part of a combo without re-pressing its key`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(
            axisKeyCode,
            BindingCombo.fromBindings(listOf(Binding.MOUSE_MOVE_RIGHT, Binding.KEY_E)),
        )
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val event = motionEvent(deviceId)

        try {
            for (value in listOf(0.5f, 0.8f, 1f)) {
                controller.state.thumbLX = value
                assertTrue(handler.onGenericMotionEvent(event))
                runInputTicks()
                assertEquals(value, mouseMoveOffset(handler).x, 0f)
            }

            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `stick key releases when the stick jumps past the center in one event`() {
        val deviceId = 42
        val rightKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val leftKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, (-1).toByte())
        val controller = motionController(rightKeyCode, Binding.KEY_D).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(leftKeyCode)
                    setBinding(Binding.KEY_A)
                },
            )
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLX = 0.2f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer).injectKeyPress(XKeycode.KEY_D)

            // Left of center but inside the dead zone: D is released, A is not pressed.
            controller.state.thumbLX = -0.12f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer).injectKeyRelease(XKeycode.KEY_D)
            verify(xServer, times(0)).injectKeyPress(XKeycode.KEY_A)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `held stick is pressed again after the radial menu closes`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val radialKeyCode = KeyEvent.KEYCODE_BUTTON_L1
        val controller = motionController(axisKeyCode, Binding.KEY_E).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(radialKeyCode)
                    setBinding(Binding.OPEN_RADIAL_MENU)
                },
            )
            state.thumbLX = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            runInputTicks()
            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)

            assertTrue(handler.onKeyEvent(keyEvent(deviceId, radialKeyCode, KeyEvent.ACTION_DOWN)))
            verify(xServer, times(1)).injectKeyRelease(XKeycode.KEY_E)

            // No new MotionEvent: the stick didn't move while the menu was open.
            assertTrue(handler.onKeyEvent(keyEvent(deviceId, radialKeyCode, KeyEvent.ACTION_UP)))
            runInputTicks()
            verify(xServer, times(2)).injectKeyPress(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `held trigger key is pressed again after the radial menu closes`() {
        val deviceId = 42
        val radialKeyCode = KeyEvent.KEYCODE_BUTTON_L1
        val controller = motionController(KeyEvent.KEYCODE_BUTTON_L2, Binding.KEY_E).apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(radialKeyCode)
                    setBinding(Binding.OPEN_RADIAL_MENU)
                },
            )
            state.triggerL = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)

            assertTrue(handler.onKeyEvent(keyEvent(deviceId, radialKeyCode, KeyEvent.ACTION_DOWN)))
            verify(xServer, times(1)).injectKeyRelease(XKeycode.KEY_E)

            // The trigger is still held when the menu closes.
            assertTrue(handler.onKeyEvent(keyEvent(deviceId, radialKeyCode, KeyEvent.ACTION_UP)))
            runInputTicks()
            verify(xServer, times(2)).injectKeyPress(XKeycode.KEY_E)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `releasing all input is not undone by stale stick state`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(axisKeyCode, Binding.MOUSE_MOVE_RIGHT).apply {
            state.thumbLX = 1f
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val handler = PhysicalControllerHandler(profile, mock<XServer>())

        try {
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            runInputTicks()
            assertEquals(1f, mouseMoveOffset(handler).x, 0f)

            // e.g. quick menu opened; controller.state is stale from here on.
            handler.releaseAllActiveInput()
            runInputTicks()
            assertEquals(0f, mouseMoveOffset(handler).x, 0f)

            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            runInputTicks()
            assertEquals(1f, mouseMoveOffset(handler).x, 0f)
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `non-joystick motion is left to the fallbacks and does not wake the frame loop`() {
        val deviceId = 42
        val controller = object : ExternalController() {
            override fun updateStateFromMotionEvent(event: MotionEvent): Boolean = false
        }
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val handler = PhysicalControllerHandler(profile, mock<XServer>())

        try {
            assertFalse(handler.onGenericMotionEvent(motionEvent(deviceId)))
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `motion is dispatched synchronously with throttling off, the default`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(axisKeyCode, Binding.KEY_E)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLX = 1f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)

            controller.state.thumbLX = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer, times(1)).injectKeyRelease(XKeycode.KEY_E)
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `throttled motion inside the interval is dispatched on a later frame, not dropped`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(axisKeyCode, Binding.KEY_E)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer, throttling = InputThrottling().apply { enabled = true })
        val event = motionEvent(deviceId)

        try {
            // First motion after idle goes out right away.
            controller.state.thumbLX = 1f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)

            // Same instant: inside the throttle interval, so it waits for the first frame it is due on.
            controller.state.thumbLX = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            verify(xServer, times(0)).injectKeyRelease(XKeycode.KEY_E)
            assertTrue(frameScheduled(handler))

            runInputTicks()
            verify(xServer, times(1)).injectKeyRelease(XKeycode.KEY_E)
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `held mouse-look steps on display frames and the loop parks on release`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(axisKeyCode, Binding.MOUSE_MOVE_RIGHT)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)
        val event = motionEvent(deviceId)

        try {
            controller.state.thumbLX = 1f
            assertTrue(handler.onGenericMotionEvent(event))
            assertTrue(frameScheduled(handler))
            runInputTicks()
            verify(xServer, atLeastOnce()).injectPointerMoveDelta(anyInt(), anyInt())

            controller.state.thumbLX = 0f
            assertTrue(handler.onGenericMotionEvent(event))
            runInputTicks()
            assertEquals(0f, mouseMoveOffset(handler).x, 0f)
            assertFalse(frameScheduled(handler))
        } finally {
            handler.cleanup()
        }
    }

    @Test
    fun `motion held back by a pause is dispatched once the game resumes`() {
        val deviceId = 42
        val axisKeyCode = ExternalControllerBinding.getKeyCodeForAxis(MotionEvent.AXIS_X, 1.toByte())
        val controller = motionController(axisKeyCode, Binding.KEY_E)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(deviceId)).thenReturn(controller)
        val xServer = mock<XServer>()
        val handler = PhysicalControllerHandler(profile, xServer)

        try {
            PluviaApp.isOverlayPaused = true
            controller.state.thumbLX = 1f
            assertTrue(handler.onGenericMotionEvent(motionEvent(deviceId)))
            runInputTicks()
            verify(xServer, times(0)).injectKeyPress(XKeycode.KEY_E)

            // e.g. manual resume: no new motion, the stick is simply still held.
            PluviaApp.isOverlayPaused = false
            handler.onOverlayResumed()
            runInputTicks()
            verify(xServer, times(1)).injectKeyPress(XKeycode.KEY_E)
        } finally {
            PluviaApp.isOverlayPaused = false
            handler.cleanup()
        }
    }

    private fun runInputTicks() {
        shadowOf(Looper.getMainLooper()).idleFor(50, MILLISECONDS)
    }

    private fun keyEvent(deviceId: Int, keyCode: Int, action: Int): KeyEvent {
        return mock<KeyEvent>().also { event ->
            whenever(event.repeatCount).thenReturn(0)
            whenever(event.deviceId).thenReturn(deviceId)
            whenever(event.keyCode).thenReturn(keyCode)
            whenever(event.action).thenReturn(action)
        }
    }

    private fun mouseLook(handler: PhysicalControllerHandler): MouseLookStepper =
        privateField(handler, "mouseLook") as MouseLookStepper

    private fun mouseMoveOffset(handler: PhysicalControllerHandler): PointF {
        val mouseLook = mouseLook(handler)
        return PointF(mouseLook.offsetX, mouseLook.offsetY)
    }

    // The handler's own loop (throttled motion) or the mouse-look loop it drives.
    private fun frameScheduled(handler: PhysicalControllerHandler): Boolean =
        (privateField(handler, "frameLoop") as FrameCallbackLoop).isScheduled || mouseLook(handler).isRunning

    private fun motionController(keyCode: Int, binding: Binding): ExternalController =
        motionController(keyCode, BindingCombo.of(binding))

    private fun motionController(keyCode: Int, bindingCombo: BindingCombo): ExternalController {
        return object : ExternalController() {
            override fun updateStateFromMotionEvent(event: MotionEvent): Boolean = true
        }.apply {
            addControllerBinding(
                ExternalControllerBinding().apply {
                    setKeyCode(keyCode)
                    setBindingCombo(bindingCombo)
                },
            )
        }
    }

    private fun motionEvent(deviceId: Int): MotionEvent {
        return mock<MotionEvent>().also { event ->
            whenever(event.deviceId).thenReturn(deviceId)
        }
    }

    private fun privateField(handler: PhysicalControllerHandler, name: String): Any? {
        val field = PhysicalControllerHandler::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(handler)
    }
}
