package app.gamenative.html5.input

import com.winlator.inputcontrols.Binding

// LUT: Binding.KEY_* → W3C KeyboardEvent fields. keyCode/which carry RMMV legacy compat
// (Input._onKeyDown reads event.keyCode); key/code carry modern Chromium handlers; charCode
// set only for printable keys (KEY_SPACE, KEY_A..Z, KEY_0..9, KEY_KP_0..9).
// runCatching used because Binding.java may not have ALL of KEY_F1..F12 / KEY_0..9 /
// KEY_KP_0..9 -- the LUT skips missing names gracefully.
object Html5KeyMapping {
    data class KeySpec(
        val key: String,
        val code: String,
        val keyCode: Int,
        val charCode: Int = 0,
    )

    val KEY_MAP: Map<Binding, KeySpec> = buildMap {
        // arrow keys (RMMV load-bearing)
        put(Binding.KEY_UP, KeySpec("ArrowUp", "ArrowUp", 38))
        put(Binding.KEY_DOWN, KeySpec("ArrowDown", "ArrowDown", 40))
        put(Binding.KEY_LEFT, KeySpec("ArrowLeft", "ArrowLeft", 37))
        put(Binding.KEY_RIGHT, KeySpec("ArrowRight", "ArrowRight", 39))

        // navigation keys
        put(Binding.KEY_ENTER, KeySpec("Enter", "Enter", 13))
        put(Binding.KEY_ESC, KeySpec("Escape", "Escape", 27))
        put(Binding.KEY_SPACE, KeySpec(" ", "Space", 32, 32))
        put(Binding.KEY_BKSP, KeySpec("Backspace", "Backspace", 8))
        put(Binding.KEY_DEL, KeySpec("Delete", "Delete", 46))
        put(Binding.KEY_TAB, KeySpec("Tab", "Tab", 9))
        put(Binding.KEY_HOME, KeySpec("Home", "Home", 36))
        put(Binding.KEY_PG_UP, KeySpec("PageUp", "PageUp", 33))
        put(Binding.KEY_PG_DOWN, KeySpec("PageDown", "PageDown", 34))

        // modifier keys
        put(Binding.KEY_CTRL_L, KeySpec("Control", "ControlLeft", 17))
        put(Binding.KEY_CTRL_R, KeySpec("Control", "ControlRight", 17))
        put(Binding.KEY_SHIFT_L, KeySpec("Shift", "ShiftLeft", 16))
        put(Binding.KEY_SHIFT_R, KeySpec("Shift", "ShiftRight", 16))
        put(Binding.KEY_ALT_L, KeySpec("Alt", "AltLeft", 18))
        put(Binding.KEY_ALT_R, KeySpec("Alt", "AltRight", 18))

        // function keys
        for (i in 1..12) {
            val binding = runCatching { Binding.valueOf("KEY_F$i") }.getOrNull() ?: continue
            put(binding, KeySpec("F$i", "F$i", 111 + i))
        }

        // alpha keys (KEY_A=65 → KEY_Z=90; charCode = lowercase ascii 97..122)
        for (i in 0..25) {
            val name = "KEY_${'A' + i}"
            val binding = runCatching { Binding.valueOf(name) }.getOrNull() ?: continue
            val ch = ('a' + i).toString()
            put(binding, KeySpec(ch, "Key${('A' + i)}", 65 + i, 97 + i))
        }

        // digit keys (KEY_0=48 → KEY_9=57; charCode = ascii 48..57)
        for (i in 0..9) {
            val binding = runCatching { Binding.valueOf("KEY_$i") }.getOrNull() ?: continue
            put(binding, KeySpec(i.toString(), "Digit$i", 48 + i, 48 + i))
        }

        // numeric keypad (KEY_KP_0..9; key="0".."9", code="Numpad0".."Numpad9", keyCode 96..105)
        for (i in 0..9) {
            val binding = runCatching { Binding.valueOf("KEY_KP_$i") }.getOrNull() ?: continue
            put(binding, KeySpec(i.toString(), "Numpad$i", 96 + i, 48 + i))
        }
    }

    fun specFor(binding: Binding): KeySpec? = KEY_MAP[binding]
}
