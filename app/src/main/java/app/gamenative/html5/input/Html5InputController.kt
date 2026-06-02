package app.gamenative.html5.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.math.Mathf
import timber.log.Timber

// extracted from WebViewScreen so html5 input lives outside the Compose tree.
// owns the PhysicalControllerHandler instance + Html5GamepadBridge for html5 containers.
// parallel to XServerScreen's PhysicalControllerHandler usage (reuses the class, not the scaffolding).
// layer: kotlin-side input bus subscriber + bridge updater, runs on main thread.
class Html5InputController private constructor(
    private val handler: PhysicalControllerHandler,
    private val profileRef: () -> ControlsProfile?,
    // optional synthesizer pumps KEY_*/MOUSE_* bindings into DOM-event synthesis path.
    private val synthesizer: Html5InputSynthesizer? = null,
    // injectable clock so pure-JVM tests can drive the BUTTON_B co-occurrence window
    // without calling SystemClock.uptimeMillis (not mocked in Robolectric-less unit tests).
    private val nowMillis: () -> Long = { SystemClock.uptimeMillis() },
    // fix: overlay MENU button + Binding.OPEN_NAVIGATION_MENU intercept point.
    // R3 was unreserved (back-button is THE menu hotkey); MENU button must invoke this
    // callback to open QuickMenu instead of being dropped in dispatchBinding's no-op branch.
    private var onOpenNavigationMenu: (() -> Unit)? = null,
) {
    val bridge: Html5GamepadBridge = Html5GamepadBridge()

    // production constructor -- profile lookup at call time via the lambda so cleanup() can
    // run even after the profile is released. `xServer = null` is safe
    // PhysicalControllerHandler.createMouseMoveTimer only touches xServer inside `xServer?.`
    // and the timer only starts when MOUSE_MOVE_* bindings produce offsets -- HTML5 profiles
    // use GAMEPAD_* bindings, so the mouse-move path is never reached.
    constructor(
        profile: ControlsProfile?,
        synthesizer: Html5InputSynthesizer? = null,
    ) : this(
        handler = PhysicalControllerHandler(profile = profile, xServer = null, onOpenNavigationMenu = null),
        profileRef = { profile },
        synthesizer = synthesizer,
    )

    // setter so WebViewScreen can rebind the callback across recomposition without rebuilding
    // the controller (callback captures showQuickMenu setter -- recompose would otherwise leak
    // a stale ref). called from a LaunchedEffect that targets the latest setShowQuickMenu.
    fun setOnOpenNavigationMenu(callback: (() -> Unit)?) {
        onOpenNavigationMenu = callback
    }

    // timestamp of the most recent KEYCODE_BUTTON_B event. used to disambiguate a later
    // KEYCODE_BACK: if BACK fires within CONTROLLER_B_WINDOW_MS of a BUTTON_B, they came from
    // the same physical controller-B press (Odin handhelds emit both for one button press).
    // outside the window, BACK is treated as the dedicated hardware-back button → exit.
    private var lastButtonBTimestamp: Long = 0L

    // - default gamepad keys (A/X/Y/L1/R1/dpad/etc) must NOT be consumed -- consumption
    // kills WebView's native onKeyDown → DOM KeyboardEvent path. RMMV + keyboard-fallback
    // games depend on native passthrough until the gamepad-kbd-suppress shim ships.
    // - KEYCODE_BACK is ambiguous: on Android handhelds the controller's B/Circle emits
    // BUTTON_B + KEYCODE_BACK together (within ~15ms), while the dedicated hardware-back
    // button emits ONLY KEYCODE_BACK. co-occurrence disambiguation: consume BACK only if
    // BUTTON_B fired within CONTROLLER_B_WINDOW_MS. outside that window, let BACK fall
    // through to MainActivity's AndroidEvent.BackPressed → NavHost pops → game exits.
    // bridge refreshes on any handler-recognized event regardless of consume decision.
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            lastButtonBTimestamp = nowMillis()
        }

        val consumed = handler.onKeyEvent(event)
        if (consumed) updateBridgeFromProfile()

        // synthesizer fires only when the resolved binding is KEY_*/MOUSE_*/MOUSE_MOVE_*.
        // GAMEPAD_* bindings are already handled by handler.onKeyEvent above (writes
        // profile.gamepadState → updateBridgeFromProfile → __gnGamepadBridge); routing them
        // through dispatchBinding's gamepad branch would write the same state twice. one
        // binding → one event. chromium's native KeyEvent→DOM auto-dispatch is suppressed
        // by the gamepad-kbd-suppress shim (default-on; pack opt-out via EngineProfile flag).
        if (synthesizer != null && event.repeatCount == 0) {
            val resolved = profileRef()
                ?.getController(event.deviceId)
                ?.getControllerBinding(event.keyCode)
                ?.binding
            // null / NONE → raw android keycode fallback (fresh controller, no bindings yet)
            val effective: Binding? = when {
                resolved == null || resolved == Binding.NONE ->
                    Html5RawAndroidKeyMap.bindingFor(event.keyCode)
                else -> resolved
            }
            if (effective != null && !effective.isGamepad) {
                Timber.tag("Html5Input").d(
                    "physical KeyEvent → keyCode=%d resolved=%s → dispatch=%s isDown=%b",
                    event.keyCode, resolved?.name ?: "null", effective.name,
                    event.action == KeyEvent.ACTION_DOWN,
                )
                dispatchBinding(effective, event.action == KeyEvent.ACTION_DOWN, 0f)
            }
        }

        // narrow consumption: eat KEYCODE_BACK only when it's the controller-B-as-BACK case.
        return event.keyCode == KeyEvent.KEYCODE_BACK &&
            (nowMillis() - lastButtonBTimestamp) <= CONTROLLER_B_WINDOW_MS
    }

    // MotionEvent (analog sticks, triggers as axes): WebView ignores gamepad MotionEvents, so
    // consumption is harmless. return handler's signal to match onKeyEvent's return convention.
    // always attempt synth even when handler returns false. handler may decline
    // (no registered controller for this deviceId, no wildcard either), but synth path can still
    // resolve via the wildcard "*" controller in profile (Html5DefaultControlsProfileFactory
    // populates one). without this, fresh-controller users get zero analog response on overlay.
    fun onMotionEvent(event: MotionEvent): Boolean {
        val consumed = handler.onGenericMotionEvent(event)
        if (consumed) updateBridgeFromProfile()
        if (synthesizer != null) {
            // run synth regardless of handler's consume verdict -- the profile lookup inside
            // synthesizeAxisKeysFromMotion has its own bail-out when no controller is found.
            synthesizeAxisKeysFromMotion(event)
        }
        return consumed
    }

    // walk the standard joystick axes (LX/LY/RX/RY + dpad-as-hat),
    // resolve each direction's controller binding via the profile's wildcard "*" controller,
    // translate to a keyboard binding (honoring user remap), pump through synthesizer.onAxisValue.
    // axis values read directly from the MotionEvent rather than from controller.state -- state
    // is only populated when handler.onGenericMotionEvent consumes the event, which requires a
    // device-id-matched controller registration. reading from MotionEvent makes synth resilient
    // when no device-id-matched controller is registered.
    //
    // AGGREGATE per-key max-abs value across ALL axes BEFORE pumping to synth.
    // multiple physical axes can resolve to the SAME logical key (e.g. AXIS_X + AXIS_HAT_X both
    // bound to KEY_D for "right"). prior impl iterated axes serially and called onAxisValue
    // for each -- an inactive axis would report value=0 to the key, racing the active axis's
    // value=1 and triggering an immediate keyup transition (keydown→keyup same tick). union
    // the votes here so a key stays "down" while ANY axis pushes it past threshold.
    private fun synthesizeAxisKeysFromMotion(event: MotionEvent) {
        val syn = synthesizer ?: return
        val profile = profileRef() ?: return
        // getController(deviceId) falls back to wildcard "*" -- Html5DefaultControlsProfileFactory
        // populates one -- so this works even when the device hasn't been touched in
        // Edit Physical Controller.
        val controller = profile.getController(event.deviceId)
        if (controller == null) {
            Timber.tag("Html5Input").d(
                "MotionEvent dropped: no controller (deviceId=%d, no wildcard)", event.deviceId,
            )
            return
        }

        // (axisCode, motionEvent-axis-value) pairs -- read straight off the event.
        // HAT_X/HAT_Y INCLUDED. d-pad on most pads emits via HAT axes
        // (AXIS_HAT_X / AXIS_HAT_Y, codes 15 and 16) without a corresponding KeyEvent. omitting them = no dispatch.
        val pairs = arrayOf(
            MotionEvent.AXIS_X to event.getAxisValue(MotionEvent.AXIS_X),
            MotionEvent.AXIS_Y to event.getAxisValue(MotionEvent.AXIS_Y),
            MotionEvent.AXIS_Z to event.getAxisValue(MotionEvent.AXIS_Z),
            MotionEvent.AXIS_RZ to event.getAxisValue(MotionEvent.AXIS_RZ),
            MotionEvent.AXIS_HAT_X to event.getAxisValue(MotionEvent.AXIS_HAT_X),
            MotionEvent.AXIS_HAT_Y to event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )

        // pass 1: discover every key bound to ANY direction of ANY axis on this controller.
        // we need to call onAxisValue exactly ONCE per key per MotionEvent so deadband/threshold
        // hysteresis sees a coherent "is any axis pushing this key" signal, not a serialized
        // race of zeros and ones.
        val keyMaxAbs = mutableMapOf<Binding, Float>()

        for ((axis, value) in pairs) {
            val posKey = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
            val negKey = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())
            controller.getControllerBinding(posKey)?.binding?.let { b ->
                accumulate(b, axis, value, positive = true, into = keyMaxAbs)
            }
            controller.getControllerBinding(negKey)?.binding?.let { b ->
                accumulate(b, axis, value, positive = false, into = keyMaxAbs)
            }
        }

        // pass 2: pump aggregated values into synth -- exactly once per key per event.
        for ((keyB, maxAbs) in keyMaxAbs) {
            syn.onAxisValue(keyB, maxAbs)
        }
    }

    // contribution: the magnitude of THIS axis ONLY when it's pushing the requested direction.
    private fun contribution(value: Float, positive: Boolean): Float {
        val sign = Mathf.sign(value)
        val pushing = if (positive) sign > 0 else sign < 0
        return if (pushing) Math.abs(value) else 0f
    }

    // resolve binding → key, compute its contribution for this axis/direction, and union it into
    // the per-key max-abs map. records key even with contrib=0 so a bound key is checked at least
    // once and gets a keyup if no axis is currently pushing it.
    private fun accumulate(
        binding: Binding,
        axis: Int,
        value: Float,
        positive: Boolean,
        into: MutableMap<Binding, Float>,
    ) {
        val keyB = mapToKeyBinding(binding) ?: return
        val contrib = contribution(value, positive)
        if (contrib > 0f) {
            Timber.tag("Html5Input").d(
                "axis %d=%.2f → %sBinding=%s → key=%s contrib=%.2f",
                axis, value, if (positive) "pos" else "neg", binding.name, keyB.name, contrib,
            )
        }
        val prev = into[keyB] ?: 0f
        if (contrib > prev) {
            into[keyB] = contrib
        } else {
            into.putIfAbsent(keyB, 0f)
        }
    }

    // bindings are the source of truth. Profile bindings that resolve to KEY_*/MOUSE_* go
    // through the synthesizer; GAMEPAD_* bindings update profile.gamepadState via
    // handler.applyBinding and surface to JS via the single Html5GamepadBridge. No second
    // virtual-gamepad bridge -- overlay taps + physical events both write the same state.
    private fun mapToKeyBinding(binding: Binding): Binding? =
        if (Html5KeyMapping.specFor(binding) != null) binding else null

    fun setProfile(profile: ControlsProfile?) {
        handler.setProfile(profile)
    }

    // shared dispatch path for both physical KeyEvents (onKeyEvent) and overlay element
    // presses (Html5BindingSink in WebViewScreen). honors the user's controller-profile
    // remap because callers resolve the binding via profile.getController(...).getControllerBinding
    // before calling here.
    //
    // dispatch table:
    // KEY_* / MOUSE_* → synthesizer.onBindingPress (DOM key/mouse event)
    // MOUSE_MOVE_* → synthesizer.onCursorMove with offset (cursor delta)
    // GAMEPAD_* → handler.applyBinding (writes profile.gamepadState) → updateBridgeFromProfile
    // OPEN_NAVIGATION_MENU → invoke onOpenNavigationMenu callback
    // anything else / NONE → drop
    fun dispatchBinding(binding: Binding, isDown: Boolean, offset: Float = 0f) {
        // fix: OPEN_NAVIGATION_MENU runs WITHOUT requiring a synthesizer -- overlay
        // MENU button must work on the touch-only path even if KEY_* synth isn't configured.
        if (binding == Binding.OPEN_NAVIGATION_MENU) {
            // fire on key-down only so a press+release doesn't toggle twice.
            if (isDown) {
                Timber.tag("Html5Input").d("dispatchBinding OPEN_NAVIGATION_MENU → invoke callback")
                onOpenNavigationMenu?.invoke()
            }
            return
        }
        when {
            binding == Binding.NONE -> {
                // explicit no-op
            }
            binding.name.startsWith("MOUSE_MOVE_") -> {
                val syn = synthesizer ?: return
                if (isDown) {
                    val (dx, dy) = when (binding) {
                        Binding.MOUSE_MOVE_LEFT -> -offset to 0f
                        Binding.MOUSE_MOVE_RIGHT -> offset to 0f
                        Binding.MOUSE_MOVE_UP -> 0f to -offset
                        Binding.MOUSE_MOVE_DOWN -> 0f to offset
                        else -> 0f to 0f
                    }
                    if (dx != 0f || dy != 0f) syn.onCursorMove(dx, dy)
                }
            }
            binding.isGamepad -> {
                // route through the SAME state-update path physical events use
                // (handler.applyBinding → profile.gamepadState → Html5GamepadBridge). overlay
                // taps with no analog magnitude get sane digital defaults: full deflection for
                // thumb directions, full press for L2/R2. analog overlays passing offset != 0
                // pass through unchanged.
                val effectiveOffset = if (offset != 0f) offset else digitalOffsetFor(binding, isDown)
                handler.applyBinding(binding, isDown, effectiveOffset)
                updateBridgeFromProfile()
                Timber.tag("Html5Input").d(
                    "dispatchBinding GAMEPAD %s isDown=%b offset=%.2f → handler.applyBinding",
                    binding.name, isDown, effectiveOffset,
                )
            }
            else -> {
                // KEY_* / MOUSE_* -- direct synthesizer dispatch
                val syn = synthesizer ?: return
                syn.onBindingPress(binding, isDown)
            }
        }
    }

    // digital defaults for gamepad bindings tapped with no analog magnitude (offset == 0):
    // full press for L2/R2, full deflection for thumb directions. negative for left/up.
    private fun digitalOffsetFor(binding: Binding, isDown: Boolean): Float = when (binding) {
        Binding.GAMEPAD_BUTTON_L2,
        Binding.GAMEPAD_BUTTON_R2,
        -> if (isDown) 1f else 0f
        Binding.GAMEPAD_LEFT_THUMB_LEFT,
        Binding.GAMEPAD_RIGHT_THUMB_LEFT,
        Binding.GAMEPAD_LEFT_THUMB_UP,
        Binding.GAMEPAD_RIGHT_THUMB_UP,
        -> if (isDown) -1f else 0f
        Binding.GAMEPAD_LEFT_THUMB_RIGHT,
        Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
        Binding.GAMEPAD_LEFT_THUMB_DOWN,
        Binding.GAMEPAD_RIGHT_THUMB_DOWN,
        -> if (isDown) 1f else 0f
        else -> 0f
    }

    fun cleanup() {
        handler.cleanup()
    }

    // the GamepadState lives on ControlsProfile -- PhysicalControllerHandler writes into
    // `profile.gamepadState` (see PhysicalControllerHandler.kt handleInputEvent). we re-serialize
    // on each consumed event. allocation is one StringBuilder per input transition, not per rAF tick.
    private fun updateBridgeFromProfile() {
        val state = profileRef()?.gamepadState ?: return
        bridge.updateState(state)
    }

    companion object {
        // max gap between a controller's KEYCODE_BUTTON_B and its co-emitted KEYCODE_BACK.
        // Odin handhelds emit both within ~10ms; 50ms gives headroom without risking hardware
        // back being misclassified as controller-B.
        private const val CONTROLLER_B_WINDOW_MS = 50L

        // test seam -- lets Html5InputControllerTest inject a MockK<PhysicalControllerHandler>
        // and an injectable clock for BUTTON_B co-occurrence window assertions.
        @VisibleForTesting
        internal fun forTest(
            handler: PhysicalControllerHandler,
            profile: ControlsProfile?,
            synthesizer: Html5InputSynthesizer? = null,
            nowMillis: () -> Long = { SystemClock.uptimeMillis() },
        ): Html5InputController = Html5InputController(
            handler = handler,
            profileRef = { profile },
            synthesizer = synthesizer,
            nowMillis = nowMillis,
        )
    }
}
