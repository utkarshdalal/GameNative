package app.gamenative.ui.component

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.flow.first

/**
 * Makes this element the default focus of its screen or dialog for controller and keyboard users. Never put it on a
 * text field: that would open the keyboard. It can sit on a container, in which case its first focusable child is
 * focused. A new [key] runs it again.
 */
@Composable
fun Modifier.focusOnOpen(key: Any? = Unit): Modifier {
    val requester = remember { FocusRequester() }
    FocusOnOpen(requester, key = key)
    return focusRequester(requester)
}

/**
 * Focuses [requester] when its window (screen, dialog or menu popup) first has window focus, under controller or
 * keyboard input only, so touch users see no ring. A request made before the window is focused is dropped, so this
 * waits for it, and it retries once after a frame for content that is not laid out yet. When [requester] cannot
 * take focus (not attached, disabled), [fallback] is tried.
 *
 * A new window starts in touch mode even when it was opened with a controller (A is not a navigation key, so it never
 * leaves touch mode), so the app-wide [LastInput] decides: after controller input the window is moved out of touch
 * mode first. A window opened by touch gets the request on the first controller or keyboard press, unless a text
 * field or an embedded view holds focus by then.
 */
@Composable
fun FocusOnOpen(requester: FocusRequester, fallback: FocusRequester? = null, key: Any? = Unit) {
    val windowInfo = LocalWindowInfo.current
    val inputModeManager = LocalInputModeManager.current
    val view = LocalView.current
    LaunchedEffect(key) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (inputModeManager.inputMode != InputMode.Keyboard && LastInput.isController) {
            // leaves touch mode for this window (View.requestFocusFromTouch)
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
        if (inputModeManager.inputMode == InputMode.Keyboard || LastInput.isController) {
            if (requester.requestFocusSafely() || fallback?.requestFocusSafely() == true) return@LaunchedEffect
            withFrameNanos { }
            if (requester.requestFocusSafely() || fallback?.requestFocusSafely() == true) return@LaunchedEffect
            if (inputModeManager.inputMode == InputMode.Keyboard) return@LaunchedEffect
        }
        // in touch mode only text fields can hold focus, so nothing else can have been focused before this
        snapshotFlow { inputModeManager.inputMode }.first { it == InputMode.Keyboard }
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (view.windowHasOtherFocus()) return@LaunchedEffect
        if (requester.requestFocusSafely() || fallback?.requestFocusSafely() == true) return@LaunchedEffect
        withFrameNanos { }
        if (!requester.requestFocusSafely()) fallback?.requestFocusSafely()
    }
}

/**
 * The kind of the user's last input, app-wide. Set from the activity's key and touch dispatch; a dialog or popup
 * window does not route through the activity, so inside one it stays what it was when the window opened.
 */
object LastInput {
    @Volatile
    var isController: Boolean = false
        private set

    fun onKey(event: KeyEvent) {
        // ime keys arrive from the virtual keyboard; system keys (volume, nav bar back) say nothing about the user
        if (event.isSystem || event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return
        if (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_DPAD) ||
            event.isFromSource(InputDevice.SOURCE_KEYBOARD)
        ) {
            isController = true
        }
    }

    fun onMotion(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            isController = true
            return
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS) isController = false
    }
}

/** A text field, or an embedded view (game input, search field), holds this window's focus. */
private fun View.windowHasOtherFocus(): Boolean {
    val focused = rootView.findFocus()
    // the compose view reports itself as a text editor while one of its text fields is focused
    if (focused != null && (focused !== this || focused.onCheckIsTextEditor())) return true
    return ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

fun FocusRequester.requestFocusSafely(): Boolean =
    try {
        requestFocus()
    } catch (_: IllegalStateException) {
        false
    }

/** For imperative requests in effects: tries now, then once more after a frame. */
suspend fun FocusRequester.requestFocusAfterLayout() {
    if (requestFocusSafely()) return
    withFrameNanos { }
    requestFocusSafely()
}
