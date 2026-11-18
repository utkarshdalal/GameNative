package app.gamenative.ui.component.dialog

import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Lets the D-pad, keyboard and right stick scroll a dialog body that has nothing focusable inside it.
 *
 * Put it on a container that holds both the scrolling body and the buttons, and pass the body's
 * [ScrollState]. Up and Down scroll while there is more to show, then move focus as usual.
 * The right stick scrolls at a speed that follows how far it is pushed.
 * Do not use it when the body has its own focusable rows: focus traversal already scrolls those.
 */
@Composable
fun Modifier.dialogScrollKeys(state: ScrollState): Modifier {
    val scope = rememberCoroutineScope()
    DialogStickScroll(state)
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

private const val STICK_DEADZONE = 0.25f

/** Reads the right stick from the dialog window, since a dialog's joystick events never reach the activity. */
@Composable
private fun DialogStickScroll(state: ScrollState) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    val pxPerSecond = with(LocalDensity.current) { 900.dp.toPx() }
    var stick by remember { mutableFloatStateOf(0f) }
    DisposableEffect(window) {
        val original = window.callback
        window.callback = object : Window.Callback by original {
            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val axis = if (event.device?.getMotionRange(MotionEvent.AXIS_RZ) != null) {
                        MotionEvent.AXIS_RZ
                    } else {
                        MotionEvent.AXIS_RY
                    }
                    val value = event.getAxisValue(axis)
                    stick = if (abs(value) > STICK_DEADZONE) value else 0f
                }
                return original.dispatchGenericMotionEvent(event)
            }
        }
        onDispose { window.callback = original }
    }
    val pushed = stick != 0f
    LaunchedEffect(pushed) {
        if (!pushed) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            state.dispatchRawDelta(stick * pxPerSecond * (now - last) / 1_000_000_000f)
            last = now
        }
    }
}
