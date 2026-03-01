package app.gamenative.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
}

data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

@Composable
fun QuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Unit,
    hasPhysicalController: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val menuItems = buildList {
        add(QuickMenuItem(
            id = QuickMenuAction.KEYBOARD,
            icon = Icons.Default.Keyboard,
            labelResId = R.string.keyboard,
            accentColor = PluviaTheme.colors.accentCyan,
        ))
        add(QuickMenuItem(
            id = QuickMenuAction.INPUT_CONTROLS,
            icon = Icons.Default.TouchApp,
            labelResId = R.string.input_controls,
            accentColor = PluviaTheme.colors.accentPurple,
        ))
        add(QuickMenuItem(
            id = QuickMenuAction.EDIT_CONTROLS,
            icon = Icons.Default.Edit,
            labelResId = R.string.edit_controls,
            accentColor = PluviaTheme.colors.accentSuccess,
        ))
        if (hasPhysicalController) {
            add(QuickMenuItem(
                id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                icon = Icons.Default.Gamepad,
                labelResId = R.string.edit_physical_controller,
                accentColor = PluviaTheme.colors.accentWarning,
            ))
        }
        add(QuickMenuItem(
            id = QuickMenuAction.EXIT_GAME,
            icon = Icons.AutoMirrored.Filled.ExitToApp,
            labelResId = R.string.exit_game,
            accentColor = PluviaTheme.colors.accentDanger,
        ))
    }

    val firstItemFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        AnimatedVisibility(
            visible = isVisible,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Surface(
                modifier = Modifier
                    .width(adaptivePanelWidth(280.dp))
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.quick_menu_back),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .focusGroup()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        menuItems.forEachIndexed { index, item ->
                            QuickMenuItemRow(
                                item = item,
                                onClick = {
                                    onItemSelected(item.id)
                                    onDismiss()
                                },
                                focusRequester = if (index == 0) firstItemFocusRequester else null,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
                // Focus request may fail if composition is not ready
            }
        }
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (isFocused && isEnabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.05f),
                            )
                        )
                    )
                } else Modifier
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else Modifier
            )
            .selectable(
                selected = isFocused,
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFocused -> accentColor.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    isFocused -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
        }

        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = if (isFocused && isEnabled) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = {},
                hasPhysicalController = false,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu_WithController() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = {},
                hasPhysicalController = true,
            )
        }
    }
}
