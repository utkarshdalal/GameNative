package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for the split-trackpad on-screen keyboard ([ScKeyboard]) — selection + commit + shift, headless. */
@RunWith(RobolectricTestRunner::class)
class ScKeyboardTest {

    /** Pad coords that land on the center of grid [cell] (row 0 = top), in the ±32768 raw space. */
    private fun coordsFor(cell: Int): Pair<Int, Int> {
        val col = cell % ScKeyboardLayout.COLS
        val rowTop = cell / ScKeyboardLayout.COLS
        val nx = (col + 0.5f) / ScKeyboardLayout.COLS
        val ny = ((ScKeyboardLayout.ROWS - 1 - rowTop) + 0.5f) / ScKeyboardLayout.ROWS
        return ((nx - 0.5f) * 65536f).toInt() to ((ny - 0.5f) * 65536f).toInt()
    }

    private fun right(cell: Int, click: Boolean = false): TritonState {
        val (x, y) = coordsFor(cell)
        var b = TritonProtocol.BTN_RPAD_TOUCH
        if (click) b = b or TritonProtocol.BTN_RPAD_CLICK
        return TritonState().apply { buttons = b; rightPadX = x; rightPadY = y }
    }

    private fun left(cell: Int, click: Boolean = false): TritonState {
        val (x, y) = coordsFor(cell)
        var b = TritonProtocol.BTN_LPAD_TOUCH
        if (click) b = b or TritonProtocol.BTN_LPAD_CLICK
        return TritonState().apply { buttons = b; leftPadX = x; leftPadY = y }
    }

    private fun rightIndexOf(label: String) = ScKeyboardLayout.RIGHT.indexOfFirst { (it as? KbKey.Chr)?.label == label }

