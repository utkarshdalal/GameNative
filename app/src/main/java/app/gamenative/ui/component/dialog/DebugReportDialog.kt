package app.gamenative.ui.component.dialog

import android.view.InputDevice
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.billing.Membership
import app.gamenative.ui.component.BenefitRow
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.RingButton
import app.gamenative.ui.component.RingTextButton
import app.gamenative.ui.component.dialog.state.DebugReportDialogState
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.requestFocusAfterLayout
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedDialogSurface

/**
 * In-app chat is where every report goes by default, whatever the plan's source: a
 * Discord-granted plan reaches the app through the account merge, so a linked Discord
 * account is never a reason to send a signed-in report to Discord. A signed-out user
 * without a Discord link also gets the app route, and Send asks them to sign in first.
 * Discord is used only when the Discord action was tapped for this report ([useDiscord],
 * reset each time the dialog opens), when a signed-out user already linked Discord, or
 * when the app chat is unavailable.
 */
fun debugReportUsesApp(
    appChatEnabled: Boolean,
    accountSignedIn: Boolean,
    hasDiscordToken: Boolean,
    useDiscord: Boolean,
): Boolean = appChatEnabled && !useDiscord && (accountSignedIn || !hasDiscordToken)

/** Above the sign-in steps when a report needs an account: where the answer goes, what it costs, how long it takes. */
@Composable
fun DebugReportSignInHero() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HeroBadge(icon = Icons.Filled.SmartToy, accent = PluviaTheme.colors.accentCyan)
        Text(
            text = stringResource(R.string.debug_signin_answer_in_app),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun DebugReportSignInBenefits() {
    if (Membership.aiTrialOffered()) {
        BenefitRow(
            title = stringResource(R.string.debug_signin_first_free),
            icon = Icons.Rounded.AutoAwesome,
            iconTint = PluviaTheme.colors.accentWarning,
            compact = true,
        )
    }
    BenefitRow(
        title = stringResource(R.string.debug_signin_about_a_minute),
        icon = Icons.Filled.HourglassTop,
        iconTint = PluviaTheme.colors.accentSuccess,
        compact = true,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DebugReportDialog(
    state: DebugReportDialogState,
    hasDiscordToken: Boolean,
    appChatEnabled: Boolean,
    accountSignedIn: Boolean,
    useDiscord: Boolean,
    sendProgress: Float?,
    onStateChange: (DebugReportDialogState) -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onConnectDiscord: () -> Unit,
    onSignInForApp: () -> Unit,
    onUseDiscordChange: (Boolean) -> Unit,
    onOpenThread: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.visible) {
        val inputModeManager = LocalInputModeManager.current
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = state.phase != DebugReportDialogState.PHASE_SENDING,
                dismissOnClickOutside = state.phase != DebugReportDialogState.PHASE_SENDING,
                // Lets the dialog see the keyboard's height, so it can stay above it.
                decorFitsSystemWindows = false,
            ),
        ) {
            ThemedDialogSurface(
                modifier = Modifier
                    .imePadding()
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
                                        color = PluviaTheme.progressColor,
                                        trackColor = PluviaTheme.sliderTrackColor(ProgressIndicatorDefaults.linearTrackColor),
                                )
                            } else {
                                CircularProgressIndicator(modifier = Modifier.padding(bottom = 16.dp), color = PluviaTheme.progressColor)
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
                                RingTextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.close))
                                }
                                RingButton(
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
                                RingTextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.close))
                                }
                            }
                        }

                        DebugReportDialogState.PHASE_ERROR -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
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
                                RingTextButton(
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
                                RingTextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.close))
                                }
                                RingButton(
                                    onClick = onSend,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_retry))
                                }
                            }
                        }

                        else -> {
                            val usesApp = debugReportUsesApp(appChatEnabled, accountSignedIn, hasDiscordToken, useDiscord)
                            val canSend = !state.preparing
                            val sendEnabled = canSend && (usesApp || hasDiscordToken)
                            val sendFocusRequester = remember { FocusRequester() }
                            // The on-screen keyboard cannot be driven by a controller, so only touch opens on the text field.
                            val openedByTouch = remember { inputModeManager.inputMode == InputMode.Touch }
                            var padFocusedSend by remember { mutableStateOf(false) }
                            LaunchedEffect(sendEnabled) {
                                if (openedByTouch || !sendEnabled || padFocusedSend) return@LaunchedEffect
                                padFocusedSend = true
                                sendFocusRequester.requestFocusAfterLayout()
                            }
                            var padKeyboardOpen by remember { mutableStateOf(false) }
                            // After the controller keyboard closes: true = move to Send, false = stay on the field.
                            var focusSendAfterPadKeyboard by remember { mutableStateOf<Boolean?>(null) }
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
                                            color = PluviaTheme.progressColor,
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
                                val keyboard = LocalSoftwareKeyboardController.current
                                LaunchedEffect(Unit) { if (openedByTouch) runCatching { issueFocusRequester.requestFocus() } }

                                // A controller cannot drive the on-screen keyboard, so its Up/Down always leave the field,
                                // read before the keyboard takes them. A hardware keyboard keeps its arrows while typing.
                                val leaveField: (KeyEvent) -> Boolean = handler@{ event ->
                                    val sources = event.nativeKeyEvent.device?.sources ?: 0
                                    val fromPad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                                        sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
                                    if (imeVisible && !fromPad) return@handler false
                                    if (fromPad && (event.key == Key.ButtonA || event.key == Key.DirectionCenter)) {
                                        if (event.type == KeyEventType.KeyDown) {
                                            keyboard?.hide()
                                            padKeyboardOpen = true
                                        }
                                        return@handler true
                                    }
                                    val direction = when (event.key) {
                                        Key.DirectionUp -> FocusDirection.Up
                                        Key.DirectionDown -> FocusDirection.Down
                                        Key.ButtonB -> if (imeVisible) null else return@handler false
                                        else -> return@handler false
                                    }
                                    if (event.type == KeyEventType.KeyDown) {
                                        if (direction != null) focusManager.moveFocus(direction)
                                        if (fromPad) keyboard?.hide()
                                    }
                                    true
                                }

                                NoExtractOutlinedTextField(
                                    value = state.issueText,
                                    onValueChange = { onStateChange(state.copy(issueText = it)) },
                                    label = { Text(stringResource(R.string.debug_report_what_went_wrong)) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 100.dp)
                                        .padding(bottom = 16.dp)
                                        .focusRequester(issueFocusRequester)
                                        .onPreInterceptKeyBeforeSoftKeyboard(leaveField)
                                        .onPreviewKeyEvent(leaveField),
                                    maxLines = 5,
                                )

                                if (padKeyboardOpen) {
                                    ScOnScreenKeyboardDialog(
                                        label = stringResource(R.string.debug_report_what_went_wrong),
                                        initial = state.issueText,
                                        onCancel = {
                                            padKeyboardOpen = false
                                            focusSendAfterPadKeyboard = sendEnabled
                                        },
                                        onDone = { text ->
                                            onStateChange(state.copy(issueText = text))
                                            padKeyboardOpen = false
                                            focusSendAfterPadKeyboard = sendEnabled
                                        },
                                    )
                                }

                                // Waits for this window to get focus back from the keyboard dialog, and for Send to be enabled.
                                val windowFocused = LocalWindowInfo.current.isWindowFocused
                                LaunchedEffect(focusSendAfterPadKeyboard, windowFocused, sendEnabled) {
                                    val toSend = focusSendAfterPadKeyboard ?: return@LaunchedEffect
                                    if (!windowFocused || (toSend && !sendEnabled)) return@LaunchedEffect
                                    if (toSend) runCatching { sendFocusRequester.requestFocus() }
                                    keyboard?.hide()
                                    focusSendAfterPadKeyboard = null
                                }

                                if (!usesApp && !hasDiscordToken) {
                                    Text(
                                        text = stringResource(R.string.debug_report_connect_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(bottom = 8.dp),
                                    )
                                    RingButton(
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
                                RingTextButton(onClick = onDismiss) {
                                    Text(stringResource(R.string.cancel))
                                }
                                RingButton(
                                    onClick = onSend,
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .focusRequester(sendFocusRequester),
                                    enabled = sendEnabled,
                                ) {
                                    Text(stringResource(R.string.debug_report_send))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RingTextButton(
                                    onClick = onShare,
                                    enabled = !state.preparing,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                ) {
                                    Text(stringResource(R.string.debug_report_share_instead), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                            if (appChatEnabled) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val altInteraction = remember { MutableInteractionSource() }
                                    TextButton(
                                        onClick = {
                                            when {
                                                usesApp -> onUseDiscordChange(true)
                                                accountSignedIn || !hasDiscordToken -> onUseDiscordChange(false)
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
                                            stringResource(
                                                when {
                                                    !usesApp -> R.string.debug_report_use_app
                                                    accountSignedIn -> R.string.debug_report_use_discord
                                                    else -> R.string.debug_report_use_discord_no_account
                                                },
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            textAlign = TextAlign.End,
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
