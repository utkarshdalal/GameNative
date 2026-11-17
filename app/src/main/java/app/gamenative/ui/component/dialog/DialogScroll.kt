package app.gamenative.ui.component.dialog

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.launch

/**
 * Lets the D-pad and keyboard scroll a dialog body that has nothing focusable inside it.
 *
 * Put it on a container that holds both the scrolling body and the buttons, and pass the body's
 * [ScrollState]. Up and Down scroll while there is more to show, then move focus as usual.
 * Do not use it when the body has its own focusable rows: focus traversal already scrolls those.
 */
@Composable
fun Modifier.dialogScrollKeys(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    return onKeyEvent { event ->
        val forward = when (event.key) {
            Key.DirectionDown, Key.PageDown -> true
            Key.DirectionUp, Key.PageUp -> false
            else -> return@onKeyEvent false
        }
        // A state that is not attached to a layout reports that it can scroll forever.
        if (state.viewportSize == 0) return@onKeyEvent false
        if (if (forward)!state.canScrollForward else !state.canScrollBackward) return@onKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            val step = state.viewportSize * 2 / 3f
            scope.launch { state.animateScrollBy(if (forward) step else -step) }
        }
        true
    }
}

/**
 * Focus for a dialog's default button. A dialog opened by touch cannot take focus until the first
 * key or controller press leaves touch mode, so the request repeats when the input mode changes.
 */
@Composable
fun Modifier.dialogDefaultFocus(): Modifier {
    val requester = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(inputMode) { runCatching { requester.requestFocus() } }
    return focusRequester(requester)
}
