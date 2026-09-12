package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A controller-navigable replacement for a Material `DropdownMenu`: a small modal that lists [options] as a d-pad
 * selectable vertical list (a real menu the user can see and pick from), instead of a popup window the SC nav bridge
 * can't reach. Pushes onto the [ScNavDialogStack] (so the bridge dispatches d-pad/A here and B cancels), focusable
 * root drives [ScNavState]. Picking an option calls [onPick] then [onDismiss].
 */
@Composable
fun <T> ScNavChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val nav = remember { ScNavState() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.8f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                val listScroll = rememberScrollState()
                ScScrollbar(listScroll, Modifier.heightIn(max = 420.dp)) {
                    Column(modifier = Modifier.verticalScroll(listScroll)) {
                        options.forEachIndexed { i, (value, lbl) ->
                            val pick = { onPick(value); onDismiss() }
                            val isSel = value == selected
                            ScNavItem(nav, i, modifier = Modifier.fillMaxWidth(), onActivate = pick) {
                                Text(
                                    (if (isSel) "●  " else "○  ") + lbl,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 12.dp, horizontal = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
