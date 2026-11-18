package app.gamenative.html5.input

import androidx.annotation.VisibleForTesting
import com.winlator.inputcontrols.Binding
import org.json.JSONObject
import timber.log.Timber

// Kotlin owns input state + binding semantics; JS owns DOM dispatch. no synthetic key repeat.
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

    // main thread only.
    private val axisKeyState = mutableMapOf<Binding, Boolean>()

    // GAMEPAD_* / MOUSE_MOVE_* never reach here; dispatchBinding routes them elsewhere.
    fun onBindingPress(binding: Binding, isDown: Boolean) {
        when {
            Html5KeyMapping.specFor(binding) != null -> emitKeyEvent(binding, isDown)
            isMouseButton(binding) -> emitMouseButton(binding, isDown)
        }
    }

    // hysteresis: down above 0.5, up below 0.45, so a stick resting near the threshold doesn't chatter.
    fun onAxisValue(binding: Binding, value: Float) {
        if (Html5KeyMapping.specFor(binding) == null) {
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
        }
    }

    // full down-state in each log line shows when an unrelated axis tramples a key's state.
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
        // RMMV/C3 listen for click, not just mouseup.
        if (!isDown && button == 0) {
            bridge.enqueue(
                eventJson("type" to "click", "button" to 0, "x" to cursorX.toInt(), "y" to cursorY.toInt()),
            )
        }
    }

    @VisibleForTesting
    internal fun isMouseButton(b: Binding): Boolean = mouseButtonIndex(b) != null

    // W3C MouseEvent.button: 0=left, 1=middle, 2=right
    private fun mouseButtonIndex(b: Binding): Int? = when (b) {
        Binding.MOUSE_LEFT_BUTTON -> 0
        Binding.MOUSE_RIGHT_BUTTON -> 2
        Binding.MOUSE_MIDDLE_BUTTON -> 1
        else -> null
    }

    private fun eventJson(vararg pairs: Pair<String, Any>): String {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj.toString()
    }
}
