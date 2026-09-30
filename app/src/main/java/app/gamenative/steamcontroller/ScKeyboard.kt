package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode

/**
 * Steam-Controller-style split on-screen keyboard (see docs/SC-KEYBOARD.md). The keyboard is two grids — the
 * **left trackpad** drives a cursor over the left half, the **right trackpad** over the right half — and a key is
 * typed by **clicking that pad or pulling that side's trigger** while the cursor is over it. Output goes straight
 * to the game via [ScOutputSink.key] (`XServer.injectKeyPress`), so no Android IME is involved.
 *
 * Pure logic (unit-testable); rendering is [ScKeyboardOverlayView] via the [ScKeyboardOverlay] seam. v1 commits on
 * click/trigger (not thumb-lift) and supports a sticky Shift; a symbol layer / release-to-type are future work.
 */

/** One key on the on-screen keyboard. */
sealed class KbKey(val label: String) {
    /** A character key that injects [code]. Shift is held when the keyboard's sticky shift is on, OR when
     *  [forceShift] is set (used by symbol-page keys that are always the shifted glyph, e.g. `!` = Shift+1). */
    class Chr(val code: XKeycode, label: String, val forceShift: Boolean = false) : KbKey(label)
    object Shift : KbKey("⇧ Shift") // sticky shift (capital for the next letter)
    object Backspace : KbKey("⌫")  // ⌫
    object Space : KbKey("space")
    object Enter : KbKey("⏎ Enter")
    object Close : KbKey("✕")      // ✕ close the keyboard
    object Sym : KbKey("?123")     // switch to the symbol page
    object Abc : KbKey("ABC")      // switch back to the letter page
    object Empty : KbKey("")            // unused cell
}

/** The fixed split-QWERTY layout: two [COLS]×[ROWS] grids, row 0 = TOP, row-major. */
object ScKeyboardLayout {
    const val COLS = 5
    const val ROWS = 5

    private fun k(ch: Char): KbKey = KbKey.Chr(XKeycode.valueOf("KEY_" + ch.uppercaseChar()), ch.toString())
    /** A symbol key: injects [code], holding Shift for the shifted glyph (e.g. `!` = Shift+KEY_1). */
    private fun s(code: XKeycode, label: String, shift: Boolean = false) = KbKey.Chr(code, label, forceShift = shift)

    val LEFT: List<KbKey> = listOf(
        k('1'), k('2'), k('3'), k('4'), k('5'),
        k('q'), k('w'), k('e'), k('r'), k('t'),
        k('a'), k('s'), k('d'), k('f'), k('g'),
        k('z'), k('x'), k('c'), k('v'), k('b'),
        KbKey.Shift, KbKey.Chr(XKeycode.KEY_COMMA, ","), KbKey.Chr(XKeycode.KEY_PERIOD, "."), KbKey.Chr(XKeycode.KEY_MINUS, "-"), KbKey.Space,
    )

    val RIGHT: List<KbKey> = listOf(
        k('6'), k('7'), k('8'), k('9'), k('0'),
        k('y'), k('u'), k('i'), k('o'), k('p'),
        k('h'), k('j'), k('k'), k('l'), KbKey.Enter,
        k('n'), k('m'), KbKey.Backspace, KbKey.Close, KbKey.Sym,
        KbKey.Space, KbKey.Empty, KbKey.Empty, KbKey.Empty, KbKey.Empty,
    )

    // Symbol page — same 5×5 split; functional keys (Shift/Space/Enter/Backspace/Close/toggle) stay in place, the
    // character cells become symbols. Shifted glyphs (! @ # …) hold Shift over the base keycode.
    val LEFT_SYM: List<KbKey> = listOf(
        s(XKeycode.KEY_1, "!", true), s(XKeycode.KEY_2, "@", true), s(XKeycode.KEY_3, "#", true), s(XKeycode.KEY_4, "$", true), s(XKeycode.KEY_5, "%", true),
        s(XKeycode.KEY_6, "^", true), s(XKeycode.KEY_7, "&", true), s(XKeycode.KEY_8, "*", true), s(XKeycode.KEY_9, "(", true), s(XKeycode.KEY_0, ")", true),
        s(XKeycode.KEY_MINUS, "-"), s(XKeycode.KEY_MINUS, "_", true), s(XKeycode.KEY_EQUAL, "="), s(XKeycode.KEY_EQUAL, "+", true), s(XKeycode.KEY_BACKSLASH, "\\"),
        s(XKeycode.KEY_BACKSLASH, "|", true), s(XKeycode.KEY_SEMICOLON, ";"), s(XKeycode.KEY_SEMICOLON, ":", true), s(XKeycode.KEY_APOSTROPHE, "'"), s(XKeycode.KEY_APOSTROPHE, "\"", true),
        KbKey.Shift, s(XKeycode.KEY_GRAVE, "`"), s(XKeycode.KEY_GRAVE, "~", true), s(XKeycode.KEY_SLASH, "/"), KbKey.Space,
    )

