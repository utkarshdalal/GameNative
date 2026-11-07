package app.gamenative.html5.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.VisibleForTesting
import app.gamenative.ui.screen.xserver.PhysicalControllerHandler
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.math.Mathf
import timber.log.Timber

// html5 counterpart of XServerScreen's PhysicalControllerHandler wiring. main thread.
class Html5InputController private constructor(
    private val handler: PhysicalControllerHandler,
    private val profileRef: () -> ControlsProfile?,
    private val synthesizer: Html5InputSynthesizer? = null,
    private val nowMillis: () -> Long = { SystemClock.uptimeMillis() },
    private var onOpenNavigationMenu: (() -> Unit)? = null,
) {
    val bridge: Html5GamepadBridge = Html5GamepadBridge()

    // xServer = null is safe: the handler only touches it null-safely, from the mouse-move timer.
    constructor(
        profile: ControlsProfile?,
        synthesizer: Html5InputSynthesizer? = null,
    ) : this(
        handler = PhysicalControllerHandler(profile = profile, xServer = null, onOpenNavigationMenu = null),
        profileRef = { profile },
        synthesizer = synthesizer,
    )

    // rebound on recomposition so the callback never holds a stale showQuickMenu setter.
    fun setOnOpenNavigationMenu(callback: (() -> Unit)?) {
        onOpenNavigationMenu = callback
    }

    private var lastButtonBTimestamp: Long = 0L

    // gamepad keys must NOT be consumed: that kills WebView's native key -> DOM KeyboardEvent path,
    // which keyboard-reading games rely on.
    // KEYCODE_BACK is ambiguous: on handhelds (e.g. Odin) controller B emits BUTTON_B + BACK together,
    // while hardware back emits ONLY BACK. consume BACK only right after a BUTTON_B; otherwise it
    // falls through and exits the game.
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            lastButtonBTimestamp = nowMillis()
        }

        val consumed = handler.onKeyEvent(event)
        if (consumed) updateBridgeFromProfile()

        // GAMEPAD_* is skipped: handler.onKeyEvent already wrote that state, and dispatching it
        // again would double it.
        if (synthesizer != null && event.repeatCount == 0) {
            val resolved = profileRef()
                ?.getController(event.deviceId)
                ?.getControllerBinding(event.keyCode)
                ?.binding
            val effective: Binding? = when {
                resolved == null || resolved == Binding.NONE ->
                    Html5RawAndroidKeyMap.bindingFor(event.keyCode)
                else -> resolved
            }
            if (effective != null && !effective.isGamepad) {
                dispatchBinding(effective, event.action == KeyEvent.ACTION_DOWN, 0f)
            }
        }

        return event.keyCode == KeyEvent.KEYCODE_BACK &&
            (nowMillis() - lastButtonBTimestamp) <= CONTROLLER_B_WINDOW_MS
    }

    // WebView ignores gamepad MotionEvents, so consuming them is harmless.
    fun onMotionEvent(event: MotionEvent): Boolean {
        val consumed = handler.onGenericMotionEvent(event)
        if (consumed) updateBridgeFromProfile()
        if (synthesizer != null) {
            // regardless of the handler's verdict: it may decline a device the wildcard "*"
            // controller still covers.
            synthesizeAxisKeysFromMotion(event)
        }
        return consumed
    }

    // axis values come off the MotionEvent, not controller.state: state is only filled when the
    // handler consumed the event, which needs a device-id-matched controller.
    // several axes can map to the SAME key (AXIS_X + AXIS_HAT_X -> KEY_D). take the max per key and
    // call onAxisValue ONCE per key, or an idle axis's 0 immediately keyups what the active one pressed.
    private fun synthesizeAxisKeysFromMotion(event: MotionEvent) {
        val syn = synthesizer ?: return
        val profile = profileRef() ?: return
        val controller = profile.getController(event.deviceId)
        if (controller == null) {
            Timber.tag("Html5Input").d(
                "MotionEvent dropped: no controller (deviceId=%d, no wildcard)", event.deviceId,
            )
            return
        }

        // most pads send the d-pad ONLY as HAT axes, with no KeyEvent.
        val pairs = arrayOf(
            MotionEvent.AXIS_X to event.getAxisValue(MotionEvent.AXIS_X),
            MotionEvent.AXIS_Y to event.getAxisValue(MotionEvent.AXIS_Y),
            MotionEvent.AXIS_Z to event.getAxisValue(MotionEvent.AXIS_Z),
            MotionEvent.AXIS_RZ to event.getAxisValue(MotionEvent.AXIS_RZ),
            MotionEvent.AXIS_HAT_X to event.getAxisValue(MotionEvent.AXIS_HAT_X),
            MotionEvent.AXIS_HAT_Y to event.getAxisValue(MotionEvent.AXIS_HAT_Y),
        )

        val keyMaxAbs = mutableMapOf<Binding, Float>()

        for ((axis, value) in pairs) {
            val posKey = ExternalControllerBinding.getKeyCodeForAxis(axis, 1.toByte())
            val negKey = ExternalControllerBinding.getKeyCodeForAxis(axis, (-1).toByte())
            controller.getControllerBinding(posKey)?.binding?.let { b ->
                accumulate(b, value, positive = true, into = keyMaxAbs)
            }
            controller.getControllerBinding(negKey)?.binding?.let { b ->
                accumulate(b, value, positive = false, into = keyMaxAbs)
            }
        }

        for ((keyB, maxAbs) in keyMaxAbs) {
            syn.onAxisValue(keyB, maxAbs)
        }
    }

    private fun contribution(value: Float, positive: Boolean): Float {
        val sign = Mathf.sign(value)
        val pushing = if (positive) sign > 0 else sign < 0
        return if (pushing) Math.abs(value) else 0f
    }

    // records the key even at 0 so a released key still gets its keyup.
    private fun accumulate(
        binding: Binding,
        value: Float,
        positive: Boolean,
        into: MutableMap<Binding, Float>,
    ) {
        val keyB = mapToKeyBinding(binding) ?: return
        val contrib = contribution(value, positive)
        val prev = into[keyB] ?: 0f
        if (contrib > prev) {
            into[keyB] = contrib
        } else {
            into.putIfAbsent(keyB, 0f)
        }
    }

    private fun mapToKeyBinding(binding: Binding): Binding? =
        if (Html5KeyMapping.specFor(binding) != null) binding else null

    fun setProfile(profile: ControlsProfile?) {
        handler.setProfile(profile)
    }

    // shared by physical keys and overlay presses; callers pass the already-remapped binding.
    fun dispatchBinding(binding: Binding, isDown: Boolean, offset: Float = 0f) {
        // before the synthesizer check: the overlay MENU button must work without one.
        if (binding == Binding.OPEN_NAVIGATION_MENU) {
            if (isDown) {
                Timber.tag("Html5Input").d("dispatchBinding OPEN_NAVIGATION_MENU → invoke callback")
                onOpenNavigationMenu?.invoke()
            }
            return
        }
        when {
            binding == Binding.NONE -> {
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
                // same state path as physical input, so overlay and pad never diverge.
                val effectiveOffset = if (offset != 0f) offset else digitalOffsetFor(binding, isDown)
                handler.applyBinding(binding, isDown, effectiveOffset)
                updateBridgeFromProfile()
            }
            else -> {
                val syn = synthesizer ?: return
                syn.onBindingPress(binding, isDown)
            }
        }
    }

    // overlay taps carry no magnitude: full press / full deflection.
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

    private fun updateBridgeFromProfile() {
        val state = profileRef()?.gamepadState ?: return
        bridge.updateState(state)
    }

    companion object {
        // observed co-emission gap is ~10ms; 50ms leaves headroom without catching hardware back.
        private const val CONTROLLER_B_WINDOW_MS = 50L

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
