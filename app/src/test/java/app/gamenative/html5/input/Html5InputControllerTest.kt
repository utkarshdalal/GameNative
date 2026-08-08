package app.gamenative.html5.input

import android.view.KeyEvent
import android.view.MotionEvent
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.GamepadState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

// pure-jvm via MockK — android.view.KeyEvent/MotionEvent are mocked, ControlsProfile is a
// plain Java class (no android <clinit>), PhysicalControllerHandler is a plain kotlin class.
// Html5InputController uses an internal constructor that accepts a pre-built handler for mocking.
class Html5InputControllerTest {

    @Test
    fun bridge_is_exposed_for_addJavascriptInterface_wiring() {
        val handler = mockk<PhysicalControllerHandler>()
        val profile = mockk<ControlsProfile>(relaxed = true)
        val controller = Html5InputController.forTest(handler, profile)
        assertNotNull("bridge must be accessible from WebViewScreen", controller.bridge)
    }

    @Test
    fun onKeyEvent_non_back_key_returns_false_so_WebView_sees_native_KeyEvent() {
        // RMMV (and other keyboard-fallback games) need WebView's native onKeyDown path
        // to dispatch DOM KeyboardEvent. consuming non-BACK keys here would kill gameplay
        // input. bridge still updates so C3 / Gamepad-API games see live state.
        // relaxed=true so onKeyEvent's source/repeatCount reads (added with the kbd-swallow
        // path) get default 0 — the keyCode stub still wins per MockK explicit-over-relaxed.
        val ev = mockk<KeyEvent>(relaxed = true)
        every { ev.keyCode } returns KeyEvent.KEYCODE_BUTTON_A
        val state = GamepadState().apply { setPressed(0, true) } // A button
        val profile = mockk<ControlsProfile>()
        every { profile.gamepadState } returns state

        val handler = mockk<PhysicalControllerHandler>()
        every { handler.onKeyEvent(ev) } returns true

        val controller = Html5InputController.forTest(handler, profile)
        val result = controller.onKeyEvent(ev)

        assertEquals("non-BACK gamepad key must NOT consume — WebView pass-through required", false, result)
        // bridge still reflects handler update so Gamepad API polling sees the press
        val json = controller.bridge.readState()
        val firstButton = json.substringAfter("\"buttons\":[").substringBefore(",{")
        assertEquals(true, firstButton.contains("\"pressed\":true"))
    }

    @Test
    fun onKeyEvent_back_key_after_ButtonB_is_consumed_as_controller_B() {
        // Android handhelds (Odin etc.) emit BUTTON_B + KEYCODE_BACK together (~10ms apart)
        // for one physical controller-B press. co-occurrence heuristic: BACK within
        // CONTROLLER_B_WINDOW_MS of a BUTTON_B is the controller, not hardware-back → consume.
        val bEv = mockk<KeyEvent>(relaxed = true)
        every { bEv.keyCode } returns KeyEvent.KEYCODE_BUTTON_B
        val backEv = mockk<KeyEvent>(relaxed = true)
        every { backEv.keyCode } returns KeyEvent.KEYCODE_BACK

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handler.onKeyEvent(any()) } returns false

        // injected clock: first call returns t=100 (when BUTTON_B fires),
        // subsequent calls return t=110 (10ms later — same physical press)
        val times = listOf(100L, 110L, 110L).iterator()
        val controller = Html5InputController.forTest(handler, mockk<ControlsProfile>(relaxed = true), nowMillis = { times.next() })
        controller.onKeyEvent(bEv)
        val result = controller.onKeyEvent(backEv)

