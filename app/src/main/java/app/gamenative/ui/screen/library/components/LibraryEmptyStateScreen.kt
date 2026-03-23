package app.gamenative.ui.screen.library.components

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.theme.PluviaCyan
import app.gamenative.ui.theme.PluviaForegroundMuted
import app.gamenative.ui.theme.PluviaPurple
import app.gamenative.ui.util.shouldShowGamepadUI

/**
 * Represents the different empty states the library can be in.
 */
sealed class LibraryEmptyState {
    data class NotSignedIn(val tab: LibraryTab) : LibraryEmptyState()
    data class EmptyLibrary(val tab: LibraryTab) : LibraryEmptyState()
    data object FilteredEmpty : LibraryEmptyState()
    data object NoCustomGames : LibraryEmptyState()
}

/**
 * Full-screen empty state shown when a library tab has no content to display.
 * Handles: not signed in, empty library, filters hiding all games, no custom games.
 * Orientation-aware layout with gamepad focus support and subtle glow animation.
 */
@Composable
internal fun LibraryEmptyStateScreen(
    state: LibraryEmptyState,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = resolveEmptyStateConfig(state)
    val orientation = LocalConfiguration.current.orientation
    val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + slideInVertically(
            initialOffsetY = { it / 8 },
            animationSpec = tween(500, easing = EaseOutCubic),
        ),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (isLandscape) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 48.dp),
                ) {
                    GlowIcon(icon = config.icon)
                    Spacer(modifier = Modifier.width(40.dp))
                    TextAndAction(
                        title = config.title,
                        subtitle = config.subtitle,
                        actionLabel = config.actionLabel,
                        onAction = onAction,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    GlowIcon(icon = config.icon)
                    Spacer(modifier = Modifier.height(24.dp))
                    TextAndAction(
                        title = config.title,
                        subtitle = config.subtitle,
                        actionLabel = config.actionLabel,
                        onAction = onAction,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    )
                }
            }
        }
    }
}

@Composable
private fun GlowIcon(icon: ImageVector) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowScale",
    )
    val glowColor = PluviaPurple.copy(alpha = 0.15f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(120.dp)
            .drawBehind {
                val radius = size.maxDimension * 0.5f * pulseScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = PluviaCyan,
            modifier = Modifier.size(64.dp),
        )
    }
}

@Composable
private fun TextAndAction(
    title: String,
    subtitle: String?,
    actionLabel: String,
    onAction: () -> Unit,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "buttonScale",
    )
    val showGamepad = shouldShowGamepadUI()

    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = PluviaForegroundMuted,
                textAlign = if (horizontalAlignment == Alignment.CenterHorizontally) TextAlign.Center else TextAlign.Start,
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onAction,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
            interactionSource = interactionSource,
            modifier = Modifier
                .focusRequester(focusRequester)
                .scale(scale),
        ) {
            Text(actionLabel)
        }
    }

    if (showGamepad) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }
}

/**
 * Resolves the display configuration for a given empty state.
 */
@Composable
private fun resolveEmptyStateConfig(state: LibraryEmptyState): EmptyStateConfig {
    return when (state) {
        is LibraryEmptyState.NotSignedIn -> {
            val tabName = stringResource(state.tab.labelResId)
            EmptyStateConfig(
                icon = Icons.Outlined.AccountCircle,
                title = when (state.tab) {
                    LibraryTab.STEAM -> stringResource(R.string.library_source_not_logged_in_steam)
                    LibraryTab.GOG -> stringResource(R.string.library_source_not_logged_in_gog)
                    LibraryTab.EPIC -> stringResource(R.string.library_source_not_logged_in_epic)
                    LibraryTab.AMAZON -> stringResource(R.string.library_source_not_logged_in_amazon)
                    else -> stringResource(R.string.library_source_not_logged_in_steam)
                },
                subtitle = stringResource(R.string.empty_state_not_signed_in_subtitle),
                actionLabel = when (state.tab) {
                    LibraryTab.STEAM -> stringResource(R.string.steam_sign_in)
                    LibraryTab.GOG -> stringResource(R.string.gog_settings_login_title)
                    LibraryTab.EPIC -> stringResource(R.string.epic_settings_login_title)
                    LibraryTab.AMAZON -> stringResource(R.string.amazon_settings_login_title)
                    else -> tabName
                },
            )
        }
        is LibraryEmptyState.EmptyLibrary -> {
            val tabName = stringResource(state.tab.labelResId)
            EmptyStateConfig(
                icon = Icons.Outlined.SportsEsports,
                title = stringResource(R.string.empty_state_empty_library, tabName),
                subtitle = stringResource(R.string.empty_state_empty_library_subtitle),
                actionLabel = stringResource(R.string.empty_state_empty_library_action),
            )
        }
        is LibraryEmptyState.FilteredEmpty -> EmptyStateConfig(
            icon = Icons.Outlined.FilterListOff,
            title = stringResource(R.string.empty_state_filtered_title),
            subtitle = stringResource(R.string.empty_state_filtered_subtitle),
            actionLabel = stringResource(R.string.empty_state_filtered_action),
        )
        is LibraryEmptyState.NoCustomGames -> EmptyStateConfig(
            icon = Icons.Outlined.CreateNewFolder,
            title = stringResource(R.string.library_source_no_custom_games),
            subtitle = stringResource(R.string.empty_state_custom_subtitle),
            actionLabel = stringResource(R.string.add_custom_game_dialog_title),
        )
    }
}

private data class EmptyStateConfig(
    val icon: ImageVector,
    val title: String,
    val subtitle: String?,
    val actionLabel: String,
)