    val RIGHT_SYM: List<KbKey> = listOf(
        s(XKeycode.KEY_BRACKET_LEFT, "["), s(XKeycode.KEY_BRACKET_RIGHT, "]"), s(XKeycode.KEY_BRACKET_LEFT, "{", true), s(XKeycode.KEY_BRACKET_RIGHT, "}", true), s(XKeycode.KEY_SLASH, "?", true),
        s(XKeycode.KEY_COMMA, "<", true), s(XKeycode.KEY_PERIOD, ">", true), s(XKeycode.KEY_COMMA, ","), s(XKeycode.KEY_PERIOD, "."), KbKey.Empty,
        KbKey.Empty, KbKey.Empty, KbKey.Empty, KbKey.Empty, KbKey.Enter,
        KbKey.Empty, KbKey.Empty, KbKey.Backspace, KbKey.Close, KbKey.Abc,
        KbKey.Space, KbKey.Empty, KbKey.Empty, KbKey.Empty, KbKey.Empty,
    )

    fun leftFor(symbols: Boolean): List<KbKey> = if (symbols) LEFT_SYM else LEFT
    fun rightFor(symbols: Boolean): List<KbKey> = if (symbols) RIGHT_SYM else RIGHT

    /** Pad position (raw ±32768, +Y up) → cell index in a [COLS]×[ROWS] grid (row 0 = top), or -1 if out of range. */
    fun cellAt(x: Int, y: Int): Int {
        val nx = (x.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val ny = (y.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
        val col = (nx * COLS).toInt().coerceIn(0, COLS - 1)
        val rowTop = (ROWS - 1) - (ny * ROWS).toInt().coerceIn(0, ROWS - 1)
        return rowTop * COLS + col
    }
}

/** Render seam for the keyboard HUD; the layout is static ([ScKeyboardLayout]), the spec carries live cursor/shift. */
interface ScKeyboardOverlay {
    fun show(spec: ScKeyboardSpec)
    fun hide()
}

/**
 * Live keyboard state for the overlay: highlighted cell on each half (-1 = none) + sticky-shift state, plus the
 * continuous finger position within each half ([leftX]/[leftY], [rightX]/[rightY]; 0..1, x left→right, y top→
 * bottom; -1 = that pad isn't touched) so the HUD can draw a cursor dot, not just a cell highlight.
 */
data class ScKeyboardSpec(
    val leftCursor: Int, val rightCursor: Int, val shift: Boolean,
    val leftX: Float = -1f, val leftY: Float = -1f,
    val rightX: Float = -1f, val rightY: Float = -1f,
    val symbols: Boolean = false,
)

object NoOpScKeyboardOverlay : ScKeyboardOverlay {
    override fun show(spec: ScKeyboardSpec) {}
    override fun hide() {}
}

/**
 * Drives the split keyboard from [TritonState]s while [active]. Typing fires keys through [sink]; the visual is
 * pushed to [overlay]. Toggled by [ProfileInterpreter] on a [ScOutput.ShowKeyboard] binding.
 */
class ScKeyboard(
    private val sink: ScOutputSink,
    private val overlay: ScKeyboardOverlay = NoOpScKeyboardOverlay,
    /** Trackpad haptics: detent ticks as the cursor crosses cells + a click on type. Keyboard mode suppresses
     *  the interpreter's normal pad-feel path, so the keyboard fires its own. Null = no haptics. */
    private val haptics: TritonHaptics? = null,
    /** Time source (ms) for key-repeat timing; override in tests with a virtual clock. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Touchpad smoothing 0–100 (shared knob); low-passes the cursor position so the dot/cell don't jitter. */
    smoothing: Int = 0,
) {
    // @Volatile so live dial-in (ProfileInterpreter.setPadTuning) can retune the cursor low-pass mid-game.
    @Volatile private var smoothing: Int = smoothing
    fun setSmoothing(value: Int) { smoothing = value }

    var active = false
        private set
    private var shift = false
    private var symbols = false // symbol page vs the letter page (toggled by Sym/Abc)
    private var leftCursor = -1
    private var rightCursor = -1
    private var leftX = -1f
    private var leftY = -1f
    private var rightX = -1f
    private var rightY = -1f
    private var prevButtons = 0
    // EMA-smoothed raw pad position per half (NaN = not currently touched / unseeded).
    private var smLeftX = Float.NaN
    private var smLeftY = Float.NaN
    private var smRightX = Float.NaN
    private var smRightY = Float.NaN
    private val clickGain = HapticSettings().clickGain
    private val tickGain = HapticSettings().tickGain

    // Hold-to-repeat: holding a pad-click/trigger over a key types it once, then (after [REPEAT_DELAY_MS]) repeats
    // every [REPEAT_INTERVAL_MS] while held on the same cell. Per-side state so each thumb repeats independently.
    private val lRepeat = SideRepeat()
    private val rRepeat = SideRepeat()

    fun activate() {
        active = true; shift = false; symbols = false; leftCursor = -1; rightCursor = -1
        leftX = -1f; leftY = -1f; rightX = -1f; rightY = -1f; prevButtons = 0
        smLeftX = Float.NaN; smLeftY = Float.NaN; smRightX = Float.NaN; smRightY = Float.NaN
        lRepeat.reset(); rRepeat.reset()
        push()
    }

    fun deactivate() {
        active = false
        runCatching { overlay.hide() }
    }

    /** Process one report while the keyboard is up. */
    fun update(s: TritonState) {
        if (!active) return
        val now = clock()
        val a = ScTuningStore.emaAlpha(smoothing)
        // LEFT half: smooth the raw pad position (Touchpad smoothing), then track cursor (+detent tick on cell
        // change) and type on pad-click/trigger (hold to repeat).
        val lTouch = s.has(TritonProtocol.BTN_LPAD_TOUCH)
        if (lTouch) {
            smLeftX = if (smLeftX.isNaN()) s.leftPadX.toFloat() else smLeftX * (1f - a) + s.leftPadX * a
            smLeftY = if (smLeftY.isNaN()) s.leftPadY.toFloat() else smLeftY * (1f - a) + s.leftPadY * a
        } else { smLeftX = Float.NaN; smLeftY = Float.NaN }
        val lx = smLeftX.toInt(); val ly = smLeftY.toInt()
        val newLeft = if (lTouch) ScKeyboardLayout.cellAt(lx, ly) else -1
        if (lTouch && newLeft >= 0 && newLeft != leftCursor) haptics?.tick(TritonHaptics.SIDE_LEFT_PAD, tickGain)
        leftCursor = newLeft
        leftX = if (lTouch) normX(lx) else -1f
        leftY = if (lTouch) normY(ly) else -1f
        fireSide(s, leftCursor, ScKeyboardLayout.leftFor(symbols), TritonHaptics.SIDE_LEFT_PAD, now, lRepeat,
            TritonProtocol.BTN_LPAD_CLICK, TritonProtocol.BTN_LTRIG_CLICK)
        // RIGHT half
        val rTouch = s.has(TritonProtocol.BTN_RPAD_TOUCH)
        if (rTouch) {
            smRightX = if (smRightX.isNaN()) s.rightPadX.toFloat() else smRightX * (1f - a) + s.rightPadX * a
            smRightY = if (smRightY.isNaN()) s.rightPadY.toFloat() else smRightY * (1f - a) + s.rightPadY * a
        } else { smRightX = Float.NaN; smRightY = Float.NaN }
        val rx = smRightX.toInt(); val ry = smRightY.toInt()
        val newRight = if (rTouch) ScKeyboardLayout.cellAt(rx, ry) else -1
        if (rTouch && newRight >= 0 && newRight != rightCursor) haptics?.tick(TritonHaptics.SIDE_RIGHT_PAD, tickGain)
        rightCursor = newRight
        rightX = if (rTouch) normX(rx) else -1f
        rightY = if (rTouch) normY(ry) else -1f
        if (active) fireSide(s, rightCursor, ScKeyboardLayout.rightFor(symbols), TritonHaptics.SIDE_RIGHT_PAD, now, rRepeat,
            TritonProtocol.BTN_RPAD_CLICK, TritonProtocol.BTN_RTRIG_CLICK)
        prevButtons = s.buttons
        if (active) push() else runCatching { overlay.hide() }
    }

    /**
     * Type from one half: fire on the rising edge of any [commitBits] (pad-click / trigger) over [cursor], then
     * auto-repeat while the button stays held on the same cell (initial [REPEAT_DELAY_MS], then [REPEAT_INTERVAL_MS]).
     * Sliding to a different cell while held suppresses repeat until release, so dragging doesn't machine-gun keys.
     */
    private fun fireSide(s: TritonState, cursor: Int, keys: List<KbKey>, side: Int, now: Long, rep: SideRepeat, vararg commitBits: Int) {
        val down = commitBits.any { (s.buttons and it) != 0 }
        val rising = commitBits.any { (s.buttons and it) != 0 && (prevButtons and it) == 0 }
        val key = keys.getOrNull(cursor)
        when {
            rising && cursor >= 0 -> {
                haptics?.click(side, clickGain); fire(key)
                rep.cell = cursor; rep.nextFire = now + REPEAT_DELAY_MS
            }
            down && cursor >= 0 && cursor == rep.cell && repeatable(key) && now >= rep.nextFire -> {
                haptics?.tick(side, tickGain); fire(key)
                rep.nextFire = now + REPEAT_INTERVAL_MS
            }
            !down -> rep.reset()
            cursor != rep.cell -> rep.cell = -2 // moved off the pressed cell while held → no repeat until release
        }
    }

    /** Which keys auto-repeat when held: characters + backspace/space/enter; not Shift/Close/Empty (one-shot). */
    private fun repeatable(key: KbKey?): Boolean = when (key) {
        is KbKey.Chr, KbKey.Backspace, KbKey.Space, KbKey.Enter -> true
        else -> false
    }

    // Pad position (raw ±32768, +Y up) → normalized half coords: x left→right, y top→bottom (screen order).
    private fun normX(x: Int) = (x.toFloat() / 65536f + 0.5f).coerceIn(0f, 0.999f)
    private fun normY(y: Int) = (1f - (y.toFloat() / 65536f + 0.5f)).coerceIn(0f, 0.999f)

    private fun fire(key: KbKey?) {
        when (key) {
            is KbKey.Chr -> {
                if (shift || key.forceShift) {
                    sink.key(XKeycode.KEY_SHIFT_L, true)
                    sink.key(key.code, true); sink.key(key.code, false)
                    sink.key(XKeycode.KEY_SHIFT_L, false)
                    if (shift) shift = false // sticky one-shot (forceShift keys don't consume it)
                } else {
                    sink.key(key.code, true); sink.key(key.code, false)
                }
            }
            KbKey.Shift -> shift = !shift
            KbKey.Sym, KbKey.Abc -> symbols = !symbols
            KbKey.Space -> pulse(XKeycode.KEY_SPACE)
            KbKey.Backspace -> pulse(XKeycode.KEY_BKSP)
            KbKey.Enter -> pulse(XKeycode.KEY_ENTER)
            KbKey.Close -> deactivate()
            KbKey.Empty, null -> {}
        }
    }

    private fun pulse(code: XKeycode) { sink.key(code, true); sink.key(code, false) }

    private fun push() {
        runCatching { overlay.show(ScKeyboardSpec(leftCursor, rightCursor, shift, leftX, leftY, rightX, rightY, symbols)) }
    }

    /** Per-side key-repeat state: [cell] = the cell the held button pressed (-1 idle, -2 = slid off, suppressed). */
    private class SideRepeat {
        var cell = -1
        var nextFire = 0L
        fun reset() { cell = -1; nextFire = 0L }
    }

    private companion object {
        const val REPEAT_DELAY_MS = 350L     // hold this long before the first repeat
        const val REPEAT_INTERVAL_MS = 90L   // then repeat at this cadence
    }
}
