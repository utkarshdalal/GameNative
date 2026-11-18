package app.gamenative.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import app.gamenative.ui.component.ThemedSwitch
import app.gamenative.ui.component.hasThemedRowFocus
import app.gamenative.ui.component.hasThemedRowFocusFill
import app.gamenative.ui.component.themedRowFocus
import app.gamenative.ui.component.themedRowFocusContent
import app.gamenative.ui.theme.PluviaTheme
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.SettingsMenuLink as LibrarySettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch as LibrarySettingsSwitch
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
    val filled = focused && hasThemedRowFocusFill
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
    if (!hasThemedRowFocus) {
        LibrarySettingsMenuLink(
            title = title,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            subtitle = subtitle,
            action = action,
            colors = colors,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            onClick = onClick,
        )
        return
    }
    SettingsTileScaffold(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick).then(modifier),
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
    if (switchStyle == null && !hasThemedRowFocus) {
        LibrarySettingsSwitch(
            state = state,
            title = title,
            modifier = modifier,
            enabled = enabled,
            icon = icon,
            subtitle = subtitle,
            colors = colors,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            onCheckedChange = onCheckedChange,
        )
        return
    }
    SettingsTileScaffold(
        modifier = Modifier
            .toggleable(
                enabled = enabled,
                value = state,
                role = Role.Switch,
                onValueChange = { onCheckedChange(!state) },
            ).then(modifier),
        enabled = enabled,
        title = title,
        subtitle = subtitle,
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        if (switchStyle != null) {
            ThemedSwitch(checked = state, onCheckedChange = onCheckedChange, style = switchStyle, enabled = enabled)
        } else {
            val track = colors.actionColor(enabled)
            Switch(
                enabled = enabled,
                checked = state,
                onCheckedChange = onCheckedChange,
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

private const val SUBTITLE_ALPHA = 0.8f