        assertEquals("BACK co-occurring with BUTTON_B must be consumed as controller-B", true, result)
    }

    @Test
    fun onKeyEvent_back_key_standalone_is_NOT_consumed_so_hardware_back_exits() {
        // standalone KEYCODE_BACK (no preceding BUTTON_B within the window) is the dedicated
        // hardware-back button. fall through to AndroidEvent.BackPressed → WebView exits.
        val ev = mockk<KeyEvent>(relaxed = true)
        every { ev.keyCode } returns KeyEvent.KEYCODE_BACK

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handler.onKeyEvent(ev) } returns false

        // clock returns t=5000 — no prior BUTTON_B (lastButtonBTimestamp = 0),
        // so gap = 5000 > CONTROLLER_B_WINDOW_MS → fall through.
        val controller = Html5InputController.forTest(handler, mockk<ControlsProfile>(relaxed = true), nowMillis = { 5000L })
        val result = controller.onKeyEvent(ev)

        assertEquals("standalone KEYCODE_BACK must NOT consume — hardware Back must exit", false, result)
    }

    @Test
    fun onKeyEvent_returns_false_when_handler_rejects_and_does_NOT_update_bridge() {
        val ev = mockk<KeyEvent>(relaxed = true)
        every { ev.keyCode } returns KeyEvent.KEYCODE_BUTTON_A
        val profile = mockk<ControlsProfile>(relaxed = true)
        val handler = mockk<PhysicalControllerHandler>()
        every { handler.onKeyEvent(ev) } returns false

        val controller = Html5InputController.forTest(handler, profile)
        val initialSnapshot = controller.bridge.readState()

        val result = controller.onKeyEvent(ev)

        assertEquals(false, result)
        // bridge stays untouched when handler rejects — avoids allocating on every non-gamepad key
        assertEquals(
            "bridge snapshot should not change on non-consumed event",
            initialSnapshot,
            controller.bridge.readState(),
        )
        verify(exactly = 0) { profile.gamepadState }
    }

    @Test
    fun onMotionEvent_returns_true_when_handler_consumes_and_updates_bridge() {
        val ev = mockk<MotionEvent>()
        val state = GamepadState().apply { thumbLX = 0.7f }
        val profile = mockk<ControlsProfile>()
        every { profile.gamepadState } returns state

        val handler = mockk<PhysicalControllerHandler>()
        every { handler.onGenericMotionEvent(ev) } returns true

        val controller = Html5InputController.forTest(handler, profile)
        val result = controller.onMotionEvent(ev)

        assertEquals(true, result)
        val json = controller.bridge.readState()
        val axes = json.substringAfter("\"axes\":[").substringBefore("]").split(",")
        assertEquals(0.7f.toString(), axes[0])
    }

    @Test
    fun onMotionEvent_returns_false_when_handler_rejects() {
        val ev = mockk<MotionEvent>()
        val profile = mockk<ControlsProfile>(relaxed = true)
        val handler = mockk<PhysicalControllerHandler>()
        every { handler.onGenericMotionEvent(ev) } returns false

        val controller = Html5InputController.forTest(handler, profile)
        val result = controller.onMotionEvent(ev)
        assertEquals(false, result)
        verify(exactly = 0) { profile.gamepadState }
    }

    @Test
    fun null_profile_during_consume_leaves_bridge_at_default_without_crash() {
        val ev = mockk<KeyEvent>(relaxed = true)
        every { ev.keyCode } returns KeyEvent.KEYCODE_BUTTON_A
        val handler = mockk<PhysicalControllerHandler>()
        every { handler.onKeyEvent(ev) } returns true

        // constructor path with null profile — handler still recognizes the event (mocked)
        // but updateBridgeFromProfile finds profile null → early return, bridge untouched.
        val controller = Html5InputController.forTest(handler, null)
        val before = controller.bridge.readState()
        val result = controller.onKeyEvent(ev)
        val after = controller.bridge.readState()

        // onKeyEvent now always returns false (native WebView pass-through).
        assertEquals(false, result)
        assertEquals("bridge unchanged on null profile", before, after)
    }

    @Test
    fun cleanup_delegates_to_handler() {
        val handler = mockk<PhysicalControllerHandler>()
        every { handler.cleanup() } just Runs
        val controller = Html5InputController.forTest(handler, null)
        controller.cleanup()
        verify(exactly = 1) { handler.cleanup() }
    }

    @Test
    fun setProfile_delegates_to_handler() {
        val handler = mockk<PhysicalControllerHandler>()
        every { handler.setProfile(any()) } just Runs
        val controller = Html5InputController.forTest(handler, null)
        val newProfile = mockk<ControlsProfile>(relaxed = true)
        controller.setProfile(newProfile)
        verify(exactly = 1) { handler.setProfile(newProfile) }
    }

    //: when MULTIPLE axes resolve to the SAME logical key on the
    // active profile (e.g. AXIS_X+ and DPAD_RIGHT both bound to KEY_D), processing one
    // MotionEvent must NOT serialize keydown→keyup on the same tick. Pre-fix, an inactive
    // axis would call onAxisValue(KEY_D, 0f) right after the active axis called
    // onAxisValue(KEY_D, 1f), tripping the synthesizer's keyup transition. Aggregation in
    // synthesizeAxisKeysFromMotion reduces this to a single onAxisValue per key per event.
    @Test
    fun synthesizeAxisKeys_two_axes_same_key_only_active_axis_wins() {
        // build a real ControlsProfile where AXIS_X+ → KEY_D AND DPAD_RIGHT → KEY_D.
        // DPAD_RIGHT is what AXIS_HAT_X+ resolves to via ExternalControllerBinding.getKeyCodeForAxis.
        val profile = mockk<ControlsProfile>(relaxed = true)
        val controller = mockk<com.winlator.inputcontrols.ExternalController>(relaxed = true)
        every { profile.getController(any<Int>()) } returns controller

        val xPosBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_D
        }
        val hatXPosBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_D
        }
        // axis-keycode constants from ExternalControllerBinding (signed bytes widened to int).
        // dispatch by keycode in a single answers block — last-stub-wins semantics in MockK
        // would otherwise let our catch-all overwrite the specific stubs.
        val xPosKey = com.winlator.inputcontrols.ExternalControllerBinding.AXIS_X_POSITIVE.toInt()
        val dpadRight = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        every { controller.getControllerBinding(any<Int>()) } answers {
            when (firstArg<Int>()) {
                xPosKey -> xPosBinding
                dpadRight -> hatXPosBinding
                else -> null
            }
        }

        // capture synth calls — we want EXACTLY one call to onAxisValue(KEY_D, *) with the
        // ACTIVE value, not two competing calls (1.0 then 0.0) on the same event.
        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)

        val handlerMock = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handlerMock.onGenericMotionEvent(any()) } returns false

        // craft MotionEvent: AXIS_X=1.0 (active right), AXIS_HAT_X=0.0 (dpad neutral).
        // all other axes neutral.
        val ev = mockk<MotionEvent>(relaxed = true)
        every { ev.deviceId } returns 99
        every { ev.getAxisValue(MotionEvent.AXIS_X) } returns 1.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Y) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Z) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_RZ) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_X) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_Y) } returns 0.0f

        val ctrl = Html5InputController.forTest(handlerMock, profile, synth)
        ctrl.onMotionEvent(ev)

        // synthesizer should have ONE keydown for KEY_D in queue, ZERO keyups.
        val drained = bridge.drainQueue()
        val keydowns = drained.split("\"type\":\"keydown\"").size - 1
        val keyups = drained.split("\"type\":\"keyup\"").size - 1
        assertEquals("expected one keydown for KEY_D, got drained=$drained", 1, keydowns)
        assertEquals("expected zero keyups (the bug fired keyup same tick), got drained=$drained", 0, keyups)
    }

    //HAT_Y=-1.0 (d-pad UP) must produce one ArrowUp keydown when
    // the profile binds DPAD_UP keycode to KEY_UP directly (post-migration RMMV/Electron
    // shape). pack:c3 profiles keep GAMEPAD_DPAD_UP and would produce zero keydowns here —
    // that's the intended behavior (gamepad goes via virtual gamepad bridge, not synth).
    @Test
    fun synthesizeAxisKeys_hat_y_negative_dispatches_arrow_up() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        val controller = mockk<com.winlator.inputcontrols.ExternalController>(relaxed = true)
        every { profile.getController(any<Int>()) } returns controller

        val keyUpBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_UP
        }
        val keyDownBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_DOWN
        }
        // post-migration RMMV-pack profile: DPAD keycodes bound directly to KEY_UP/DOWN
        // via gamepadKeySynthesisMap applied at default-profile-population time.
        every { controller.getControllerBinding(any<Int>()) } answers {
            when (firstArg<Int>()) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> keyUpBinding
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> keyDownBinding
                else -> null
            }
        }

        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)
        val handlerMock = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handlerMock.onGenericMotionEvent(any()) } returns false

        val ev = mockk<MotionEvent>(relaxed = true)
        every { ev.deviceId } returns 99
        every { ev.getAxisValue(MotionEvent.AXIS_X) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Y) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Z) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_RZ) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_X) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_Y) } returns -1.0f // dpad UP

        val ctrl = Html5InputController.forTest(handlerMock, profile, synth)
        ctrl.onMotionEvent(ev)

        val drained = bridge.drainQueue()
        // expect ArrowUp keydown (KEY_UP → "ArrowUp"), no ArrowDown
        assertEquals(
            "expected ArrowUp keydown for HAT_Y=-1.0, got drained=$drained",
            1, drained.split("\"key\":\"ArrowUp\"").size - 1,
        )
        assertEquals(
            "no ArrowDown should fire when HAT_Y=-1.0, got drained=$drained",
            0, drained.split("\"key\":\"ArrowDown\"").size - 1,
        )
    }

    // GAMEPAD_* dispatches NEVER synthesize DOM keyboard events — they update
    // profile.gamepadState (via handler.applyBinding) which surfaces to JS via
    // Html5GamepadBridge / navigator.getGamepads(). Bindings are the source of truth — packs
    // that need keyboard equivalents populate KEY_* directly via Html5DefaultControlsProfileFactory
    // at profile-creation time using `gamepadKeySynthesisMap`. Pin negative behavior here.
    @Test
    fun dispatchBinding_gamepad_does_not_synthesize_keyboard_events() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.getController(any<Int>()) } returns null
        every { profile.getController(any<String>()) } returns null
        // dispatchBinding's GAMEPAD_* branch calls updateBridgeFromProfile which reads
        // profile.gamepadState (Java field, NOT mockk-intercepted). use a real instance so
        // Html5GamepadBridge.buildGamepadJson can read its dpad/thumbX/etc. fields.
        every { profile.gamepadState } returns com.winlator.inputcontrols.GamepadState()

        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)
        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile, synth)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_A, isDown = true)
        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_A, isDown = false)
        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_START, isDown = true)
        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_START, isDown = false)

        val drained = bridge.drainQueue()
        // No keydown/keyup events should appear — GAMEPAD_* dispatches go to handler.applyBinding,
        // not the synthesizer's __gnInputBridge queue.
        assertEquals(
            "GAMEPAD dispatches must not produce keyboard JS events, got drained=$drained",
            0, drained.split("\"type\":\"keydown\"").size - 1,
        )
        assertEquals(
            "no keyup either, got drained=$drained",
            0, drained.split("\"type\":\"keyup\"").size - 1,
        )
    }

    // Refactor pin: dispatchBinding for GAMEPAD_BUTTON_A routes through handler.applyBinding
    // (the SAME path physical KeyEvents use), not a separate virtual-gamepad bridge.
    @Test
    fun dispatchBinding_gamepadButton_routes_to_handler_applyBinding() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_A, isDown = true)

        verify(exactly = 1) {
            handler.applyBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_A, true, 0f)
        }
    }

    // Refactor pin: thumb-direction binding with offset=0 defaults to full deflection sign.
    // overlay digital-tap of "left-stick LEFT" must produce thumbLX = -1, not 0.
    @Test
    fun dispatchBinding_thumbLeft_zeroOffset_defaultsToFullDeflectionNegative() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT, isDown = true, offset = 0f)

        verify(exactly = 1) {
            handler.applyBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT, true, -1f)
        }
    }

    // Refactor pin: GAMEPAD_LEFT_THUMB_RIGHT digital tap → +1f deflection (matches +ve sign).
    @Test
    fun dispatchBinding_thumbRight_zeroOffset_defaultsToFullDeflectionPositive() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT, isDown = true, offset = 0f)

        verify(exactly = 1) {
            handler.applyBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT, true, 1f)
        }
    }

    // Refactor pin: L2/R2 with offset=0 defaults to full press (1f) when isDown=true.
    // matches the pre-existing physical KeyEvent path's default for digital trigger presses.
    @Test
    fun dispatchBinding_l2Trigger_zeroOffset_isDown_defaultsToFullPress() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_L2, isDown = true, offset = 0f)

        verify(exactly = 1) {
            handler.applyBinding(com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_L2, true, 1f)
        }
    }

    // Refactor pin: analog overlay (joystick) passes a real magnitude → preserves it instead
    // of overriding with the digital default.
    @Test
    fun dispatchBinding_thumbDirection_analogOffset_passesThrough() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile)

        // overlay analog-joystick at 50% deflection — non-zero offset, must NOT be defaulted.
        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT, isDown = true, offset = 0.5f)

        verify(exactly = 1) {
            handler.applyBinding(com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT, true, 0.5f)
        }
    }

    // Refactor pin: onKeyEvent for a GAMEPAD_*-resolved binding must NOT also route through
    // dispatchBinding (handler.onKeyEvent already wrote profile.gamepadState — second routing
    // would duplicate the write). guards against the pre-Commit-C double-dispatch path.
    @Test
    fun onKeyEvent_gamepad_resolvedBinding_does_not_route_through_dispatchBinding() {
        val ev = mockk<KeyEvent>(relaxed = true)
        every { ev.keyCode } returns KeyEvent.KEYCODE_BUTTON_A
        every { ev.action } returns KeyEvent.ACTION_DOWN
        every { ev.repeatCount } returns 0
        every { ev.deviceId } returns 99

        // profile resolves keyCode → GAMEPAD_BUTTON_A binding
        val gamepadBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.GAMEPAD_BUTTON_A
        }
        val externalCtrl = mockk<com.winlator.inputcontrols.ExternalController>(relaxed = true)
        every { externalCtrl.getControllerBinding(KeyEvent.KEYCODE_BUTTON_A) } returns gamepadBinding

        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.getController(99) } returns externalCtrl
        every { profile.gamepadState } returns GamepadState()

        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handler.onKeyEvent(ev) } returns true

        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)
        val ctrl = Html5InputController.forTest(handler, profile, synth)

        ctrl.onKeyEvent(ev)

        // handler.onKeyEvent ALWAYS runs (the original consume-path). handler.applyBinding
        // must NOT be called (would be the duplicate write from dispatchBinding's gamepad
        // branch). gate is the !effective.isGamepad check in onKeyEvent.
        verify(exactly = 1) { handler.onKeyEvent(ev) }
        verify(exactly = 0) { handler.applyBinding(any(), any(), any()) }
        // synthesizer queue must also be empty — no DOM kbd events for gamepad bindings.
        assertEquals(
            "no DOM events for GAMEPAD_*-resolved physical KeyEvent",
            "[]", bridge.drainQueue(),
        )
    }

    // KEY_* bindings still synthesize keyboard events directly — that's the path packs use
    // when they want keyboard fallback (RMMV/Electron default profiles populate KEY_* via
    // pack synth map at population time).
    @Test
    fun dispatchBinding_key_synthesizes_keyboard_event() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        every { profile.getController(any<Int>()) } returns null
        every { profile.getController(any<String>()) } returns null

        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)
        val handler = mockk<PhysicalControllerHandler>(relaxed = true)
        val ctrl = Html5InputController.forTest(handler, profile, synth)

        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.KEY_Z, isDown = true)
        ctrl.dispatchBinding(com.winlator.inputcontrols.Binding.KEY_Z, isDown = false)

        val drained = bridge.drainQueue()
        assertEquals(
            "expected one KeyZ keydown+keyup pair, got drained=$drained",
            2, drained.split("\"code\":\"KeyZ\"").size - 1,
        )
    }

    //post-migration profile binds DPAD keycodes to KEY_LEFT/RIGHT
    // directly. HAT_X=1.0 → KEYCODE_DPAD_RIGHT → KEY_RIGHT → ArrowRight keydown.
    @Test
    fun synthesizeAxisKeys_hat_x_positive_dispatches_arrow_right() {
        val profile = mockk<ControlsProfile>(relaxed = true)
        val controller = mockk<com.winlator.inputcontrols.ExternalController>(relaxed = true)
        every { profile.getController(any<Int>()) } returns controller

        val keyLeftBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_LEFT
        }
        val keyRightBinding = mockk<com.winlator.inputcontrols.ExternalControllerBinding>(relaxed = true).also {
            every { it.binding } returns com.winlator.inputcontrols.Binding.KEY_RIGHT
        }
        every { controller.getControllerBinding(any<Int>()) } answers {
            when (firstArg<Int>()) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> keyLeftBinding
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> keyRightBinding
                else -> null
            }
        }

        val bridge = Html5InputBridge()
        val synth = Html5InputSynthesizer(bridge, 1280, 720)
        val handlerMock = mockk<PhysicalControllerHandler>(relaxed = true)
        every { handlerMock.onGenericMotionEvent(any()) } returns false

        val ev = mockk<MotionEvent>(relaxed = true)
        every { ev.deviceId } returns 99
        every { ev.getAxisValue(MotionEvent.AXIS_X) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Y) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_Z) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_RZ) } returns 0.0f
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_X) } returns 1.0f // dpad RIGHT
        every { ev.getAxisValue(MotionEvent.AXIS_HAT_Y) } returns 0.0f

        val ctrl = Html5InputController.forTest(handlerMock, profile, synth)
        ctrl.onMotionEvent(ev)

        val drained = bridge.drainQueue()
        assertEquals(
            "expected ArrowRight keydown for HAT_X=1.0, got drained=$drained",
            1, drained.split("\"key\":\"ArrowRight\"").size - 1,
        )
        assertEquals(
            "no ArrowLeft should fire when HAT_X=1.0, got drained=$drained",
            0, drained.split("\"key\":\"ArrowLeft\"").size - 1,
        )
    }
}
