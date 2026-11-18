package app.gamenative.ui.component.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import app.gamenative.ui.component.ThemedSwitch
import app.gamenative.ui.component.focusRing
import app.gamenative.ui.component.hasThemedRowFocus
import app.gamenative.ui.component.hasThemedRowFocusFill
import app.gamenative.ui.component.themedFocused
import app.gamenative.ui.component.themedRowFocus
import app.gamenative.ui.component.themedRowFocusContent
import app.gamenative.ui.theme.PluviaTheme
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.base.internal.SettingsTileScaffold as LibrarySettingsTileScaffold

/**
 * Settings tile scaffold. Without a theme focus look it is the library scaffold as is; with one, the focused tile gets
 * the theme focus fill, underline and text color.
 */
@Composable
@NonRestartableComposable
fun SettingsTileScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = SettingsTileDefaults.Elevation,
    shadowElevation: Dp = SettingsTileDefaults.Elevation,
    action: @Composable (() -> Unit)? = null,
) {
    if (!hasThemedRowFocus) {
        LibrarySettingsTileScaffold(
            title = title,
            modifier = modifier,
            enabled = enabled,
            subtitle = subtitle,
            icon = icon,
            colors = colors,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            action = action,
        )
        return
    }
    var focused by remember { mutableStateOf(false) }
    val filled = themedFocused(focused) && hasThemedRowFocusFill
    LibrarySettingsTileScaffold(
        title = title,
        modifier = Modifier
            .onFocusChanged { focused = it.hasFocus }
            .themedRowFocus(focused, base = colors.containerColor)
            .then(modifier),
        enabled = enabled,
        subtitle = subtitle,
        icon = icon,
        colors = if (filled) colors.onFocusFill(themedRowFocusContent(true, colors.titleColor)) else colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        action = action,
    )
}

private fun SettingsTileColors.onFocusFill(content: Color): SettingsTileColors = SettingsTileColors(
    containerColor = Color.Transparent,
    titleColor = content,
    iconColor = content,
    subtitleColor = content.copy(alpha = content.alpha * SUBTITLE_ALPHA),
    actionColor = actionColor,
    disabledTitleColor = content.copy(alpha = content.alpha * SettingsTileDefaults.DisabledAlpha),
    disabledIconColor = content.copy(alpha = content.alpha * SettingsTileDefaults.DisabledAlpha),
    disabledSubtitleColor = content.copy(alpha = content.alpha * SettingsTileDefaults.DisabledAlpha),
    disabledActionColor = disabledActionColor,
)

@Composable
@NonRestartableComposable
fun SettingsMenuLink(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: (@Composable () -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = SettingsTileDefaults.Elevation,
    shadowElevation: Dp = SettingsTileDefaults.Elevation,
    onClick: () -> Unit,
) {
    SettingsTileScaffold(
        modifier = Modifier.settingsTileClickable(enabled, onClick).then(modifier),
        enabled = enabled,
        title = title,
        subtitle = subtitle,
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        action = action,
    )
}

/** Settings switch tile; draws the theme's switch ([PluviaTheme.switchStyle]) and focus look when it has them. */
@Composable
@NonRestartableComposable
fun SettingsSwitch(
    state: Boolean,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = SettingsTileDefaults.Elevation,
    shadowElevation: Dp = SettingsTileDefaults.Elevation,
    onCheckedChange: (Boolean) -> Unit,
) {
    val switchStyle = if (PluviaTheme.isThemed) PluviaTheme.switchStyle else null
    val interactionSource = remember { MutableInteractionSource() }
    SettingsTileScaffold(
        modifier = Modifier
            .toggleable(
                value = state,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = { onCheckedChange(!state) },
            )
            .settingsTileRing(interactionSource)
            .then(modifier),
        enabled = enabled,
        title = title,
        subtitle = subtitle,
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        // the tile toggles as a whole (taps on the switch included); a switch with its own handler would be a second
        // focus target inside the tile, which takes focus without the tile's ring. without a handler the switch drops
        // its touch-target size, so it is kept here and the row height stays the same
        if (switchStyle != null) {
            ThemedSwitch(
                checked = state,
                onCheckedChange = null,
                style = switchStyle,
                modifier = Modifier.minimumInteractiveComponentSize(),
                enabled = enabled,
            )
        } else {
            val track = colors.actionColor(enabled)
            Switch(
                modifier = Modifier.minimumInteractiveComponentSize(),
                enabled = enabled,
                checked = state,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = track,
                    checkedThumbColor = contentColorFor(track),
                    disabledCheckedTrackColor = track,
                    disabledCheckedThumbColor = contentColorFor(track),
                ),
            )
        }
    }
}

/**
 * Click handling for a whole settings tile plus its focus ring. Nested controls in the tile are not D-pad stops of
 * their own (2D focus search skips the children of a focused node), so the tile is the node that rings.
 */
@Composable
internal fun Modifier.settingsTileClickable(enabled: Boolean, onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return clickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        enabled = enabled,
        onClick = onClick,
    ).settingsTileRing(interactionSource)
}

/**
 * The tile's focus ring, drawn on a node inset from the tile's sides so its side edges stay off the screen bezel.
 * The content after it is grown back to the tile's full width, so layout and the touch target are unchanged.
 */
@Composable
private fun Modifier.settingsTileRing(interactionSource: MutableInteractionSource): Modifier =
    padding(horizontal = TileRingInset)
        .focusRing(interactionSource, PluviaTheme.shape(8.dp), width = 2.dp)
        .outsetHorizontal(TileRingInset)

private fun Modifier.outsetHorizontal(outset: Dp): Modifier = layout { measurable, constraints ->
    val px = outset.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = 2 * px))
    layout(placeable.width - 2 * px, placeable.height) { placeable.place(-px, 0) }
}

private val TileRingInset = 6.dp

/**
 * Makes a button in a tile's action slot a D-pad stop: Right on the tile moves to it, Left moves back. [content]
 * draws the tile and puts the given modifier on a wrapper around the button. The tile sits in a plain Box here
 * because a modifier passed into a tile lands after its clickable and would bind to the tile's children instead.
 * With [hasAction] false nothing is wired (Right on a tile must not point at a requester that is not attached).
 */
@Composable
internal fun SettingsTileActionFocus(hasAction: Boolean, content: @Composable (actionModifier: Modifier) -> Unit) {
    if (!hasAction) {
        content(Modifier)
        return
    }
    val tileFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    Box(Modifier.focusRequester(tileFocus).focusProperties { right = actionFocus }) {
        content(Modifier.focusRequester(actionFocus).focusProperties { left = tileFocus })
    }
}

private const val SUBTITLE_ALPHA = 0.8f
