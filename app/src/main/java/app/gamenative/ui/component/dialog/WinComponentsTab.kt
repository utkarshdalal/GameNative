package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.core.KeyValueSet

@Composable
fun WinComponentsTabContent(state: ContainerConfigState) {
    val config = state.config.value
    SettingsGroup() {
        for (wincomponent in KeyValueSet(config.wincomponents)) {
            val compId = wincomponent[0]
            val compNameRes = winComponentsItemTitleRes(compId)
            val compValue = wincomponent[1].toInt()
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(stringResource(id = compNameRes)) },
                subtitle = { Text(if (compId.startsWith("direct")) "DirectX" else "General") },
                value = compValue,
                items = state.winCompOpts,
                onItemSelected = {
                    state.config.value = config.copy(
                        wincomponents = config.wincomponents.replace("$compId=$compValue", "$compId=$it"),
                    )
                },
            )
        }
    }
}
