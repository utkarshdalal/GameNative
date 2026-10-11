package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.online.OnlineFailReason
import app.gamenative.ui.component.NoExtractOutlinedTextField

data class OnlineSessionFeedbackDialogState(
    val visible: Boolean = false,
    val appId: String = "",
    val gameName: String = "",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnlineSessionFeedbackDialog(
    state: OnlineSessionFeedbackDialogState,
    onWorked: (notes: String?) -> Unit,
    onFailed: (reason: OnlineFailReason, notes: String?) -> Unit,
    onSkip: () -> Unit,
    onCopyDebug: () -> Unit,
) {
    if (!state.visible) return

    var selectedReason by remember(state.appId) { mutableStateOf<OnlineFailReason?>(null) }
    var showFailReasons by remember(state.appId) { mutableStateOf(false) }
    var notes by remember(state.appId) { mutableStateOf("") }

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.online_feedback_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.online_feedback_subtitle, state.gameName),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                Spacer(modifier = Modifier.height(20.dp))

                if (!showFailReasons) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { onWorked(notes.takeIf { it.isNotBlank() }) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.online_feedback_worked))
                        }
                        OutlinedButton(
                            onClick = { showFailReasons = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.online_feedback_failed))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.online_feedback_reason_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OnlineFailReason.entries.forEach { reason ->
                            FilterChip(
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason },
                                label = { Text(reason.label) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    NoExtractOutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(stringResource(R.string.online_feedback_notes_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        enabled = selectedReason != null,
                        onClick = {
                            selectedReason?.let { onFailed(it, notes.takeIf { n -> n.isNotBlank() }) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.online_feedback_submit_fail))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCopyDebug) {
                    Text(stringResource(R.string.online_feedback_copy_debug))
                }
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.online_feedback_skip))
                }
            }
        }
    }
}
