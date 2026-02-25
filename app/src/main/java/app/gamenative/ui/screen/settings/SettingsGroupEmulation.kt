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
import app.gamenative.ui.component.dialog.Box64PresetsDialog
import app.gamenative.ui.component.dialog.ContainerConfigDialog
import app.gamenative.ui.component.dialog.FEXCorePresetsDialog
import app.gamenative.ui.component.dialog.OrientationDialog
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.utils.ContainerUtils
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsGroupEmulation() {
    SettingsGroup(title = { Text(text = stringResource(R.string.settings_emulation_title)) }) {
        var showConfigDialog by rememberSaveable { mutableStateOf(false) }
        var showOrientationDialog by rememberSaveable { mutableStateOf(false) }
        var showBox64PresetsDialog by rememberSaveable { mutableStateOf(false) }

        OrientationDialog(
            openDialog = showOrientationDialog,
            onDismiss = { showOrientationDialog = false },
        )

        ContainerConfigDialog(
            visible = showConfigDialog,
            title = stringResource(R.string.settings_emulation_default_config_dialog_title),
            default = true,
            initialConfig = ContainerUtils.getDefaultContainerData(),
            onDismissRequest = { showConfigDialog = false },
            onSave = {
                showConfigDialog = false
                ContainerUtils.setDefaultContainerData(it)
            },
        )

        Box64PresetsDialog(
            visible = showBox64PresetsDialog,
            onDismissRequest = { showBox64PresetsDialog = false },
        )
        var showFexcorePresetsDialog by rememberSaveable { mutableStateOf(false) }
        if (showFexcorePresetsDialog) {
            FEXCorePresetsDialog(
                visible = showFexcorePresetsDialog,
                onDismissRequest = { showFexcorePresetsDialog = false },
            )
        }

        var showDriverManager by rememberSaveable { mutableStateOf(false) }
        if (showDriverManager) {
            // Lazy-load dialog composable to avoid cyclic imports
            app.gamenative.ui.screen.settings.DriverManagerDialog(open = showDriverManager, onDismiss = { showDriverManager = false })
        }

        var showContentsManager by rememberSaveable { mutableStateOf(false) }
        if (showContentsManager) {
            app.gamenative.ui.screen.settings.ContentsManagerDialog(open = showContentsManager, onDismiss = { showContentsManager = false })
        }

        var showWineProtonManager by rememberSaveable { mutableStateOf(false) }
        if (showWineProtonManager) {
            app.gamenative.ui.screen.settings.WineProtonManagerDialog(open = showWineProtonManager, onDismiss = { showWineProtonManager = false })
        }

        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_orientations_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_orientations_subtitle)) },
            onClick = { showOrientationDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_default_config_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_default_config_subtitle)) },
            onClick = { showConfigDialog = true },
        )
        var autoApplyKnownConfig by rememberSaveable { mutableStateOf(PrefManager.autoApplyKnownConfig) }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = autoApplyKnownConfig,
            title = { Text(text = stringResource(R.string.settings_emulation_auto_apply_known_config_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_auto_apply_known_config_subtitle)) },
            onCheckedChange = {
                autoApplyKnownConfig = it
                PrefManager.autoApplyKnownConfig = it
            },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_box64_presets_title)) },
            subtitle = { Text(stringResource(R.string.settings_emulation_box64_presets_subtitle)) },
            onClick = { showBox64PresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.fexcore_presets)) },
            subtitle = { Text(text = stringResource(R.string.fexcore_presets_description)) },
            onClick = { showFexcorePresetsDialog = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_driver_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_driver_manager_subtitle)) },
            onClick = { showDriverManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_contents_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_contents_manager_subtitle)) },
            onClick = { showContentsManager = true },
        )
        SettingsMenuLink(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_title)) },
            subtitle = { Text(text = stringResource(R.string.settings_emulation_wine_proton_manager_subtitle)) },
            onClick = { showWineProtonManager = true },
        )
    }
}
