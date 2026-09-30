package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * The Steam Controller QuickMenu hub — the list of sub-editors (Manage Configs / Buttons & Bindings / …). Styled to
 * match the bindings editor and the other SC menus: a flat rounded [Surface] card, [ScNavItem] rows with the rotating
 * gradient selection ring + purple selected text, and a filled oval Back chip. Controller-navigable via
 * [ScNavDialogColumn] (d-pad + A, B = back); the pad cursor also works. Each [items] entry is (label, action).
 */
@Composable
fun ScRootMenuDialog(title: String, items: List<Pair<String, () -> Unit>>, onBack: () -> Unit) {
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            val nav = remember { ScNavState() }
            ScNavDialogColumn(nav, onBack = onBack, modifier = Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                items.forEachIndexed { i, (label, action) ->
                    ScNavItem(nav, line = i, modifier = Modifier.fillMaxWidth(), onActivate = action) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    ScNavItem(nav, line = items.size, onActivate = onBack) { ScActionChip("Back", onClick = onBack, filled = true) }
                }
            }
        }
    }
}