    @Test
    fun `clicking the right pad over a letter types it`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink)
        kb.activate()
        val y = rightIndexOf("y")
        kb.update(right(y, click = false)) // hover (sets cursor; no fire)
        kb.update(right(y, click = true))  // click rising edge -> type
        assertEquals(1, sink.keyPresses(XKeycode.KEY_Y))
    }

    @Test
    fun `inactive keyboard ignores input`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink) // not activated
        kb.update(right(rightIndexOf("y"), click = true))
        assertEquals(0, sink.keys.size)
    }

    @Test
    fun `sticky shift capitalizes the next letter only`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink)
        kb.activate()
        val shift = ScKeyboardLayout.LEFT.indexOf(KbKey.Shift)
        kb.update(left(shift, click = false))
        kb.update(left(shift, click = true)) // toggle shift on
        val y = rightIndexOf("y")
        kb.update(right(y, click = false))
        kb.update(right(y, click = true))    // type Y with shift held
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && !it.pressed })
        assertEquals(1, sink.keyPresses(XKeycode.KEY_Y))
        // shift was one-shot: a second letter has no shift
        val u = rightIndexOf("u")
        kb.update(right(u, click = false))
        kb.update(right(u, click = true))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && it.pressed }) // still just the one
    }

    @Test
    fun `special keys fire space backspace enter and close dismisses`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink)
        kb.activate()
        val enter = ScKeyboardLayout.RIGHT.indexOf(KbKey.Enter)
        kb.update(right(enter, click = false)); kb.update(right(enter, click = true))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_ENTER))

        val space = ScKeyboardLayout.RIGHT.indexOf(KbKey.Space)
        kb.update(right(space, click = false)); kb.update(right(space, click = true))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_SPACE))

        val bksp = ScKeyboardLayout.RIGHT.indexOf(KbKey.Backspace)
        kb.update(right(bksp, click = false)); kb.update(right(bksp, click = true))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_BKSP))

        assertTrue(kb.active)
        val close = ScKeyboardLayout.RIGHT.indexOf(KbKey.Close)
        kb.update(right(close, click = false)); kb.update(right(close, click = true))
        assertFalse("Close should dismiss the keyboard", kb.active)
    }

    @Test
    fun `holding a key auto-repeats after the initial delay`() {
        val sink = RecordingSink()
        var now = 1000L
        val kb = ScKeyboard(sink, clock = { now })
        kb.activate()
        val bksp = ScKeyboardLayout.RIGHT.indexOf(KbKey.Backspace)
        kb.update(right(bksp, click = false))          // hover
        kb.update(right(bksp, click = true))           // rising -> fire #1 (nextFire = 1350)
        assertEquals(1, sink.keyPresses(XKeycode.KEY_BKSP))
        now = 1300; kb.update(right(bksp, click = true)) // before delay -> no repeat
        assertEquals(1, sink.keyPresses(XKeycode.KEY_BKSP))
        now = 1400; kb.update(right(bksp, click = true)) // past delay -> fire #2 (nextFire = 1490)
        now = 1500; kb.update(right(bksp, click = true)) // past interval -> fire #3
        assertEquals(3, sink.keyPresses(XKeycode.KEY_BKSP))
        now = 1600; kb.update(right(bksp, click = false)) // release -> stop
        now = 1800; kb.update(right(bksp, click = false))
        assertEquals(3, sink.keyPresses(XKeycode.KEY_BKSP))
    }

    @Test
    fun `holding shift does not repeat-toggle`() {
        val sink = RecordingSink()
        var now = 1000L
        val kb = ScKeyboard(sink, clock = { now })
        kb.activate()
        val shift = ScKeyboardLayout.LEFT.indexOf(KbKey.Shift)
        kb.update(left(shift, click = false))
        kb.update(left(shift, click = true))           // toggle shift ON once
        now = 1500; kb.update(left(shift, click = true)) // held well past the repeat delay
        now = 2000; kb.update(left(shift, click = true))
        // Shift is one-shot (not repeatable): still armed for exactly one capitalized letter.
        val y = rightIndexOf("y")
        kb.update(right(y, click = false)); kb.update(right(y, click = true))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && it.pressed })
    }

    @Test
    fun `symbol page types a shifted symbol then Abc returns to letters`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink)
        kb.activate()
        // Toggle to the symbol page via the Sym key (right half).
        val sym = ScKeyboardLayout.RIGHT.indexOf(KbKey.Sym)
        kb.update(right(sym, click = false)); kb.update(right(sym, click = true))
        // "!" is LEFT_SYM[0] = Shift+KEY_1 (forceShift). Clicking it holds Shift around the keycode.
        assertEquals("!", (ScKeyboardLayout.LEFT_SYM[0] as KbKey.Chr).label)
        kb.update(left(0, click = false)); kb.update(left(0, click = true))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_1))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && !it.pressed })
        // Abc key returns to the letter page: same left-half cell 0 is now "1", no forced shift.
        val abc = ScKeyboardLayout.RIGHT_SYM.indexOf(KbKey.Abc)
        kb.update(right(abc, click = false)); kb.update(right(abc, click = true))
        kb.update(left(0, click = false)); kb.update(left(0, click = true))
        assertEquals(2, sink.keyPresses(XKeycode.KEY_1)) // "!" and "1" both fire KEY_1
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SHIFT_L && it.pressed }) // but letter '1' held no shift
    }

    @Test
    fun `trigger pull also commits`() {
        val sink = RecordingSink()
        val kb = ScKeyboard(sink)
        kb.activate()
        val p = rightIndexOf("p")
        val (x, yy) = coordsFor(p)
        kb.update(TritonState().apply { buttons = TritonProtocol.BTN_RPAD_TOUCH; rightPadX = x; rightPadY = yy })
        kb.update(TritonState().apply { buttons = TritonProtocol.BTN_RPAD_TOUCH or TritonProtocol.BTN_RTRIG_CLICK; rightPadX = x; rightPadY = yy })
        assertEquals(1, sink.keyPresses(XKeycode.KEY_P))
    }
}
