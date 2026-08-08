package app.gamenative.html5.input

import com.winlator.inputcontrols.GamepadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// pure-jvm — @JavascriptInterface is metadata-only on jvm classpath (verified P04).
// GamepadState is plain java, no android <clinit>.
class Html5GamepadBridgeTest {

    private fun state(block: GamepadState.() -> Unit = {}) = GamepadState().apply(block)

    @Test
    fun readState_before_update_returns_empty_state_array() {
        val bridge = Html5GamepadBridge()
        val json = bridge.readState()
        assertTrue("must be JSON array", json.startsWith("[") && json.endsWith("]"))
        assertTrue("connected should be false on empty", json.contains("\"connected\":false"))
        assertTrue("standard mapping required", json.contains("\"mapping\":\"standard\""))
        assertTrue("index 0 required", json.contains("\"index\":0"))
    }

    @Test
    fun buildGamepadJson_emits_16_button_entries() {
        val json = Html5GamepadBridge.buildGamepadJson(state())
        val buttonCount = json.substringAfter("\"buttons\":[").substringBefore("]")
            .count { it == '{' }
        assertEquals(16, buttonCount)
    }

    @Test
    fun buildGamepadJson_emits_4_axis_values_per_w3c_standard_mapping() {
        val json = Html5GamepadBridge.buildGamepadJson(state())
        val axes = json.substringAfter("\"axes\":[").substringBefore("]")
        assertEquals(4, axes.split(",").size)
    }

    @Test
    fun button_a_press_surfaces_at_index_0() {
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { setPressed(0, true) })
        val json = bridge.readState()
        // first button in buttons[] has pressed:true
        val firstButton = json.substringAfter("\"buttons\":[").substringBefore(",{")
        assertTrue("expected first button pressed: got $firstButton", firstButton.contains("\"pressed\":true"))
        assertTrue("connected true when anything pressed", json.contains("\"connected\":true"))
    }

    @Test
    fun trigger_L_surfaces_only_in_buttons_6() {
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { triggerL = 0.75f })
        val json = bridge.readState()
        val buttonEntries = json.substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertEquals(16, buttonEntries.size)
        assertTrue("buttons[6] trigger L value missing", buttonEntries[6].contains("\"value\":0.75"))
        // standard mapping: triggers MUST NOT contaminate axes (regression: CrossCode read
        // axes[2]=triggerL as RX → right stick LR dead).
        val axes = json.substringAfter("\"axes\":[").substringBefore("]").split(",")
        assertEquals(4, axes.size)
        axes.forEach { assertEquals("0.0", it) }
    }

    @Test
    fun trigger_R_surfaces_only_in_buttons_7() {
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { triggerR = 1.0f })
        val json = bridge.readState()
        val buttonEntries = json.substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertTrue("buttons[7] trigger R value missing", buttonEntries[7].contains("\"value\":1.0"))
        val axes = json.substringAfter("\"axes\":[").substringBefore("]").split(",")
        assertEquals(4, axes.size)
        axes.forEach { assertEquals("0.0", it) }
    }

    @Test
    fun thumb_LX_LY_emit_to_axes_0_1() {
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { thumbLX = 0.5f; thumbLY = -0.5f })
        val json = bridge.readState()
        val axes = json.substringAfter("\"axes\":[").substringBefore("]").split(",")
        assertEquals(0.5f.toString(), axes[0])
        assertEquals((-0.5f).toString(), axes[1])
    }

    @Test
    fun thumb_RX_RY_emit_to_axes_2_3_per_w3c_standard() {
        // regression guard: pre-fix layout put thumbRX at axes[3] (treated as RY by standard-
        // mapping consumers). CrossCode + any standard-aware engine expects [LX, LY, RX, RY].
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { thumbRX = 0.8f; thumbRY = -0.3f })
        val json = bridge.readState()
        val axes = json.substringAfter("\"axes\":[").substringBefore("]").split(",")
        assertEquals(4, axes.size)
        assertEquals(0.8f.toString(), axes[2])
        assertEquals((-0.3f).toString(), axes[3])
    }

    @Test
    fun dpad_maps_to_w3c_order_12_UP_13_DOWN_14_LEFT_15_RIGHT() {
        // GamepadState.dpad native: [0]=UP, [1]=RIGHT, [2]=DOWN, [3]=LEFT
        // W3C: buttons[12]=UP, [13]=DOWN, [14]=LEFT, [15]=RIGHT
        val bridge = Html5GamepadBridge()
        bridge.updateState(state { dpad[0] = true }) // UP
        var entries = bridge.readState().substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertTrue("UP at buttons[12]", entries[12].contains("\"pressed\":true"))
        assertTrue("no phantom at buttons[13]", entries[13].contains("\"pressed\":false"))

        bridge.updateState(state { dpad[1] = true }) // RIGHT
        entries = bridge.readState().substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertTrue("RIGHT at buttons[15]", entries[15].contains("\"pressed\":true"))

        bridge.updateState(state { dpad[2] = true }) // DOWN
        entries = bridge.readState().substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertTrue("DOWN at buttons[13]", entries[13].contains("\"pressed\":true"))

        bridge.updateState(state { dpad[3] = true }) // LEFT
        entries = bridge.readState().substringAfter("\"buttons\":[").substringBefore("]}]")
            .split("},{")
        assertTrue("LEFT at buttons[14]", entries[14].contains("\"pressed\":true"))
    }
}
