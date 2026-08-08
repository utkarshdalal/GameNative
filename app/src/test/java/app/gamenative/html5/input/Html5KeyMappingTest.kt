package app.gamenative.html5.input

import com.winlator.inputcontrols.Binding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// LUT correctness — Binding.KEY_* → W3C KeyboardEvent fields.
// keyCode/which carry RMMV legacy compat; key/code carry modern Chromium handlers;
// charCode set only for printable keys (KEY_SPACE, KEY_A..Z, KEY_0..9).
class Html5KeyMappingTest {

    @Test fun KEY_UP_maps_to_ArrowUp_keyCode_38() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_UP)
        assertNotNull(spec)
        assertEquals("ArrowUp", spec!!.key)
        assertEquals("ArrowUp", spec.code)
        assertEquals(38, spec.keyCode)
        assertEquals(0, spec.charCode)
    }

    @Test fun KEY_A_maps_to_lowercase_a_with_charCode_97() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_A)
        assertNotNull(spec)
        assertEquals("a", spec!!.key)
        assertEquals("KeyA", spec.code)
        assertEquals(65, spec.keyCode)
        assertEquals(97, spec.charCode)
    }

    @Test fun KEY_SPACE_charCode_set_to_32() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_SPACE)
        assertNotNull(spec)
        assertEquals(" ", spec!!.key)
        assertEquals("Space", spec.code)
        assertEquals(32, spec.keyCode)
        assertEquals(32, spec.charCode)
    }

    @Test fun KEY_ENTER_keyCode_13() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_ENTER)
        assertEquals(13, spec!!.keyCode)
    }

    @Test fun KEY_ESC_keyCode_27() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_ESC)
        assertEquals(27, spec!!.keyCode)
    }

    @Test fun GAMEPAD_BUTTON_A_returns_null() {
        // LUT scoped to KEY_* — gamepad bindings route through Html5GamepadMapping path
        assertNull(Html5KeyMapping.specFor(Binding.GAMEPAD_BUTTON_A))
    }

    @Test fun KEY_F1_keyCode_112() {
        val spec = Html5KeyMapping.specFor(Binding.KEY_F1)
        assertEquals("F1", spec!!.key)
        assertEquals("F1", spec.code)
        assertEquals(112, spec.keyCode)
    }

    @Test fun all_KEY_A_to_Z_in_keyCode_65_to_90_range() {
        for (i in 0..25) {
            val name = "KEY_${'A' + i}"
            val binding = runCatching { Binding.valueOf(name) }.getOrNull() ?: continue
            val spec = Html5KeyMapping.specFor(binding) ?: continue
            assertTrue("$name keyCode out of range: ${spec.keyCode}", spec.keyCode in 65..90)
        }
    }
}
