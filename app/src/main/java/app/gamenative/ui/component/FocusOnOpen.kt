package app.gamenative.ui.component

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import kotlin.math.abs
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * For a screen or in-window panel of the activity window: when a D-pad or A press (or one of [extraKeys], keys the
 * screen binds itself) arrives while nothing in this element has focus, this focuses [requester] (else [fallback])
 * under keyboard input mode. After touch, compose has no focused node to give the key to, so the key would do nothing;
 * this takes it from the app key bus first, like the library does. That press only brings focus back: it is consumed
 * with its key up. Put it on the screen's root.
 *
 * It acts only while [enabled], the window has focus (no dialog or menu on top) and the screen is resumed (not while a
 * game runs), and never while a text field or an embedded view holds focus. Dialog and popup windows do not get keys
 * from the bus; [FocusOnOpen] covers those.
 */
@Composable
fun Modifier.focusOnNavigationKey(
    requester: FocusRequester,
    fallback: FocusRequester? = null,
    enabled: Boolean = true,
    extraKeys: Set<Int> = emptySet(),
): Modifier {
    val windowInfo = LocalWindowInfo.current
    val inputModeManager = LocalInputModeManager.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val currentRequester by rememberUpdatedState(requester)
    val currentFallback by rememberUpdatedState(fallback)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentExtraKeys by rememberUpdatedState(extraKeys)
    val focus = remember { NavigationKeyFocus() }
    // a panel that closes with focus inside leaves composition without a focus change
    SideEffect { if (!enabled) focus.hasFocus = false }
    // after touch the system eats the first d-pad press to leave touch mode, so the bus never sees it
    LaunchedEffect(Unit) {
        snapshotFlow { inputModeManager.inputMode }.drop(1).collect { mode ->
            if (mode != InputMode.Keyboard) return@collect
            withFrameNanos { }
            if (!currentEnabled || focus.hasFocus || !windowInfo.isWindowFocused) return@collect
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || view.windowHasOtherFocus()) return@collect
            if (!currentRequester.requestFocusSafely()) currentFallback?.requestFocusSafely()
        }
    }
    DisposableEffect(Unit) {
        fun focusDefault() = currentRequester.requestFocusSafely() || currentFallback?.requestFocusSafely() == true
        val listener: (AndroidEvent.KeyEvent) -> Boolean = listener@{ androidEvent ->
            val event = androidEvent.event
            val keyCode = event.keyCode
            if (event.action == KeyEvent.ACTION_UP && keyCode == focus.swallowUp) {
                focus.swallowUp = null
                return@listener true
            }
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return@listener false
            // a key up that never came must not eat a later one
            focus.swallowUp = null
            if (keyCode !in NavigationKeys && keyCode !in currentExtraKeys) return@listener false
            if (!currentEnabled || focus.hasFocus || !windowInfo.isWindowFocused) return@listener false
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || view.windowHasOtherFocus()) {
                return@listener false
            }
            if (inputModeManager.inputMode != InputMode.Keyboard) inputModeManager.requestInputMode(InputMode.Keyboard)
            if (!focusDefault()) {
                // the default may not be laid out yet, or keyboard mode may land a frame later; the key goes on
                scope.launch {
                    withFrameNanos { }
                    if (currentEnabled && !focus.hasFocus && windowInfo.isWindowFocused) focusDefault()
                }
                return@listener false
            }
            focus.swallowUp = keyCode
            true
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(listener)
        onDispose { PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(listener) }
    }
    return onFocusChanged { focus.hasFocus = it.hasFocus }
}

private class NavigationKeyFocus {
    var hasFocus = false
    var swallowUp: Int? = null
}

private val NavigationKeys = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_BUTTON_A,
)

/**
 * The kind of the user's last input, app-wide. Set from the activity's key and touch dispatch; a dialog or popup
 * window does not route through the activity, so inside one it stays what it was when the window opened.
 */
object LastInput {
    @Volatile
    var isController: Boolean = false
        private set

    /** From a focused element of a dialog or popup window: whether that window is in keyboard input mode. */
    fun onWindowInputMode(keyboard: Boolean) {
        isController = keyboard
    }

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
            // a resting stick still reports small values
            if (StickAxes.any { abs(event.getAxisValue(it)) > 0.5f }) isController = true
            return
        }
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS) isController = false
    }
}

private val StickAxes = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
)

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
