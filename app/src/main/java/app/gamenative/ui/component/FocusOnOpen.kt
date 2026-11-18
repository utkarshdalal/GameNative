package app.gamenative.ui.component

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
 * take focus (not attached, disabled), [fallback] is tried. A window opened by touch gets the request on the first
 * controller or keyboard press, unless a text field or an embedded view holds focus by then.
 */
@Composable
fun FocusOnOpen(requester: FocusRequester, fallback: FocusRequester? = null, key: Any? = Unit) {
    val windowInfo = LocalWindowInfo.current
    val inputModeManager = LocalInputModeManager.current
    val view = LocalView.current
    LaunchedEffect(key) {
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        if (inputModeManager.inputMode != InputMode.Keyboard) {
            // in touch mode only text fields can hold focus, so nothing else can have been focused before this
            snapshotFlow { inputModeManager.inputMode }.first { it == InputMode.Keyboard }
            snapshotFlow { windowInfo.isWindowFocused }.first { it }
            if (view.windowHasOtherFocus()) return@LaunchedEffect
        }
        if (requester.requestFocusSafely() || fallback?.requestFocusSafely() == true) return@LaunchedEffect
        withFrameNanos { }
        if (!requester.requestFocusSafely()) fallback?.requestFocusSafely()
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
