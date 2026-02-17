package app.gamenative.externaldisplay

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.winlator.xserver.XServer
import timber.log.Timber

/**
 * Invisible view that receives IME (soft keyboard) input from Android system keyboard
 * and forwards it to XServer as keyboard events for the game.
 * 
 * This is needed when the system keyboard is pinned to the external display
 */
class IMEInputReceiver(
    context: Context,
    private val xServer: XServer,
) : FrameLayout(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        
        // Make this view visible to the IME system
        setFocusable(View.FOCUSABLE)
        setFocusableInTouchMode(true)
        
        post {
            if (requestFocus()) {
                Timber.d("IMEInputReceiver: Successfully got focus")
            } else {
                Timber.w("IMEInputReceiver: Failed to get focus")
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean {
        Timber.d("IMEInputReceiver: onCheckIsTextEditor called - returning true")
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        Timber.d("IMEInputReceiver: onCreateInputConnection called!")
        // Disable autocomplete/suggestions so each key commits immediately
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or 
                             EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                             EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or 
                              EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                              EditorInfo.IME_ACTION_NONE
        
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                Timber.d("IMEInputReceiver: commitText called with: '$text'")
                text?.forEach { char ->
                    sendCharacterToGame(char)
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                Timber.d("IMEInputReceiver: deleteSurroundingText called")
                if (beforeLength > 0) {
                    xServer.injectKeyPress(com.winlator.xserver.XKeycode.KEY_BKSP, 0)
                    xServer.injectKeyRelease(com.winlator.xserver.XKeycode.KEY_BKSP)
                    Timber.v("IMEInputReceiver: Sent backspace")
                }
                return true
            }
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        Timber.d("IMEInputReceiver: onWindowFocusChanged: $hasWindowFocus")
        if (hasWindowFocus) {
            post { requestFocus() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.d("IMEInputReceiver: onAttachedToWindow - requesting focus")
        post { requestFocus() }
    }

    private fun sendCharacterToGame(char: Char) {
        val keyCode = charToKeyCode(char)
        if (keyCode != null) {
            val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            xServer.keyboard.onKeyEvent(event)
            
            val upEvent = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            xServer.keyboard.onKeyEvent(upEvent)
            
            Timber.v("IMEInputReceiver: Sent char '$char' as keyCode $keyCode")
        } else {
            Timber.w("IMEInputReceiver: Could not map character '$char' to keyCode")
        }
    }

    private fun charToKeyCode(char: Char): Int? = when (char) {
        'a', 'A' -> KeyEvent.KEYCODE_A
        'b', 'B' -> KeyEvent.KEYCODE_B
        'c', 'C' -> KeyEvent.KEYCODE_C
        'd', 'D' -> KeyEvent.KEYCODE_D
        'e', 'E' -> KeyEvent.KEYCODE_E
        'f', 'F' -> KeyEvent.KEYCODE_F
        'g', 'G' -> KeyEvent.KEYCODE_G
        'h', 'H' -> KeyEvent.KEYCODE_H
        'i', 'I' -> KeyEvent.KEYCODE_I
        'j', 'J' -> KeyEvent.KEYCODE_J
        'k', 'K' -> KeyEvent.KEYCODE_K
        'l', 'L' -> KeyEvent.KEYCODE_L
        'm', 'M' -> KeyEvent.KEYCODE_M
        'n', 'N' -> KeyEvent.KEYCODE_N
        'o', 'O' -> KeyEvent.KEYCODE_O
        'p', 'P' -> KeyEvent.KEYCODE_P
        'q', 'Q' -> KeyEvent.KEYCODE_Q
        'r', 'R' -> KeyEvent.KEYCODE_R
        's', 'S' -> KeyEvent.KEYCODE_S
        't', 'T' -> KeyEvent.KEYCODE_T
        'u', 'U' -> KeyEvent.KEYCODE_U
        'v', 'V' -> KeyEvent.KEYCODE_V
        'w', 'W' -> KeyEvent.KEYCODE_W
        'x', 'X' -> KeyEvent.KEYCODE_X
        'y', 'Y' -> KeyEvent.KEYCODE_Y
        'z', 'Z' -> KeyEvent.KEYCODE_Z
        '0' -> KeyEvent.KEYCODE_0
        '1' -> KeyEvent.KEYCODE_1
        '2' -> KeyEvent.KEYCODE_2
        '3' -> KeyEvent.KEYCODE_3
        '4' -> KeyEvent.KEYCODE_4
        '5' -> KeyEvent.KEYCODE_5
        '6' -> KeyEvent.KEYCODE_6
        '7' -> KeyEvent.KEYCODE_7
        '8' -> KeyEvent.KEYCODE_8
        '9' -> KeyEvent.KEYCODE_9
        ' ' -> KeyEvent.KEYCODE_SPACE
        '\n' -> KeyEvent.KEYCODE_ENTER
        '-' -> KeyEvent.KEYCODE_MINUS
        '=' -> KeyEvent.KEYCODE_EQUALS
        '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
        ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
        '\\' -> KeyEvent.KEYCODE_BACKSLASH
        ';' -> KeyEvent.KEYCODE_SEMICOLON
        '\'' -> KeyEvent.KEYCODE_APOSTROPHE
        ',' -> KeyEvent.KEYCODE_COMMA
        '.' -> KeyEvent.KEYCODE_PERIOD
        '/' -> KeyEvent.KEYCODE_SLASH
        '`' -> KeyEvent.KEYCODE_GRAVE
        else -> null
    }

    fun showKeyboard() {
        post {
            requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            Timber.d("IMEInputReceiver: Requested to show keyboard")
        }
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }
}
