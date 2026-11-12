package app.gamenative.ui.component.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.UiSoundCues
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults

@Composable
fun SettingsSwitchWithAction(
    state: Boolean,
    title: @Composable (() -> Unit),
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    icon: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsMenuLink(
        title = title,
        modifier = modifier,
        enabled = enabled,
        icon = icon,
        subtitle = subtitle,
        action = {
            Row {
                Switch(
                    enabled = enabled,
                    checked = state,
                    onCheckedChange = if (UiSoundCues.hooksEnabled) {
                        { checked: Boolean ->
                            UiSoundCues.toggle(checked)
                            onCheckedChange(checked)
                        }
                    } else {
                        onCheckedChange
                    },
                    colors = PluviaTheme.switchColors(),
                )
                action?.invoke()
            }
        },
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        onClick = {
            UiSoundCues.toggle(!state)
            onCheckedChange(!state)
        },
    )
}
