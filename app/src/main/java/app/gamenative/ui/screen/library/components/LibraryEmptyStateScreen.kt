package app.gamenative.ui.screen.library.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterListOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.R
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.PluviaBorder
import app.gamenative.ui.theme.PluviaCyan
import app.gamenative.ui.theme.PluviaForegroundMuted
import app.gamenative.ui.theme.PluviaPurple
import app.gamenative.ui.icons.Amazon
import app.gamenative.ui.icons.Steam
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.shouldShowGamepadUI

sealed class LibraryEmptyState {
    data class NotSignedIn(val tab: LibraryTab) : LibraryEmptyState()
    data class EmptyLibrary(val tab: LibraryTab) : LibraryEmptyState()
    data object FilteredEmpty : LibraryEmptyState()
    data object NoCustomGames : LibraryEmptyState()
}

@Composable
internal fun LibraryEmptyStateScreen(
    state: LibraryEmptyState,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val config = resolveEmptyStateConfig(state)

    val isInPreview = LocalInspectionMode.current
    var visible by remember { mutableStateOf(isInPreview) }
    LaunchedEffect(Unit) { visible = true }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PluviaBackground),
        contentAlignment = Alignment.Center,
    ) {
        AmbientBackground()

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(600)) + scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(500),
            ),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(horizontal = 24.dp),
            ) {
                IconContainer(config = config)
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = config.title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (config.subtitle != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = config.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PluviaForegroundMuted,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
                ActionButton(
                    label = config.actionLabel,
                    buttonIcon = config.buttonIcon,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun AmbientBackground() {
    val transition = rememberInfiniteTransition(label = "ambient")
    val orbAX by transition.animateFloat(-0.2f, 0.15f, infiniteRepeatable(tween(8000), RepeatMode.Reverse), label = "aX")
    val orbAY by transition.animateFloat(-0.15f, 0.1f, infiniteRepeatable(tween(10000), RepeatMode.Reverse), label = "aY")
    val orbBX by transition.animateFloat(0.2f, -0.1f, infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "bX")
    val orbBY by transition.animateFloat(0.15f, -0.12f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "bY")

    val purple = PluviaPurple.copy(alpha = 0.08f)
    val cyan = PluviaCyan.copy(alpha = 0.05f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val r = size.minDimension * 0.55f

        val centerA = Offset(cx + size.width * orbAX, cy + size.height * orbAY)
        drawCircle(Brush.radialGradient(listOf(purple, Color.Transparent), center = centerA, radius = r), r, centerA)

        val centerB = Offset(cx + size.width * orbBX, cy + size.height * orbBY)
        drawCircle(Brush.radialGradient(listOf(cyan, Color.Transparent), center = centerB, radius = r), r, centerB)
    }
}

@Composable
private fun IconContainer(config: EmptyStateConfig) {
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(2500), RepeatMode.Reverse), label = "pulse")
    val accent = config.accentColor
    val glow = accent.copy(alpha = 0.2f)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(80.dp)
            .drawBehind {
                val radius = size.maxDimension * 0.7f * pulse
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), radius = radius), radius, center)
            }
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.1f))
            .border(1.dp, accent.copy(alpha = 0.2f), CircleShape),
    ) {
        if (config.drawableResId != null) {
            Icon(painterResource(config.drawableResId), null, Modifier.size(40.dp), accent)
        } else if (config.icon != null) {
            Icon(config.icon, null, Modifier.size(40.dp), accent)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    buttonIcon: ImageVector,
    onAction: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val showGamepad = shouldShowGamepadUI()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "btnScale",
    )

    val gradientBrush = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary),
    )
    val bgAlpha = if (isFocused) 0.25f else 0.15f

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = bgAlpha),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = bgAlpha),
                    ),
                ),
            )
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, gradientBrush, RoundedCornerShape(10.dp))
                } else {
                    Modifier.border(1.dp, PluviaBorder.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                },
            )
            .focusRequester(focusRequester)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onAction,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(buttonIcon, null, Modifier.size(20.dp), Color.White)
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )
        }
    }

    if (showGamepad) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}

