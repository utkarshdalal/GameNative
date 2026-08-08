package app.gamenative.html5.input

import androidx.annotation.VisibleForTesting
import com.winlator.inputcontrols.Binding
import org.json.JSONObject
import timber.log.Timber

// translates Binding press/release events into JSON event specs pushed to Html5InputBridge.
// Kotlin owns state + binding semantics; JS owns DOM dispatch.
// press/release only -- no synthetic key repeat. Analog hysteresis: keydown >0.5,
// keyup <0.45 (deadband 0.45..0.5 holds current state).
// synthetic cursor maintained at viewport-center default; updated by stick/dpad/touch.
class Html5InputSynthesizer(
    private val bridge: Html5InputBridge,
    viewportWidth: Int = 1,
    viewportHeight: Int = 1,
) {
    @Volatile private var viewportW: Int = viewportWidth.coerceAtLeast(1)

    @Volatile private var viewportH: Int = viewportHeight.coerceAtLeast(1)

    @Volatile var cursorX: Float = viewportW / 2f
        private set

    @Volatile var cursorY: Float = viewportH / 2f
        private set

    // per-axis-binding "is currently down" tracking for hysteresis.
    // ConcurrentHashMap not needed -- only main thread mutates per binding event sequence.
    private val axisKeyState = mutableMapOf<Binding, Boolean>()

    // KEY_*/MOUSE_* binding from PhysicalControllerHandler. GAMEPAD_* / MOUSE_MOVE_* never
    // reach here -- dispatchBinding routes MOUSE_MOVE_* to onCursorMove instead.
    fun onBindingPress(binding: Binding, isDown: Boolean) {
        when {
            Html5KeyMapping.specFor(binding) != null -> emitKeyEvent(binding, isDown)
            isMouseButton(binding) -> emitMouseButton(binding, isDown)
        }
    }

    // analog stick or trigger value with hysteresis. binding represents the resolved
    // direction key (e.g. KEY_LEFT for left-stick negative-X axis at >0.5).
    // log every state TRANSITION (not deadband no-ops) so the next debug round shows
    // whether dispatch actually reaches bridge.enqueue. parity with onKeyEvent's isDown logging.
    fun onAxisValue(binding: Binding, value: Float) {
        if (Html5KeyMapping.specFor(binding) == null) {
            // log unmapped bindings ONCE per call so we see if a remapped axis points at a non-KEY binding
            Timber.tag("Html5Input").d(
                "axis-synth %s — no Html5KeyMapping.specFor (drop, axis=%.2f)", binding.name, value,
            )
            return
        }
        val currentlyDown = axisKeyState[binding] == true
        val absVal = if (value < 0) -value else value
        when {
            !currentlyDown && absVal > 0.5f -> {
                axisKeyState[binding] = true
                Timber.tag("Html5Input").d(
                    "axis-synth %s isDown=true (axis=%.2f thresh=0.50) state={%s}",
                    binding.name, value, snapshotDownState(),
                )
                emitKeyEvent(binding, isDown = true)
            }
            currentlyDown && absVal < 0.45f -> {
                axisKeyState[binding] = false
                Timber.tag("Html5Input").d(
                    "axis-synth %s isDown=false (axis=%.2f thresh=0.45) state={%s}",
                    binding.name, value, snapshotDownState(),
                )
                emitKeyEvent(binding, isDown = false)
            }
            // 0.45 <= absVal <= 0.5 → deadband, no state change
        }
    }

    // surface the FULL down-state of every axis-driven key on each transition.
    // makes the logcat tell us instantly when a key's state is being trampled by an unrelated
    // axis (the bug we just fixed in the controller's per-event aggregation pass).
    private fun snapshotDownState(): String =
        axisKeyState.entries
            .filter { it.value }
            .joinToString(",") { it.key.name }
            .ifEmpty { "—" }

    fun onCursorMove(dx: Float, dy: Float) {
        val newX = (cursorX + dx).coerceIn(0f, (viewportW - 1).toFloat())
        val newY = (cursorY + dy).coerceIn(0f, (viewportH - 1).toFloat())
        cursorX = newX
        cursorY = newY
        bridge.enqueue(eventJson("type" to "cursormove", "x" to newX.toInt(), "y" to newY.toInt()))
    }

    fun updateViewport(width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        cursorX = viewportW / 2f
        cursorY = viewportH / 2f
    }

    fun reset() {
        axisKeyState.clear()
        cursorX = viewportW / 2f
        cursorY = viewportH / 2f
    }

    private fun emitKeyEvent(binding: Binding, isDown: Boolean) {
        val spec = Html5KeyMapping.specFor(binding) ?: return
        val type = if (isDown) "keydown" else "keyup"
        bridge.enqueue(
            eventJson(
                "type" to type,
                "key" to spec.key,
                "code" to spec.code,
                "keyCode" to spec.keyCode,
                "charCode" to spec.charCode,
                "x" to cursorX.toInt(),
                "y" to cursorY.toInt(),
            ),
        )
        // confirm enqueue path actually fires (vs. silently swallowed by spec lookup).
        // helps differentiate "synth didn't fire" from "JS bridge didn't drain" on the next round.
        Timber.tag("Html5Input").d(
            "synth-enqueue %s synth=%s isDown=%b", binding.name, type, isDown,
        )
    }

    private fun emitMouseButton(binding: Binding, isDown: Boolean) {
        val button = mouseButtonIndex(binding) ?: return
        val type = if (isDown) "mousedown" else "mouseup"
        bridge.enqueue(
            eventJson("type" to type, "button" to button, "x" to cursorX.toInt(), "y" to cursorY.toInt()),
        )
        // for mouseup of LEFT button: also synthesize click at same coords (RMMV/C3 expectation)
        if (!isDown && button == 0) {
            bridge.enqueue(
                eventJson("type" to "click", "button" to 0, "x" to cursorX.toInt(), "y" to cursorY.toInt()),
            )
        }
    }

    // delegates to mouseButtonIndex so membership is encoded ONCE (single 3-way table below).
    @VisibleForTesting
    internal fun isMouseButton(b: Binding): Boolean = mouseButtonIndex(b) != null

    // W3C MouseEvent.button: 0=left, 1=middle, 2=right
    private fun mouseButtonIndex(b: Binding): Int? = when (b) {
        Binding.MOUSE_LEFT_BUTTON -> 0
        Binding.MOUSE_RIGHT_BUTTON -> 2
        Binding.MOUSE_MIDDLE_BUTTON -> 1
        else -> null
    }

    // centralizes JSON escaping (org.json handles backslash/quote/non-ascii) + shared shape.
    // org.json emits compact `{"k":v,...}` with no spaces -- JS consumes by key, order-agnostic.
    private fun eventJson(vararg pairs: Pair<String, Any>): String {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj.toString()
    }
}
