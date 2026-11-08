package app.gamenative.ui.component.dialog

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.optimize.OptimizeController
import app.gamenative.optimize.OptimizeLocalState
import app.gamenative.optimize.OptimizeProfile
import app.gamenative.optimize.OptimizeStatus
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OptimizeDialog(
    appId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(appId) { mutableStateOf<OptimizeLocalState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

    LaunchedEffect(appId, revision) {
        state = OptimizeController.readState(context, appId) ?: OptimizeLocalState()
    }

    fun perform(doneMessage: Int?, action: suspend () -> OptimizeController.ChangeResult) {
        busy = true
        scope.launch {
            val result = withContext(NonCancellable) { action() }
            when (result) {
                OptimizeController.ChangeResult.GAME_RUNNING -> SnackbarManager.show(context.getString(R.string.optimize_game_running))
                OptimizeController.ChangeResult.FAILED -> SnackbarManager.show(context.getString(R.string.optimize_undo_failed))
                OptimizeController.ChangeResult.DONE -> doneMessage?.let { SnackbarManager.show(context.getString(it)) }
                OptimizeController.ChangeResult.NO_CONTAINER -> Unit
            }
            busy = false
            revision++
        }
    }

    val current = state
    val profile = current?.profile ?: OptimizeProfile.OFF
    val statusText = when (val status = current?.let { OptimizeStatus.of(it) } ?: OptimizeStatus.Off) {
        OptimizeStatus.Off -> null
        OptimizeStatus.NotStarted -> stringResource(R.string.optimize_status_not_started)
        OptimizeStatus.Measuring -> stringResource(R.string.optimize_status_measuring)
        is OptimizeStatus.Trying -> {
            val cost = OptimizeStatus.visualCostRes(status.visualCostId)?.let { stringResource(it) } ?: status.visualCostEn
            if (cost.isNullOrBlank()) {
                stringResource(R.string.optimize_status_trying_plain)
            } else {
                stringResource(R.string.optimize_status_trying, cost)
            }
        }
        is OptimizeStatus.Kept -> pluralStringResource(R.plurals.optimize_status_kept, status.count, status.count)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.option_optimize)) },
        text = {
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                OptimizeProfile.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = option == profile,
                                enabled = current != null && !busy,
                                onClick = {
                                    if (option != profile) perform(null) { OptimizeController.setProfile(context, appId, option) }
                                },
                                role = Role.RadioButton,
                            )
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == profile, onClick = null)
                        Column(modifier = Modifier.padding(start = 16.dp)) {
                            Text(text = stringResource(option.title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = stringResource(option.description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                statusText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp, top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        },
        dismissButton = {
            TextButton(
                enabled = current?.hasChanges == true && !busy,
                onClick = { perform(R.string.optimize_restored) { OptimizeController.restoreOriginal(context, appId) } },
            ) {
                Text(text = stringResource(R.string.optimize_restore_original))
            }
        },
    )
}
