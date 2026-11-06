package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupPerformance() {
    SettingsGroup {
        var powerControlDefaultEnabled by rememberSaveable { mutableStateOf(PrefManager.powerControlDefaultEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = powerControlDefaultEnabled,
            title = { Text(stringResource(R.string.settings_performance_power_control_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_power_control_subtitle)) },
            onCheckedChange = {
                powerControlDefaultEnabled = it
                PrefManager.powerControlDefaultEnabled = it
            },
        )
        var sharedShaderCache by rememberSaveable { mutableStateOf(PrefManager.sharedShaderCache) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = sharedShaderCache,
            title = { Text(stringResource(R.string.settings_performance_shared_shader_cache_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_shared_shader_cache_subtitle)) },
            onCheckedChange = {
                sharedShaderCache = it
                PrefManager.sharedShaderCache = it
            },
        )
    }
}
