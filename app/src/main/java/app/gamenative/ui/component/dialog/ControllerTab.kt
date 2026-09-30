package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.container.Container

// Steam Controller (Triton) config is intentionally NOT here — it lives entirely in the in-game QuickMenu so it
// never bleeds into the generic container/controller settings (it matters to almost no one there). See
// XServerScreen's ScLiveEditorDialogs (Manage configs / Bindings / Labels / Tuning / Overlay / Keyboard).
@Composable
fun ControllerTabContent(state: ContainerConfigState, default: Boolean) {
    val config = state.config.value
    var showControllerDebugMenu by remember {
        mutableStateOf(PrefManager.showControllerDebugMenu)
    }

    SettingsGroup() {
        if (!default) {
            SettingsSwitch(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.use_sdl_api)) },
                state = config.sdlControllerAPI,
                onCheckedChange = { state.config.value = config.copy(sdlControllerAPI = it) },
            )
        }
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.use_steam_input)) },
            state = config.useSteamInput,
            onCheckedChange = { state.config.value = config.copy(useSteamInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_xinput_api)) },
            state = config.enableXInput,
            onCheckedChange = { state.config.value = config.copy(enableXInput = it) },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.enable_directinput_api)) },
            state = config.enableDInput,
            onCheckedChange = { state.config.value = config.copy(enableDInput = it) },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.directinput_mapper_type)) },
            value = if (config.dinputMapperType == 1.toByte()) 0 else 1,
            items = listOf("Standard", "XInput Mapper"),
            onItemSelected = { index ->
                state.config.value = config.copy(dinputMapperType = if (index == 0) 1 else 2)
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.show_controller_debug_menu)) },
            subtitle = { Text(text = stringResource(R.string.show_controller_debug_menu_subtitle)) },
            state = showControllerDebugMenu,
            onCheckedChange = {
                showControllerDebugMenu = it
                PrefManager.showControllerDebugMenu = it
            },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.external_display_input)) },
            subtitle = { Text(text = stringResource(R.string.external_display_input_subtitle)) },
            value = state.externalDisplayModeIndex.value,
            items = state.externalDisplayModes,
            onItemSelected = { index ->
                state.externalDisplayModeIndex.value = index
                state.config.value = config.copy(
                    externalDisplayMode = when (index) {
                        1 -> Container.EXTERNAL_DISPLAY_MODE_TOUCHPAD
                        2 -> Container.EXTERNAL_DISPLAY_MODE_KEYBOARD
                        3 -> Container.EXTERNAL_DISPLAY_MODE_HYBRID
                        else -> Container.EXTERNAL_DISPLAY_MODE_OFF
                    },
                )
            },
        )
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.external_display_swap)) },
            subtitle = { Text(text = stringResource(R.string.external_display_swap_subtitle)) },
            state = config.externalDisplaySwap,
            onCheckedChange = { state.config.value = config.copy(externalDisplaySwap = it) },
        )
    }
}
