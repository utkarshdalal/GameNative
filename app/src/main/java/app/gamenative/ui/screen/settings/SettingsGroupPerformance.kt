package app.gamenative.ui.screen.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.ui.component.dialog.LosslessScalingDialog
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.LsfgVkManager
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.renderer.lsfg.LosslessScaling

@Composable
fun SettingsGroupPerformance() {
    SettingsGroup {
        val context = LocalContext.current
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

        var refreshKey by remember { mutableIntStateOf(0) }
        var showLsfgDialog by rememberSaveable { mutableStateOf(false) }
        val isLoggedIn = SteamService.isLoggedIn
        val ownsApp = LsfgVkManager.ownsLosslessScaling()
        val isDllImported = remember(refreshKey) { LosslessScaling.getDllFile(context).isFile }

        val subtitleText = when {
            !isLoggedIn -> stringResource(R.string.library_source_not_logged_in_steam)
            !ownsApp -> stringResource(R.string.lsfg_not_in_library)
            isDllImported -> stringResource(R.string.settings_lsfg_installed)
            else -> stringResource(R.string.settings_lsfg_not_installed)
        }

        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(stringResource(R.string.settings_lsfg_title)) },
            subtitle = { Text(subtitleText) },
            onClick = { showLsfgDialog = true },
        )

        LosslessScalingDialog(
            openDialog = showLsfgDialog,
            onDismiss = {
                showLsfgDialog = false
                refreshKey++
            },
            onInstallSuccess = {
                refreshKey++
            },
        )
    }
}
