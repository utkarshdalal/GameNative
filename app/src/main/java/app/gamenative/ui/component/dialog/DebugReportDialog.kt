package app.gamenative.ui.component.dialog

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.ui.theme.PluviaTheme

const val AI_HELP_PATH_APP = "app"
const val AI_HELP_PATH_DISCORD = "discord"

fun debugReportUsesApp(
    appChatEnabled: Boolean,
    hasDiscordToken: Boolean,
    accountSignedIn: Boolean,
    preferredPath: String,
): Boolean = appChatEnabled && accountSignedIn && when (preferredPath) {
    AI_HELP_PATH_APP -> true
    AI_HELP_PATH_DISCORD -> false
    else -> !hasDiscordToken
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebugReportDialog(
    state: DebugReportDialogState,
    hasDiscordToken: Boolean,
    appChatEnabled: Boolean,
    accountSignedIn: Boolean,
    preferredPath: String,
    sendProgress: Float?,
    onStateChange: (DebugReportDialogState) -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onConnectDiscord: () -> Unit,
    onSignInForApp: () -> Unit,
    onPreferredPathChange: (String) -> Unit,
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
                            if (sendProgress != null && sendProgress > 0f) {
                                LinearProgressIndicator(
                                    progress = { sendProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 16.dp),
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp))
                            }
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

                        DebugReportDialogState.PHASE_NO_LOG -> {
                            Text(
                                text = stringResource(R.string.debug_report_title),
                                style = MaterialTheme.typography.headlineSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Text(
                                text = stringResource(R.string.debug_report_no_log),
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
                            val usesApp = debugReportUsesApp(appChatEnabled, hasDiscordToken, accountSignedIn, preferredPath)
                            val canSend = state.issueText.isNotBlank() && !state.preparing
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

                                Text(
                                    text = stringResource(
                                        R.string.debug_report_summary,
                                        state.gameName,
                                        state.deviceName,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .align(Alignment.Start)
                                        .padding(bottom = if (state.preparing) 8.dp else 16.dp),
                                )

                                if (state.preparing) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.Start)
                                            .padding(bottom = 16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = stringResource(R.string.debug_report_preparing),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = PluviaTheme.colors.textMuted,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }

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

                                if (!usesApp && !hasDiscordToken) {
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

                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.cancel))
                                }
                                Button(
                                    onClick = onSend,
                                    modifier = Modifier.padding(start = 8.dp),
                                    enabled = canSend && (usesApp || hasDiscordToken),
                                ) {
                                    Text(stringResource(R.string.debug_report_send))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = onShare,
                                    enabled = !state.preparing,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_share_instead), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                                if (appChatEnabled) {
                                    val altInteraction = remember { MutableInteractionSource() }
                                    TextButton(
                                        onClick = {
                                            when {
                                                usesApp -> onPreferredPathChange(AI_HELP_PATH_DISCORD)
                                                accountSignedIn -> onPreferredPathChange(AI_HELP_PATH_APP)
                                                else -> onSignInForApp()
                                            }
                                        },
                                        enabled = usesApp || accountSignedIn || canSend,
                                        interactionSource = altInteraction,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .focusRing(altInteraction, RoundedCornerShape(12.dp), width = 2.dp),
                                    ) {
                                        Text(
                                            stringResource(if (usesApp) R.string.debug_report_use_discord else R.string.debug_report_use_app),
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
