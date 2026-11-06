package app.gamenative.ui.component.dialog

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.CloudSaveBackupManager
import java.text.DateFormat
import java.util.Date

@Composable
fun SaveBackupRestoreDialog(
    backups: List<CloudSaveBackupManager.SaveBackup>,
    onRestore: (CloudSaveBackupManager.SaveBackup) -> Unit,
    onDismiss: () -> Unit,
) {
    if (backups.isEmpty()) return

    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    var selectedIndex by remember(backups) { mutableIntStateOf(0) }
    var confirming by remember { mutableStateOf(false) }
    val selected = backups.getOrNull(selectedIndex) ?: backups.first()

    if (confirming) {
        MessageDialog(
            visible = true,
            title = stringResource(R.string.save_backup_restore_confirm_title),
            message = stringResource(
                R.string.save_backup_restore_confirm_message,
                dateFormat.format(Date(selected.createdAt)),
            ),
            confirmBtnText = stringResource(R.string.save_backup_restore_button),
            dismissBtnText = stringResource(R.string.cancel),
            onConfirmClick = {
                confirming = false
                onRestore(selected)
            },
            onDismissClick = { confirming = false },
            onDismissRequest = { confirming = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.save_backup_restore_dialog_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                backups.forEachIndexed { index, backup ->
                    val details = stringResource(
                        R.string.save_backup_size_and_count,
                        Formatter.formatShortFileSize(context, backup.sizeBytes),
                        pluralStringResource(R.plurals.save_backup_file_count, backup.fileCount, backup.fileCount),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = index == selectedIndex,
                                onClick = { selectedIndex = index },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = null,
                        )
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(
                                text = stringResource(
                                    if (backup.kind == CloudSaveBackupManager.Kind.PRE_RESTORE) {
                                        R.string.save_backup_before_restore
                                    } else {
                                        R.string.save_backup_before_sync
                                    },
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = dateFormat.format(Date(backup.createdAt)),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = details,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { confirming = true }) {
                Text(text = stringResource(R.string.save_backup_restore_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}
