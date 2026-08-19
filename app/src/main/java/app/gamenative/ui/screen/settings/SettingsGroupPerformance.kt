package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.powercontrol.PowerManager
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupPerformance() {
    SettingsGroup {
        val context = LocalContext.current
        var powerControlEnabled by rememberSaveable { mutableStateOf(PrefManager.powerControlEnabled) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = powerControlEnabled,
            title = { Text(stringResource(R.string.settings_performance_power_control_title)) },
            subtitle = { Text(stringResource(R.string.settings_performance_power_control_subtitle)) },
            onCheckedChange = {
                powerControlEnabled = it
                PrefManager.powerControlEnabled = it
                if (powerControlEnabled) {
                    PowerManager.initialize(context)
                } else {
                    PowerManager.deinitialize()
                }
            },
        )
    }
}
