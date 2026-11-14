package app.gamenative.html5.input

import android.webkit.JavascriptInterface
import com.winlator.inputcontrols.GamepadState

// read synchronously from the JS thread on every navigator.getGamepads() poll. GamepadState's
// floats are torn-read-prone, so updateState serializes on main and readState is just a
// @Volatile ref read -- no shared mutable primitives cross threads.
class Html5GamepadBridge {
    @Volatile
    private var snapshot: String = EMPTY_STATE

    fun updateState(state: GamepadState) {
        snapshot = buildGamepadJson(state)
    }

    @JavascriptInterface
    fun readState(): String = snapshot

    companion object {
        // built by the same serializer as live updates so the wire shape can't drift.
        internal val EMPTY_STATE: String = buildGamepadJson(GamepadState())

        // GamepadState uses Wine's button order, which differs from the W3C standard mapping from slot 6 on:
        // Wine: A=0 B=1 X=2 Y=3 L1=4 R1=5 SELECT=6 START=7 L3=8 R3=9 L2=10 R2=11
        // W3C:  A=0 B=1 X=2 Y=3 L1=4 R1=5 L2=6 R2=7 SELECT=8 START=9 L3=10 R3=11
        // dpad is [UP, RIGHT, DOWN, LEFT] natively; W3C wants buttons 12..15 = UP, DOWN, LEFT, RIGHT.
        internal fun buildGamepadJson(s: GamepadState): String {
            val buttonPressed = BooleanArray(16).apply {
                this[0] = s.isPressed(0) // A
                this[1] = s.isPressed(1) // B
                this[2] = s.isPressed(2) // X
                this[3] = s.isPressed(3) // Y
                this[4] = s.isPressed(4) // L1
                this[5] = s.isPressed(5) // R1
                this[6] = s.isPressed(10) // L2
                this[7] = s.isPressed(11) // R2
                this[8] = s.isPressed(6)  // SELECT
                this[9] = s.isPressed(7)  // START
                this[10] = s.isPressed(8) // L3
                this[11] = s.isPressed(9) // R3
                this[12] = s.dpad[0] // UP
                this[13] = s.dpad[2] // DOWN
                this[14] = s.dpad[3] // LEFT
                this[15] = s.dpad[1] // RIGHT
            }
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
            // "standard" mapping = exactly 4 axes. triggers live on buttons[6]/[7]; putting them in
            // axes shifts RX/RY for consumers that read by index.
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
