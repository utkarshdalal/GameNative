package app.gamenative.html5.input

import com.winlator.inputcontrols.Binding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// — synthesizer state machine.
// pure-jvm: Html5InputBridge is a real instance (no Android deps), Binding is a Java enum,
// Html5KeyMapping is a Kotlin object. no Robolectric needed.
class Html5InputSynthesizerTest {

    private fun newSynth(w: Int = 1280, h: Int = 720): Pair<Html5InputBridge, Html5InputSynthesizer> {
        val bridge = Html5InputBridge()
        return bridge to Html5InputSynthesizer(bridge, w, h)
    }

    @Test fun KEY_UP_press_enqueues_keydown_with_keyCode_38() {
        val (bridge, synth) = newSynth()
        synth.onBindingPress(Binding.KEY_UP, isDown = true)
        val drained = bridge.drainQueue()
        assertTrue(drained, drained.contains("\"type\":\"keydown\""))
        assertTrue(drained.contains("\"keyCode\":38"))
    }

    @Test fun KEY_UP_release_enqueues_keyup() {
        val (bridge, synth) = newSynth()
        synth.onBindingPress(Binding.KEY_UP, isDown = false)
        assertTrue(bridge.drainQueue().contains("\"type\":\"keyup\""))
    }

    @Test fun analog_stick_above_threshold_then_below_emits_keydown_then_keyup() {
        val (bridge, synth) = newSynth()
        synth.onAxisValue(Binding.KEY_LEFT, 0.6f)  // crosses 0.5 → keydown
        val first = bridge.drainQueue()
        assertTrue(first, first.contains("\"type\":\"keydown\""))
        assertTrue(first.contains("\"keyCode\":37"))

        synth.onAxisValue(Binding.KEY_LEFT, 0.4f)  // crosses 0.45 down → keyup
        val second = bridge.drainQueue()
        assertTrue(second, second.contains("\"type\":\"keyup\""))
    }

    @Test fun analog_stick_holding_above_threshold_does_not_double_fire() {
        val (bridge, synth) = newSynth()
        synth.onAxisValue(Binding.KEY_LEFT, 0.6f)
        synth.onAxisValue(Binding.KEY_LEFT, 0.7f)
        synth.onAxisValue(Binding.KEY_LEFT, 0.8f)
        // only the FIRST crossing emits — count occurrences of "keydown"
        val drained = bridge.drainQueue()
        val count = drained.split("\"type\":\"keydown\"").size - 1
        assertEquals(1, count)
    }

    @Test fun analog_stick_in_deadband_emits_no_event() {
        val (bridge, synth) = newSynth()
        // start at 0 (down=false), move to 0.48 — still below 0.5 threshold
        synth.onAxisValue(Binding.KEY_LEFT, 0.48f)
        assertEquals("[]", bridge.drainQueue())
    }

    @Test fun MOUSE_LEFT_press_enqueues_mousedown_at_cursor() {
        val (bridge, synth) = newSynth(1000, 500)
        // cursor starts at center (500, 250)
        synth.onBindingPress(Binding.MOUSE_LEFT_BUTTON, isDown = true)
        val drained = bridge.drainQueue()
        assertTrue(drained.contains("\"type\":\"mousedown\""))
        assertTrue(drained.contains("\"button\":0"))
        assertTrue(drained.contains("\"x\":500"))
        assertTrue(drained.contains("\"y\":250"))
    }

    @Test fun MOUSE_LEFT_release_emits_mouseup_then_click() {
        val (bridge, synth) = newSynth()
        synth.onBindingPress(Binding.MOUSE_LEFT_BUTTON, isDown = false)
        val drained = bridge.drainQueue()
        assertTrue(drained.contains("\"type\":\"mouseup\""))
        assertTrue(drained.contains("\"type\":\"click\""))
    }

    @Test fun updateViewport_recenters_cursor() {
        val (_, synth) = newSynth(100, 100)
        synth.onCursorMove(40f, 40f)  // (50,50) → (90,90)
        synth.updateViewport(1000, 500)
        assertEquals(500f, synth.cursorX, 0.001f)
        assertEquals(250f, synth.cursorY, 0.001f)
    }

    @Test fun onCursorMove_clamps_to_viewport_bounds() {
        val (_, synth) = newSynth(100, 100)
        synth.onCursorMove(1000f, 1000f)
        assertEquals(99f, synth.cursorX, 0.001f)
        assertEquals(99f, synth.cursorY, 0.001f)
        synth.onCursorMove(-10000f, -10000f)
        assertEquals(0f, synth.cursorX, 0.001f)
        assertEquals(0f, synth.cursorY, 0.001f)
    }

    @Test fun GAMEPAD_BUTTON_A_does_not_synthesize() {
        val (bridge, synth) = newSynth()
        synth.onBindingPress(Binding.GAMEPAD_BUTTON_A, isDown = true)
        assertEquals("[]", bridge.drainQueue())
    }
}