@Composable
private fun resolveEmptyStateConfig(state: LibraryEmptyState): EmptyStateConfig = when (state) {
    is LibraryEmptyState.NotSignedIn -> {
        val store = storeIconConfig(state.tab)
        EmptyStateConfig(
            icon = store.icon,
            drawableResId = store.drawableResId,
            accentColor = store.accentColor,
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
                else -> stringResource(state.tab.labelResId)
            },
            buttonIcon = Icons.AutoMirrored.Outlined.Login,
        )
    }

    is LibraryEmptyState.EmptyLibrary -> EmptyStateConfig(
        icon = Icons.Outlined.SportsEsports,
        accentColor = PluviaPurple,
        title = stringResource(R.string.empty_state_empty_library, stringResource(state.tab.labelResId)),
        subtitle = stringResource(R.string.empty_state_empty_library_subtitle),
        actionLabel = stringResource(R.string.empty_state_empty_library_action),
        buttonIcon = Icons.Outlined.Refresh,
    )

    is LibraryEmptyState.FilteredEmpty -> EmptyStateConfig(
        icon = Icons.Outlined.FilterListOff,
        accentColor = PluviaForegroundMuted,
        title = stringResource(R.string.empty_state_filtered_title),
        subtitle = stringResource(R.string.empty_state_filtered_subtitle),
        actionLabel = stringResource(R.string.empty_state_filtered_action),
        buttonIcon = Icons.Outlined.FilterListOff,
    )

    is LibraryEmptyState.NoCustomGames -> EmptyStateConfig(
        icon = Icons.Outlined.FolderOpen,
        accentColor = PluviaCyan,
        title = stringResource(R.string.library_source_no_custom_games),
        subtitle = stringResource(R.string.empty_state_custom_subtitle),
        actionLabel = stringResource(R.string.add_custom_game_dialog_title),
        buttonIcon = Icons.Rounded.Add,
    )
}

private data class StoreIconConfig(
    val icon: ImageVector?,
    @get:DrawableRes val drawableResId: Int?,
    val accentColor: Color,
)

private fun storeIconConfig(tab: LibraryTab): StoreIconConfig = when (tab) {
    LibraryTab.STEAM -> StoreIconConfig(Icons.Filled.Steam, null, PluviaCyan)
    LibraryTab.GOG -> StoreIconConfig(null, R.drawable.ic_gog, PluviaPurple)
    LibraryTab.EPIC -> StoreIconConfig(null, R.drawable.ic_epic, Color(0xFF00D4FF))
    LibraryTab.AMAZON -> StoreIconConfig(Icons.Filled.Amazon, null, Color(0xFFFF9900))
    else -> StoreIconConfig(Icons.Outlined.SportsEsports, null, PluviaCyan)
}

private data class EmptyStateConfig(
    val icon: ImageVector? = null,
    @get:DrawableRes val drawableResId: Int? = null,
    val accentColor: Color,
    val title: String,
    val subtitle: String?,
    val actionLabel: String,
    val buttonIcon: ImageVector,
)

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_NotSignedIn() {
    PluviaTheme {
        LibraryEmptyStateScreen(
            state = LibraryEmptyState.NotSignedIn(LibraryTab.STEAM),
            onAction = {},
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_EmptyLibrary() {
    PluviaTheme {
        LibraryEmptyStateScreen(
            state = LibraryEmptyState.EmptyLibrary(LibraryTab.STEAM),
            onAction = {},
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_FilteredEmpty() {
    PluviaTheme {
        LibraryEmptyStateScreen(
            state = LibraryEmptyState.FilteredEmpty,
            onAction = {},
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun Preview_NoCustomGames() {
    PluviaTheme {
        LibraryEmptyStateScreen(
            state = LibraryEmptyState.NoCustomGames,
            onAction = {},
        )
    }
}
