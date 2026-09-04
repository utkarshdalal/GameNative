package app.gamenative.ui.component.dialog

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R

/**
 * Stable id for a physical button: its keyCode, or -scanCode for buttons that report no keyCode
 * (KEYCODE_UNKNOWN), e.g. some back paddles / grip buttons. Real keycodes are >= 0, so the negative
 * range never collides.
 */
internal fun screenshotComboKeyId(keyCode: Int, scanCode: Int): Int =
    if (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode != 0) -scanCode else keyCode

/** Friendly label for a button id (e.g. 96 -> "BUTTON A"; scanCode buttons -> "BUTTON (scan N)"). */
private fun buttonLabel(keyId: Int): String =
    if (keyId < 0) {
        "BUTTON (scan ${-keyId})"
    } else {
        KeyEvent.keyCodeToString(keyId).removePrefix("KEYCODE_").replace('_', ' ')
    }

private fun parseCombo(csv: String): List<Int> =
    csv.split(",").mapNotNull { it.trim().toIntOrNull() }

/** Human-readable combo for the currently-saved screenshot binding, e.g. "BUTTON A + BUTTON L1", or null. */
fun currentScreenshotComboLabel(context: Context): String? {
    val keys = parseCombo(PrefManager.screenshotComboKeys)
    if (keys.isEmpty()) return null
    return keys.joinToString(" + ") { buttonLabel(it) }
}

/** System/navigation keys we must not let the user bind (so the dialog stays usable). */
private val NON_BINDABLE_KEYS = setOf(
    KeyEvent.KEYCODE_UNKNOWN,
    KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_HOME,
    KeyEvent.KEYCODE_APP_SWITCH,
    KeyEvent.KEYCODE_POWER,
    KeyEvent.KEYCODE_VOLUME_UP,
    KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE,
)

// Bindable when not a system/nav key. KEYCODE_UNKNOWN is allowed only if it carries a scanCode
// (so the button can still be identified).
private fun isBindable(keyCode: Int, scanCode: Int): Boolean =
    keyCode !in NON_BINDABLE_KEYS || (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode != 0)

/**
 * Records a screenshot button combo: the user presses one or more controller buttons (held together,
 * including non-standard buttons like back paddles), the UI shows them live, and the combo is saved
 * once all buttons are released. Accepts any button except system/navigation keys.
 */
@Composable
fun ScreenshotControllerBindDialog(onDismiss: () -> Unit, onChanged: (String?) -> Unit = {}) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // The currently-saved combo, so the user can see what's bound before re-recording.
    val currentLabel = remember { currentScreenshotComboLabel(context) }
    // held = currently pressed; recorded = union of everything pressed during this gesture.
    val held = remember { mutableStateListOf<Int>() }
    val recorded = remember { mutableStateListOf<Int>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.screenshots_bind_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        val keyCode = event.nativeKeyEvent.keyCode
                        val scanCode = event.nativeKeyEvent.scanCode
                        if (!isBindable(keyCode, scanCode)) return@onPreviewKeyEvent false
                        val id = screenshotComboKeyId(keyCode, scanCode)
                        when (event.type) {
                            KeyEventType.KeyDown -> {
                                if (id !in held) held.add(id)
                                if (id !in recorded) recorded.add(id)
                                true
                            }
                            KeyEventType.KeyUp -> {
                                held.remove(id)
                                // Commit once the whole combo has been released.
                                if (held.isEmpty() && recorded.isNotEmpty()) {
                                    PrefManager.screenshotComboKeys = recorded.joinToString(",")
                                    // Pass the label directly; the DataStore write above is async.
                                    onChanged(recorded.joinToString(" + ") { buttonLabel(it) })
                                    onDismiss()
                                }
                                true
                            }
                            else -> false
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.screenshots_bind_prompt))
                val shown = if (recorded.isNotEmpty()) recorded else held
                when {
                    shown.isNotEmpty() -> Text(
                        text = shown.joinToString(" + ") { buttonLabel(it) },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    currentLabel != null -> Text(
                        text = stringResource(R.string.screenshots_bind_current, currentLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PrefManager.screenshotComboKeys = ""
                onChanged(null)
                onDismiss()
            }) { Text(stringResource(R.string.screenshots_bind_clear)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
