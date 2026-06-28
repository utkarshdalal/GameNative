package app.gamenative.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/**
 * Attaches [focusRequester] and requests focus once laid out, so a freshly opened screen already
 * has a focused element. Apply BEFORE `.focusable()`/`.selectable()`/`.clickable()`.
 *
 * [enabled] gates the request; focus is (re)requested each time it becomes `true`.
 */
@Composable
fun Modifier.requestInitialFocus(
    focusRequester: FocusRequester,
    enabled: Boolean = true,
): Modifier {
    LaunchedEffect(focusRequester, enabled) {
        if (!enabled) return@LaunchedEffect
        // Node may not be placed on the first frame, and requestFocus() returns false when the
        // request is not honored yet; retry until it actually takes.
        repeat(5) {
            try {
                if (focusRequester.requestFocus()) return@LaunchedEffect
            } catch (_: IllegalStateException) {
            }
            delay(32)
        }
    }
    return this.focusRequester(focusRequester)
}
