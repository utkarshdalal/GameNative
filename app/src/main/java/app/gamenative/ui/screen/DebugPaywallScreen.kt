package app.gamenative.ui.screen

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import java.util.Locale

@Composable
fun DebugPaywallScreen(
    gameName: String,
    deviceName: String,
    logSizeBytes: Long,
    reason: String,
    hasDiscordToken: Boolean,
    onSubscribe: () -> Unit,
    onSubscribeKofi: () -> Unit,
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
                PaywallBackButton(onClick = onDismiss)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.debug_paywall_title),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val logSizeMb = String.format(Locale.US, "%.1f", logSizeBytes / (1024f * 1024f))
                    Text(
                        text = stringResource(R.string.debug_report_summary, gameName, deviceName, logSizeMb),
                        style = MaterialTheme.typography.bodySmall,
                        color = PluviaTheme.colors.textMuted,
                    )
                }

                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    PluviaTheme.colors.accentCyan.copy(alpha = 0.2f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.SmartToy,
                        contentDescription = null,
                        tint = PluviaTheme.colors.accentCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(24.dp),
                    )
                }
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

                PaywallTierCard(
                    title = stringResource(R.string.debug_paywall_tier5_title),
                    body = stringResource(R.string.debug_paywall_tier5_body),
                    icon = Icons.Default.SmartToy,
                    iconTint = PluviaTheme.colors.accentCyan,
                )

                PaywallTierCard(
                    title = stringResource(R.string.debug_paywall_tier10_title),
                    body = stringResource(R.string.debug_paywall_tier10_body),
                    icon = Icons.Rounded.AutoAwesome,
                    iconTint = PluviaTheme.colors.accentWarning,
                )

                Text(
                    text = stringResource(R.string.debug_paywall_cancel_anytime),
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
                    Text(stringResource(R.string.debug_paywall_subscribe))
                }

                OutlinedButton(
                    onClick = onSubscribeKofi,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.debug_paywall_kofi_alt))
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

@Composable
private fun PaywallTierCard(
    title: String,
    body: String,
    icon: ImageVector,
    iconTint: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = PluviaTheme.colors.surfaceElevated,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun PaywallBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "backButtonScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    PluviaTheme.colors.accentCyan.copy(alpha = 0.2f)
                } else {
                    PluviaTheme.colors.surfaceElevated
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, PluviaTheme.colors.accentCyan.copy(alpha = 0.6f), CircleShape)
                } else {
                    Modifier.border(1.dp, PluviaTheme.colors.borderDefault.copy(alpha = 0.3f), CircleShape)
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = if (isFocused) PluviaTheme.colors.accentCyan else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp),
        )
    }
}
