package app.gamenative.html5.input

import android.webkit.JavascriptInterface
import com.winlator.inputcontrols.GamepadState

// sync read bridge. called from WebView JS thread on each navigator.getGamepads()
// poll. thread-safety: snapshot is a @Volatile String (atomic ref swap on jvm); float fields
// in GamepadState are torn-read-prone, so we serialize once on update (main thread) and
// return the already-built string from readState (JS thread) -- zero shared mutable primitives
// crossing threads. matches "no allocation in hot path" -- updateState does the work,
// readState is a pointer read.
class Html5GamepadBridge {
    @Volatile
    private var snapshot: String = EMPTY_STATE

    // called from main thread via Html5InputController; may be called at ~60hz
    // during active input -- allocation cost here is one String.format equivalent, negligible.
    fun updateState(state: GamepadState) {
        snapshot = buildGamepadJson(state)
    }

    @JavascriptInterface
    fun readState(): String = snapshot

    companion object {
        // default snapshot: index 0 present but connected:false, no phantom presses.
        // matches array-of-length-1 schema (forward-compat for 4 slots).
        // derived from the SAME serializer as live updates (zeroed GamepadState) so there's
        // ONE source of truth for the wire shape -- no parallel hand-written literal to drift.
        internal val EMPTY_STATE: String = buildGamepadJson(GamepadState())

        // Wine's Binding enum order
        // A=0 B=1 X=2 Y=3 L1=4 R1=5 SELECT=6 START=7 L3=8 R3=9 L2=10 R2=11
        // does NOT match W3C standard mapping (what navigator.getGamepads() consumers expect):
        // A=0 B=1 X=2 Y=3 L1=4 R1=5 L2=6 R2=7 SELECT=8 START=9 L3=10 R3=11
        
        // Slots 0..5 match. Slots 6..11 require an explicit remap because PhysicalControllerHandler
        // calls state.setPressed(buttonIdx) with buttonIdx = binding.ordinal - GAMEPAD_BUTTON_A.ordinal
        // -- that's Wine's order. This LUT translates Wine slots → W3C slots for serialization.
        
        // dpad boolean[] uses GamepadState's native order: [0]=UP, [1]=RIGHT, [2]=DOWN, [3]=LEFT.
        // remap to W3C order: UP=buttons[12], DOWN=buttons[13], LEFT=buttons[14], RIGHT=buttons[15].
        // triggers appear at BOTH buttons[6/7] (digital+analog) AND axes[2/5] (chrome behavior).
        internal fun buildGamepadJson(s: GamepadState): String {
            // W3C slot → Wine slot for entries that disagree (0..5 match identity)
            val buttonPressed = BooleanArray(16).apply {
                this[0] = s.isPressed(0) // A
                this[1] = s.isPressed(1) // B
                this[2] = s.isPressed(2) // X
                this[3] = s.isPressed(3) // Y
                this[4] = s.isPressed(4) // L1
                this[5] = s.isPressed(5) // R1
                this[6] = s.isPressed(10) // W3C L2 ← Wine slot 10
                this[7] = s.isPressed(11) // W3C R2 ← Wine slot 11
                this[8] = s.isPressed(6)  // W3C SELECT ← Wine slot 6
                this[9] = s.isPressed(7)  // W3C START ← Wine slot 7
                this[10] = s.isPressed(8) // W3C L3 ← Wine slot 8
                this[11] = s.isPressed(9) // W3C R3 ← Wine slot 9
                this[12] = s.dpad[0] // UP
                this[13] = s.dpad[2] // DOWN
                this[14] = s.dpad[3] // LEFT
                this[15] = s.dpad[1] // RIGHT
            }
            // trigger analog values override buttons[6]/[7] .value (keep pressed flag from bitmask).
            val buttonValue = FloatArray(16) { if (buttonPressed[it]) 1f else 0f }.apply {
                this[6] = s.triggerL
                this[7] = s.triggerR
            }
            val connected = buttonPressed.any { it } || hasStickMotion(s)

            val sb = StringBuilder(1024)
            sb.append("[{\"index\":0,\"id\":\"GameNative Controller\",\"mapping\":\"standard\",")
            sb.append("\"connected\":").append(connected).append(",\"timestamp\":0,")
            sb.append("\"buttons\":[")
            for (i in 0..15) {
                if (i > 0) sb.append(',')
                sb.append("{\"pressed\":").append(buttonPressed[i])
                sb.append(",\"touched\":").append(buttonPressed[i])
                sb.append(",\"value\":").append(buttonValue[i]).append('}')
            }
            sb.append("],\"axes\":[")
            // W3C standard mapping = 4 axes [LX, LY, RX, RY]. triggers are NOT in axes -- they
            // already surface as analog values on buttons[6]/[7] above. emitting 6 axes while
            // declaring "mapping": "standard" mis-routed RX/RY for any consumer reading by index
            // (CrossCode: axes[2]=triggerL=0 → right-stick LR dead).
            sb.append(s.thumbLX).append(',').append(s.thumbLY).append(',')
            sb.append(s.thumbRX).append(',').append(s.thumbRY)
            sb.append("]}]")
            return sb.toString()
        }

        private fun hasStickMotion(s: GamepadState): Boolean {
            val threshold = 0.01f
            return kotlin.math.abs(s.thumbLX) > threshold ||
                kotlin.math.abs(s.thumbLY) > threshold ||
                kotlin.math.abs(s.thumbRX) > threshold ||
                kotlin.math.abs(s.thumbRY) > threshold ||
                s.triggerL > threshold ||
                s.triggerR > threshold
        }
    }
}
