package app.gamenative.steamcontroller

import com.winlator.inputcontrols.GamepadState
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode

/**
 * Test fakes for the mapping engine. [RecordingSink] captures every output the [ProfileInterpreter] emits
 * so tests can assert behaviour headlessly; [TraceReader] loads a captured golden trace (real controller
 * reports) for replay. See docs/AUTOMATION-PLAN.md.
 */

/** Records all interpreter outputs instead of doing IO. */
class RecordingSink : ScOutputSink {
    data class MouseBtn(val button: Pointer.Button, val pressed: Boolean)
    data class KeyEv(val key: XKeycode, val pressed: Boolean)

    /** Union of all virtual-pad button bits ever seen pressed. */
    var gamepadButtonsSeen: Int = 0
        private set
    val dpadSeen = BooleanArray(4)
    var maxThumbLX = 0f; var minThumbLX = 0f
    var maxThumbRX = 0f; var minThumbRX = 0f
    var maxTriggerL = 0f; var maxTriggerR = 0f
    // Most-recent per-frame stick values (for tests that need the current deflection, e.g. recenter-on-lift).
    var lastThumbLX = 0f; var lastThumbRX = 0f; var lastThumbRY = 0f
    var gamepadFrames = 0; private set

    var mouseDx = 0L; var mouseDy = 0L
    var mouseMoves = 0; private set

    // Last absolute-mouse target (screen fraction 0..1) + count, for AbsoluteMouse pad tests.
    var lastAbsX = -1f; var lastAbsY = -1f
    var mouseAbsMoves = 0; private set

    val mouseButtons = ArrayList<MouseBtn>()
    val keys = ArrayList<KeyEv>()

    override fun gamepad(state: GamepadState) {
        gamepadFrames++
        gamepadButtonsSeen = gamepadButtonsSeen or (state.buttons.toInt() and 0xFFFF)
        for (i in 0..3) if (state.dpad[i]) dpadSeen[i] = true
        maxThumbLX = maxOf(maxThumbLX, state.thumbLX); minThumbLX = minOf(minThumbLX, state.thumbLX)
        maxThumbRX = maxOf(maxThumbRX, state.thumbRX); minThumbRX = minOf(minThumbRX, state.thumbRX)
        lastThumbLX = state.thumbLX; lastThumbRX = state.thumbRX; lastThumbRY = state.thumbRY
        maxTriggerL = maxOf(maxTriggerL, state.triggerL); maxTriggerR = maxOf(maxTriggerR, state.triggerR)
    }

    override fun mouseMove(dx: Int, dy: Int) {
        mouseMoves++; mouseDx += dx; mouseDy += dy
    }

    override fun mouseMoveAbs(nx: Float, ny: Float) {
        mouseAbsMoves++; lastAbsX = nx; lastAbsY = ny
    }

    override fun mouseButton(button: Pointer.Button, pressed: Boolean) {
        mouseButtons.add(MouseBtn(button, pressed))
    }

    override fun key(key: XKeycode, pressed: Boolean) {
        keys.add(KeyEv(key, pressed))
    }

    fun keyPresses(key: XKeycode) = keys.count { it.key == key && it.pressed }
    fun mouseButtonPresses(button: Pointer.Button) = mouseButtons.count { it.button == button && it.pressed }
}

object TraceReader {
    /** Load a length-prefixed trace (1-byte length + report bytes per frame) into decoded states. */
    fun loadStates(resourcePath: String): List<TritonState> {
        val raw = readResource(resourcePath)
        val out = ArrayList<TritonState>()
        var i = 0
        while (i < raw.size) {
            val n = raw[i].toInt() and 0xFF; i++
            if (i + n > raw.size) break
            val frame = raw.copyOfRange(i, i + n); i += n
            TritonProtocol.decodeBleState(frame, frame.size)?.let { out.add(it) }
        }
        return out
    }

    private fun readResource(path: String): ByteArray =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("test resource not found: $path")
}

/** Load a text test resource from `sc/` (shared by the importer/engine tests; was duplicated per test class). */
fun load(name: String): String =
    (RecordingSink::class.java.classLoader ?: ClassLoader.getSystemClassLoader())
        .getResourceAsStream("sc/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("missing test resource sc/$name")

/**
 * Structural fixture exercising all three build-step-3 mechanisms — an action layer (Overlay), a preset switch
 * (CHANGE_PRESET), and a mode_shift — so the importer decodes multiple sets + a layer + a mode-shift overlay. The
 * consuming tests assert the decoded set/layer/overlay STRUCTURE, not runtime press outcomes, so the exact
 * controller_action target numbers below aren't behaviourally asserted. (Formerly TritonBleEngineSelfTest.SMOKE_CONFIG,
 * relocated to the test source set when the on-device self-test harness was removed.)
 */
const val SMOKE_CONFIG = """
"controller_mappings"
{
	"version"		"3"
	"title"		"Engine Smoke Test"
	"controller_type"		"controller_triton"
	"actions" { "Default" { "title" "Default" "legacy_set" "1" } "Combat" { "title" "Combat" "legacy_set" "1" } }
	"action_layers" { "Overlay" { "title" "Overlay" "legacy_set" "1" "set_layer" "1" "parent_set_name" "Default" } }

	"group" { "id" "0" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 1" } } } }
		"button_b" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 2" } } } } } }
	"group" { "id" "1" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 9" } } } } } }
	"group" { "id" "2" "mode" "switches" "inputs" {
		"left_bumper"      { "activators" { "Full_Press" { "bindings" { "binding" "controller_action hold_layer 3 0 0" } } } }
		"right_bumper"     { "activators" { "Full_Press" { "bindings" { "binding" "controller_action CHANGE_PRESET 2 0 0" } } } }
		"button_back_left" { "activators" { "Full_Press" { "bindings" { "binding" "mode_shift button_diamond 1" } } } } } }
	"group" { "id" "3" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 3" } } } } } }
	"group" { "id" "4" "mode" "switches" "inputs" {
		"right_bumper" { "activators" { "Full_Press" { "bindings" { "binding" "controller_action CHANGE_PRESET 1 0 0" } } } } } }
	"group" { "id" "5" "mode" "four_buttons" "inputs" {
		"button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press 8" } } } } } }

	"preset" { "id" "0" "name" "Default" "group_source_bindings" {
		"0" "button_diamond active" "2" "switch active" "1" "button_diamond active modeshift" } }
	"preset" { "id" "1" "name" "Combat" "group_source_bindings" {
		"3" "button_diamond active" "4" "switch active" } }
	"preset" { "id" "2" "name" "Overlay" "group_source_bindings" {
		"5" "button_diamond active" } }
}
"""
