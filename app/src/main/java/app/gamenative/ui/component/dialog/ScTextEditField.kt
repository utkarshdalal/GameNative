package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.TritonProtocol

/**
 * A text field for the in-game Steam-Controller editors that does NOT pop the Android system IME (which, on the
 * cover display, covers the whole screen and leaves nowhere for the pad cursor to move to). Instead it shows a
 * read-only value that, when tapped, opens a compact on-screen keyboard *inside a dialog* — the user types by
 * clicking keys with the pad cursor (right trackpad + click, with haptics), exactly like every other editor control.
 *
 * [onValueChange] fires only on Done (B / Cancel discards), matching the "focus → edit → accept/cancel" model.
 */
@Composable
fun ScTextEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Optional controlled editing state, so a parent (e.g. d-pad "A") can open the keyboard. Null = self-managed. */
    editing: Boolean = false,
    onEditingChange: ((Boolean) -> Unit)? = null,
    /** When false, drop the stacked label (it becomes the empty-value placeholder) so the field is a single compact
     *  box — used where a selection outline wraps it and the label would make that outline look oversized. */
    showLabel: Boolean = true,
) {
    var internalEditing by remember { mutableStateOf(false) }
    val isEditing = if (onEditingChange != null) editing else internalEditing
    val setEditing: (Boolean) -> Unit = onEditingChange ?: { internalEditing = it }
    Column(modifier) {
        if (showLabel) Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),  // match the nav-selection outline radius
            modifier = Modifier.fillMaxWidth().then(if (showLabel) Modifier.padding(top = 2.dp) else Modifier).clickable { setEditing(true) },
        ) {
            Text(
                value.ifBlank { if (showLabel) "Tap to edit" else label },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (isEditing) {
        ScOnScreenKeyboardDialog(
            label = label,
            initial = value,
            onCancel = { setEditing(false) },
            onDone = { onValueChange(it); setEditing(false) },
        )
    }
}

/** Compact cursor-clickable keyboard. No system IME — types into a local string committed on Done. Also used as a
 *  standalone name-entry prompt (e.g. the config manager's duplicate/rename). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScOnScreenKeyboardDialog(
    label: String,
    initial: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var shift by remember { mutableStateOf(false) }
    val kbNav = remember { ScNavState() }
    val toggleShift = { shift = !shift }
    val space = { text += " " }
    val backspace = { if (text.isNotEmpty()) text = text.dropLast(1) }
    val done = { onDone(text) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
                    .scCaptureFocus()
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (ev.key) {
                            Key.DirectionDown -> { kbNav.moveVertical(1); true }
                            Key.DirectionUp -> { kbNav.moveVertical(-1); true }
                            Key.DirectionRight -> { kbNav.moveHorizontal(1); true }
                            Key.DirectionLeft -> { kbNav.moveHorizontal(-1); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> { kbNav.activate(); true }
                            // Deck-style shortcuts, so typing doesn't mean walking the cursor to every function key.
                            Key.ButtonX -> { backspace(); true }
                            Key.ButtonY -> { space(); true }
                            Key.ButtonL1 -> { toggleShift(); true }
                            Key.ButtonStart -> { done(); true }
                            else -> false
                        }
                    },
            ) {
                // Put this keyboard on the SC nav stack so the pad cursor taps land here and B cancels it.
                ScNavDialogCapture(onBack = onCancel)
                ScBackTitle("Edit: $label", onBack = onCancel, style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        text.ifEmpty { " " },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")
                rows.forEachIndexed { r, row ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEachIndexed { c, ch0 ->
                            val ch = if (shift) ch0.uppercaseChar() else ch0
                            val type = { text += ch }
                            ScNavItem(kbNav, r, c, modifier = Modifier.weight(1f), shape = KeyShape, onActivate = type) {
                                KeyCap(ch.toString(), Modifier.fillMaxWidth(), kbNav.isSelected(r, c), onClick = type)
                            }
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    ScNavItem(kbNav, 4, 0, shape = KeyShape, modifier = Modifier.weight(1.4f), onActivate = toggleShift) {
                        KeyCap(if (shift) "⇧ ✓" else "⇧", Modifier.fillMaxWidth(), kbNav.isSelected(4, 0), TritonProtocol.BTN_LBUMPER, toggleShift)
                    }
                    ScNavItem(kbNav, 4, 1, shape = KeyShape, modifier = Modifier.weight(3f), onActivate = space) {
                        KeyCap("Space", Modifier.fillMaxWidth(), kbNav.isSelected(4, 1), TritonProtocol.BTN_Y, space)
                    }
                    ScNavItem(kbNav, 4, 2, shape = KeyShape, modifier = Modifier.weight(1.4f), onActivate = backspace) {
                        KeyCap("⌫", Modifier.fillMaxWidth(), kbNav.isSelected(4, 2), TritonProtocol.BTN_X, backspace)
                    }
                }
                // Button legend, like the Deck keyboard's footer. Wraps on the narrow cover screen.
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ScLegendHint(TritonProtocol.BTN_A, "Type")
                    ScLegendHint(TritonProtocol.BTN_X, "Backspace")
                    ScLegendHint(TritonProtocol.BTN_Y, "Space")
                    ScLegendHint(TritonProtocol.BTN_LBUMPER, "Shift")
                    ScLegendHint(TritonProtocol.BTN_VIEW, "Done")
                    ScLegendHint(TritonProtocol.BTN_B, "Cancel")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScNavItem(kbNav, 5, 0, onActivate = onCancel) { TextButton(onClick = onCancel) { Text("Cancel") } }
                    Spacer(Modifier.width(8.dp))
                    ScNavItem(kbNav, 5, 1, onActivate = done) { Button(onClick = done) { Text("Done") } }
                }
            }
        }
    }
}

private val KeyShape = RoundedCornerShape(6.dp)

/** One key. [selected] (the controller cursor) gets a solid primary fill: the nav row's own tint sits behind this
 *  opaque cap, so without it only the thin ring showed against the light keys. [glyph] = shortcut button badge. */
@Composable
private fun KeyCap(label: String, modifier: Modifier = Modifier, selected: Boolean = false, glyph: Int? = null, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(46.dp)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                KeyShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (glyph != null) { ScButtonGlyph(glyph, size = 20.dp); Spacer(Modifier.width(6.dp)) }
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
