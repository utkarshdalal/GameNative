package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.ui.theme.PluviaTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugReportDialog(
    state: DebugReportDialogState,
    hasDiscordToken: Boolean,
    onStateChange: (DebugReportDialogState) -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onConnectDiscord: () -> Unit,
    onOpenThread: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.visible) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = state.phase != DebugReportDialogState.PHASE_SENDING,
                dismissOnClickOutside = state.phase != DebugReportDialogState.PHASE_SENDING,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = RoundedCornerShape(20.dp),
                color = PluviaTheme.colors.surfaceElevated,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (state.phase) {
                        DebugReportDialogState.PHASE_SENDING -> {
                            Text(
                                text = stringResource(R.string.debug_report_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                            Text(
                                text = stringResource(R.string.debug_report_sending),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                        }

                        DebugReportDialogState.PHASE_SUCCESS -> {
                            Text(
                                text = stringResource(R.string.debug_report_success_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Text(
                                text = stringResource(R.string.debug_report_success_message),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.close))
                                }
                                Button(
                                    onClick = onOpenThread,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_open_discord))
                                }
                            }
                        }

                        DebugReportDialogState.PHASE_ERROR -> {
                            Text(
                                text = stringResource(R.string.debug_report_failed_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Text(
                                text = stringResource(R.string.debug_report_failed_message),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            TextButton(
                                onClick = onShare,
                                modifier = Modifier.padding(bottom = 16.dp),
                            ) {
                                Text(stringResource(R.string.debug_report_share_instead))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.close))
                                }
                                Button(
                                    onClick = onSend,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_retry))
                                }
                            }
                        }

                        else -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.debug_report_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                )

                                val logSizeMb = String.format(Locale.US, "%.1f", state.logSizeBytes / (1024f * 1024f))
                                Text(
                                    text = stringResource(
                                        R.string.debug_report_summary,
                                        state.gameName,
                                        state.deviceName,
                                        logSizeMb,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .align(Alignment.Start)
                                        .padding(bottom = 16.dp),
                                )

                                val issueFocusRequester = remember { FocusRequester() }
                                val focusManager = LocalFocusManager.current
                                val imeVisible = WindowInsets.isImeVisible
                                LaunchedEffect(Unit) { runCatching { issueFocusRequester.requestFocus() } }

                                NoExtractOutlinedTextField(
                                    value = state.issueText,
                                    onValueChange = { onStateChange(state.copy(issueText = it)) },
                                    label = { Text(stringResource(R.string.debug_report_what_went_wrong)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp)
                                        .padding(bottom = 16.dp)
                                        .focusRequester(issueFocusRequester)
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown || imeVisible) {
                                                return@onPreviewKeyEvent false
                                            }
                                            when (event.key) {
                                                Key.DirectionUp -> {
                                                    focusManager.moveFocus(FocusDirection.Up)
                                                    true
                                                }
                                                Key.DirectionDown -> {
                                                    focusManager.moveFocus(FocusDirection.Down)
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                    maxLines = 5,
                                )

                                if (!hasDiscordToken) {
                                    Text(
                                        text = stringResource(R.string.debug_report_connect_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                    Button(
                                        onClick = onConnectDiscord,
                                        modifier = Modifier.padding(bottom = 16.dp),
                                    ) {
                                        Text(stringResource(R.string.debug_report_connect_discord))
                                    }
                                }

                                TextButton(
                                    onClick = onShare,
                                    modifier = Modifier.padding(bottom = 16.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_share_instead))
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = onSend,
                                    modifier = Modifier.padding(start = 8.dp),
                                    enabled = state.issueText.isNotBlank() && hasDiscordToken,
                                ) {
                                    Text(stringResource(R.string.debug_report_send))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
