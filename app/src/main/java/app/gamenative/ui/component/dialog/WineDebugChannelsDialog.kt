package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R

@Composable
fun WineDebugChannelsDialog(
    openDialog: Boolean,
    allChannels: List<String>,
    currentSelection: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!openDialog) return

    // Use a snapshot state list to track selections so Compose recomposes on changes
    val selectedChannels = remember { mutableStateListOf<String>().apply { addAll(currentSelection) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.select_wine_debug_channels)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                allChannels.forEach { channel ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = selectedChannels.contains(channel),
                                onValueChange = { isChecked ->
                                    if (isChecked) selectedChannels.add(channel)
                                    else selectedChannels.remove(channel)
                                },
                                role = Role.Checkbox,
                            )
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedChannels.contains(channel),
                            onCheckedChange = null,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = channel)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedChannels.toList()) }) {
                Text(text = stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        }
    )
}
