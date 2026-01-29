package app.gamenative.externaldisplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import app.gamenative.R
import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import kotlin.math.roundToInt

class ExternalOnScreenKeyboardView(
    context: Context,
    private val xServer: XServer,
) : LinearLayout(context) {

    private enum class ShiftState { OFF, ON, CAPS }

    private data class KeySpec(
        val normalLabel: String,
        val shiftedLabel: String? = null,
        val keycode: XKeycode? = null,
        val weight: Float = 1f,
        val isLetter: Boolean = false,
        val action: Action = Action.INPUT,
    )

    private enum class Action { INPUT, SHIFT, BACKSPACE, ENTER, SPACE, TAB, ESC, ARROW_LEFT, ARROW_DOWN, ARROW_RIGHT, ARROW_UP }

    private data class KeyButton(
        val spec: KeySpec,
        val button: Button,
    )

    private val keyButtons = mutableListOf<KeyButton>()
    private val downKeys = mutableSetOf<XKeycode>()
    private var shiftState: ShiftState = ShiftState.OFF
    private val keyboardBackgroundColor: Int = ContextCompat.getColor(context, R.color.external_display_keyboard_background)
    private val keyBackgroundColor: Int = ContextCompat.getColor(context, R.color.external_display_key_background)
    private val keyHighlightColor: Int = ContextCompat.getColor(context, R.color.external_display_key_highlight_background)
    private val keyHighlightStrongColor: Int =
        ContextCompat.getColor(context, R.color.external_display_key_highlight_strong_background)

    init {
        orientation = VERTICAL
        setMotionEventSplittingEnabled(true)
        val padding = dp(8)
        setPadding(padding, padding, padding, padding)
        setBackgroundColor(keyboardBackgroundColor)
        buildLayout()
        refreshLabels()
    }

    private fun buildLayout() {
        addRow(
            listOf(
                KeySpec("Esc", keycode = XKeycode.KEY_ESC, weight = 1.25f, action = Action.ESC),
                KeySpec("1", "!", XKeycode.KEY_1),
                KeySpec("2", "@", XKeycode.KEY_2),
                KeySpec("3", "#", XKeycode.KEY_3),
                KeySpec("4", "$", XKeycode.KEY_4),
                KeySpec("5", "%", XKeycode.KEY_5),
                KeySpec("6", "^", XKeycode.KEY_6),
                KeySpec("7", "&", XKeycode.KEY_7),
                KeySpec("8", "*", XKeycode.KEY_8),
                KeySpec("9", "(", XKeycode.KEY_9),
                KeySpec("0", ")", XKeycode.KEY_0),
                KeySpec("-", "_", XKeycode.KEY_MINUS),
                KeySpec("=", "+", XKeycode.KEY_EQUAL),
                KeySpec("⌫", keycode = XKeycode.KEY_BKSP, weight = 1.75f, action = Action.BACKSPACE),
            ),
        )

        addRow(
            listOf(
                KeySpec("Tab", keycode = XKeycode.KEY_TAB, weight = 1.5f, action = Action.TAB),
                KeySpec("q", "Q", XKeycode.KEY_Q, isLetter = true),
                KeySpec("w", "W", XKeycode.KEY_W, isLetter = true),
                KeySpec("e", "E", XKeycode.KEY_E, isLetter = true),
                KeySpec("r", "R", XKeycode.KEY_R, isLetter = true),
                KeySpec("t", "T", XKeycode.KEY_T, isLetter = true),
                KeySpec("y", "Y", XKeycode.KEY_Y, isLetter = true),
                KeySpec("u", "U", XKeycode.KEY_U, isLetter = true),
                KeySpec("i", "I", XKeycode.KEY_I, isLetter = true),
                KeySpec("o", "O", XKeycode.KEY_O, isLetter = true),
                KeySpec("p", "P", XKeycode.KEY_P, isLetter = true),
                KeySpec("[", "{", XKeycode.KEY_BRACKET_LEFT),
                KeySpec("]", "}", XKeycode.KEY_BRACKET_RIGHT),
                KeySpec("\\", "|", XKeycode.KEY_BACKSLASH, weight = 1.25f),
            ),
        )

        addRow(
            listOf(
                KeySpec("Shift", weight = 1.75f, action = Action.SHIFT),
                KeySpec("a", "A", XKeycode.KEY_A, isLetter = true),
                KeySpec("s", "S", XKeycode.KEY_S, isLetter = true),
                KeySpec("d", "D", XKeycode.KEY_D, isLetter = true),
                KeySpec("f", "F", XKeycode.KEY_F, isLetter = true),
                KeySpec("g", "G", XKeycode.KEY_G, isLetter = true),
                KeySpec("h", "H", XKeycode.KEY_H, isLetter = true),
                KeySpec("j", "J", XKeycode.KEY_J, isLetter = true),
                KeySpec("k", "K", XKeycode.KEY_K, isLetter = true),
                KeySpec("l", "L", XKeycode.KEY_L, isLetter = true),
                KeySpec(";", ":", XKeycode.KEY_SEMICOLON),
                KeySpec("'", "\"", XKeycode.KEY_APOSTROPHE),
                KeySpec("Enter", keycode = XKeycode.KEY_ENTER, weight = 2.0f, action = Action.ENTER),
            ),
        )

        addRow(
            listOf(
                KeySpec("`", "~", XKeycode.KEY_GRAVE, weight = 1.25f),
                KeySpec("z", "Z", XKeycode.KEY_Z, isLetter = true),
                KeySpec("x", "X", XKeycode.KEY_X, isLetter = true),
                KeySpec("c", "C", XKeycode.KEY_C, isLetter = true),
                KeySpec("v", "V", XKeycode.KEY_V, isLetter = true),
                KeySpec("b", "B", XKeycode.KEY_B, isLetter = true),
                KeySpec("n", "N", XKeycode.KEY_N, isLetter = true),
                KeySpec("m", "M", XKeycode.KEY_M, isLetter = true),
                KeySpec(",", "<", XKeycode.KEY_COMMA),
                KeySpec(".", ">", XKeycode.KEY_PERIOD),
                KeySpec("/", "?", XKeycode.KEY_SLASH),
                KeySpec("↑", keycode = XKeycode.KEY_UP, weight = 1.25f, action = Action.ARROW_UP),
            ),
        )

        addRow(
            listOf(
                KeySpec("Space", keycode = XKeycode.KEY_SPACE, weight = 6f, action = Action.SPACE),
                KeySpec("←", keycode = XKeycode.KEY_LEFT, weight = 1.25f, action = Action.ARROW_LEFT),
                KeySpec("↓", keycode = XKeycode.KEY_DOWN, weight = 1.25f, action = Action.ARROW_DOWN),
                KeySpec("→", keycode = XKeycode.KEY_RIGHT, weight = 1.25f, action = Action.ARROW_RIGHT),
            ),
        )
    }

    private fun addRow(keys: List<KeySpec>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val margin = dp(3)
        val height = dp(48)

        keys.forEach { spec ->
            val button = Button(context).apply {
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(16f)
                typeface = Typeface.DEFAULT_BOLD
                text = spec.normalLabel
                background = createKeyBackground(normal = true)
                setPadding(0, 0, 0, 0)
                layoutParams = LayoutParams(0, height, spec.weight).apply {
                    setMargins(margin, margin, margin, margin)
                }
                setOnTouchListener { _, event ->
                    handleKeyTouch(spec, event)
                    false
                }
            }
            keyButtons += KeyButton(spec, button)
            row.addView(button)
        }

        addView(row)
    }

    private fun handleKeyTouch(spec: KeySpec, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onKeyDown(spec)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onKeyUp(spec, cancel = event.actionMasked == MotionEvent.ACTION_CANCEL)
        }
    }

    private fun onKeyDown(spec: KeySpec) {
        when (spec.action) {
            Action.SHIFT -> Unit
            Action.BACKSPACE -> pressKey(XKeycode.KEY_BKSP)
            Action.ENTER -> pressKey(XKeycode.KEY_ENTER)
            Action.SPACE -> pressKey(XKeycode.KEY_SPACE)
            Action.TAB -> pressKey(XKeycode.KEY_TAB)
            Action.ESC -> pressKey(XKeycode.KEY_ESC)
            Action.ARROW_LEFT -> pressKey(XKeycode.KEY_LEFT)
            Action.ARROW_DOWN -> pressKey(XKeycode.KEY_DOWN)
            Action.ARROW_RIGHT -> pressKey(XKeycode.KEY_RIGHT)
            Action.ARROW_UP -> pressKey(XKeycode.KEY_UP)
            Action.INPUT -> {
                val keycode = spec.keycode ?: return
                val useShift = when (shiftState) {
                    ShiftState.OFF -> false
                    ShiftState.ON -> true
                    ShiftState.CAPS -> spec.isLetter
                }
                pressKey(keycode, withShift = useShift)
                if (shiftState == ShiftState.ON) {
                    shiftState = ShiftState.OFF
                    refreshLabels()
                }
            }
        }
    }

    private fun onKeyUp(spec: KeySpec, cancel: Boolean) {
        when (spec.action) {
            Action.SHIFT -> if (!cancel) cycleShift()
            Action.BACKSPACE -> releaseKey(XKeycode.KEY_BKSP)
            Action.ENTER -> releaseKey(XKeycode.KEY_ENTER)
            Action.SPACE -> releaseKey(XKeycode.KEY_SPACE)
            Action.TAB -> releaseKey(XKeycode.KEY_TAB)
            Action.ESC -> releaseKey(XKeycode.KEY_ESC)
            Action.ARROW_LEFT -> releaseKey(XKeycode.KEY_LEFT)
            Action.ARROW_DOWN -> releaseKey(XKeycode.KEY_DOWN)
            Action.ARROW_RIGHT -> releaseKey(XKeycode.KEY_RIGHT)
            Action.ARROW_UP -> releaseKey(XKeycode.KEY_UP)
            Action.INPUT -> spec.keycode?.let { releaseKey(it) }
        }
    }

    private fun cycleShift() {
        shiftState = when (shiftState) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> ShiftState.CAPS
            ShiftState.CAPS -> ShiftState.OFF
        }
        refreshLabels()
    }

    private fun refreshLabels() {
        val shiftForLetters = shiftState != ShiftState.OFF
        keyButtons.forEach { (spec, button) ->
            if (spec.action == Action.SHIFT) {
                val label = when (shiftState) {
                    ShiftState.OFF -> "Shift"
                    ShiftState.ON -> "Shift"
                    ShiftState.CAPS -> "Caps"
                }
                button.text = label
                button.background = when (shiftState) {
                    ShiftState.OFF -> createKeyBackground(normal = true)
                    ShiftState.ON -> createKeyBackground(highlight = true)
                    ShiftState.CAPS -> createKeyBackground(highlight = true, strong = true)
                }
                return@forEach
            }

            val showShifted = when {
                spec.isLetter -> shiftForLetters
                shiftState == ShiftState.ON -> true
                else -> false
            }

            button.text = if (showShifted && spec.shiftedLabel != null) spec.shiftedLabel else spec.normalLabel
            button.background = createKeyBackground(normal = true)
        }
    }

    private fun pressKey(key: XKeycode, withShift: Boolean = false) {
        if (!downKeys.add(key)) return
        val shiftWasDown = xServer.keyboard.modifiersMask.isSet(1)
        if (withShift && !shiftWasDown) xServer.injectKeyPress(XKeycode.KEY_SHIFT_L)
        xServer.injectKeyPress(key)
        if (withShift && !shiftWasDown) xServer.injectKeyRelease(XKeycode.KEY_SHIFT_L)
    }

    private fun releaseKey(key: XKeycode) {
        if (!downKeys.remove(key)) return
        xServer.injectKeyRelease(key)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDetachedFromWindow() {
        downKeys.toList().forEach { key ->
            xServer.injectKeyRelease(key)
        }
        downKeys.clear()
        super.onDetachedFromWindow()
    }

    private fun createKeyBackground(
        normal: Boolean = false,
        highlight: Boolean = false,
        strong: Boolean = false,
    ): StateListDrawable {
        val radius = dp(8).toFloat()
        val baseColor = when {
            highlight && strong -> keyHighlightStrongColor
            highlight -> keyHighlightColor
            normal -> keyBackgroundColor
            else -> keyBackgroundColor
        }

        val pressedColor = blendColor(baseColor, Color.WHITE, 0.18f)

        fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor))
            addState(intArrayOf(), shape(baseColor))
        }
    }

    private fun blendColor(from: Int, to: Int, ratio: Float): Int {
        val clamped = ratio.coerceIn(0f, 1f)
        val inverse = 1f - clamped
        val a = (Color.alpha(from) * inverse + Color.alpha(to) * clamped).roundToInt()
        val r = (Color.red(from) * inverse + Color.red(to) * clamped).roundToInt()
        val g = (Color.green(from) * inverse + Color.green(to) * clamped).roundToInt()
        val b = (Color.blue(from) * inverse + Color.blue(to) * clamped).roundToInt()
        return Color.argb(a, r, g, b)
    }
}
