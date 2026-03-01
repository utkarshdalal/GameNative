package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.enums.ConnectionState
import app.gamenative.ui.theme.PluviaTheme

private const val TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS = 5

@Composable
fun ConnectionStatusBanner(
    connectionState: ConnectionState,
    connectionMessage: String?,
    timeoutSeconds: Int,
    onContinueOffline: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isVisible = connectionState == ConnectionState.CONNECTING ||
                    connectionState == ConnectionState.DISCONNECTED

    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Transparent,
            shadowElevation = 4.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ConnectionIcon(connectionState)

                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTING -> {
                                connectionMessage ?: stringResource(R.string.connection_reconnecting)
                            }
                            ConnectionState.DISCONNECTED -> {
                                connectionMessage ?: stringResource(R.string.connection_disconnected)
                            }
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    if (connectionState == ConnectionState.CONNECTING && timeoutSeconds >= TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS) {
                        Text(
                            text = "${timeoutSeconds}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    when (connectionState) {
                        ConnectionState.CONNECTING -> {
                            if (timeoutSeconds >= TIMEOUT_SHOW_OFFLINE_OPTION_SECONDS) {
                                TextButton(
                                    onClick = onContinueOffline,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.continue_offline),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        ConnectionState.DISCONNECTED -> {
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.connection_retry),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        else -> {}
                    }

                    // Dismiss button
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionIcon(connectionState: ConnectionState) {
    val infiniteTransition = rememberInfiniteTransition(label = "connectionIcon")

    when (connectionState) {
        ConnectionState.CONNECTING -> {
            val rotation by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "rotation"
            )

            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(rotation)
                )
            }
        }
        ConnectionState.DISCONNECTED -> {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        else -> {}
    }
}

@Preview
@Composable
private fun Preview_ConnectionStatusBanner_Connecting() {
    PluviaTheme {
        ConnectionStatusBanner(
            connectionState = ConnectionState.CONNECTING,
            connectionMessage = "Reconnecting to Steam...",
            timeoutSeconds = 3,
            onContinueOffline = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun Preview_ConnectionStatusBanner_Connecting_WithTimeout() {
    PluviaTheme {
        ConnectionStatusBanner(
            connectionState = ConnectionState.CONNECTING,
            connectionMessage = "Reconnecting to Steam...",
            timeoutSeconds = 8,
            onContinueOffline = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun Preview_ConnectionStatusBanner_Disconnected() {
    PluviaTheme {
        ConnectionStatusBanner(
            connectionState = ConnectionState.DISCONNECTED,
            connectionMessage = "Connection lost",
            timeoutSeconds = 0,
            onContinueOffline = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}

@Preview
@Composable
private fun Preview_ConnectionStatusBanner_Connected() {
    PluviaTheme {
        // Should not be visible
        ConnectionStatusBanner(
            connectionState = ConnectionState.CONNECTED,
            connectionMessage = null,
            timeoutSeconds = 0,
            onContinueOffline = {},
            onRetry = {},
            onDismiss = {},
        )
    }
}
