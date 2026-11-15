package app.gamenative.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.BenefitCard
import app.gamenative.ui.component.FocusScaleBackButton
import app.gamenative.ui.component.HeroBadge
import app.gamenative.ui.theme.PluviaTheme

@Composable
fun DebugPaywallScreen(
    gameName: String,
    deviceName: String,
    reason: String,
    hasDiscordToken: Boolean,
    onSubscribe: () -> Unit,
    onConnectDiscord: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        PluviaTheme.colors.surfacePanel,
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FocusScaleBackButton(onClick = onDismiss)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_paywall_title),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.debug_report_summary, gameName, deviceName),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }

                HeroBadge(
                    icon = Icons.Default.SmartToy,
                    accent = PluviaTheme.colors.accentCyan,
                    size = 48.dp,
                    iconSize = 24.dp,
                    iconAlpha = 0.6f,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.debug_paywall_pitch),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                BenefitCard(
                    title = stringResource(R.string.debug_paywall_tier5_title),
                    body = stringResource(R.string.debug_paywall_tier5_body),
                    icon = Icons.Default.SmartToy,
                    iconTint = PluviaTheme.colors.accentCyan,
                )

                BenefitCard(
                    title = stringResource(R.string.debug_paywall_tier10_title),
                    body = stringResource(R.string.debug_paywall_tier10_body),
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = PluviaTheme.colors.accentWarning,
                )

                Text(
                    text = stringResource(R.string.debug_paywall_cancel_anytime) + " " + stringResource(R.string.debug_trial_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                )

                Text(
                    text = stringResource(R.string.debug_paywall_costs_note),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = PluviaTheme.colors.textMuted,
                )

                val subscribeFocusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { subscribeFocusRequester.requestFocus() } }

                Button(
                    onClick = onSubscribe,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(subscribeFocusRequester),
                ) {
                    Text(stringResource(R.string.support_see_plans))
                }

                Text(
                    text = stringResource(R.string.debug_paywall_already),
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                )
                val needsDiscordLink = reason == "not_linked" && !hasDiscordToken
                TextButton(
                    onClick = if (needsDiscordLink) onConnectDiscord else onRetry,
                ) {
                    Text(
                        stringResource(
                            if (needsDiscordLink) {
                                R.string.debug_report_connect_discord
                            } else {
                                R.string.debug_paywall_send_now
                            },
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    }
}
