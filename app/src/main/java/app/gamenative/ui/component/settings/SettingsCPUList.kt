package app.gamenative.ui.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.gamenative.R
import com.alorma.compose.settings.ui.base.internal.LocalSettingsGroupEnabled
import com.alorma.compose.settings.ui.base.internal.SettingsTileColors
import com.alorma.compose.settings.ui.base.internal.SettingsTileDefaults
import com.alorma.compose.settings.ui.base.internal.SettingsTileScaffold

@Composable
fun SettingsCPUList(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalSettingsGroupEnabled.current,
    value: String,
    onValueChange: (String) -> Unit,
    title: @Composable () -> Unit,
    icon: (@Composable () -> Unit)? = null,
    colors: SettingsTileColors = SettingsTileDefaults.colors(),
    tonalElevation: Dp = ListItemDefaults.Elevation,
    shadowElevation: Dp = ListItemDefaults.Elevation,
    action: @Composable (() -> Unit)? = null,
) {
    SettingsTileScaffold(
        modifier = modifier,
        enabled = enabled,
        title = title,
        icon = icon,
        colors = colors,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
        action = action,
        subtitle = {
            val cpuAffinity = value.split(",").map { it.toInt() }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                for (cpu in 0 until Runtime.getRuntime().availableProcessors()) {
                    Column {
                        Checkbox(
                            checked = cpuAffinity.contains(cpu),
                            onCheckedChange = {
                                val newAffinity = if (it) {
                                    (cpuAffinity + cpu).sorted()
                                } else {
                                    cpuAffinity.filter { it != cpu }
                                }
                                onValueChange(newAffinity.joinToString(","))
                            },
                        )
                        Text(stringResource(R.string.cpu_label, cpu))
                    }
                }
            }
        },
    )
}
