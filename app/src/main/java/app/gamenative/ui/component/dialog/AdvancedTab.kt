package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsCPUList
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.container.Container

@Composable
fun AdvancedTabContent(state: ContainerConfigState) {
    val config = state.config.value
    val suspendBehaviorEntries = listOf(
        stringResource(R.string.suspend_behavior_default),
        stringResource(R.string.suspend_behavior_never),
        stringResource(R.string.suspend_behavior_manual),
    )
    val suspendBehaviorIndex = when {
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true) -> 1
        config.suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true) -> 2
        else -> 0
    }

    SettingsGroup() {
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.startup_selection)) },
            value = config.startupSelection.toInt().takeIf { it in state.getStartupSelectionOptions().indices } ?: 1,
            items = state.getStartupSelectionOptions(),
            onItemSelected = {
                state.config.value = config.copy(startupSelection = it.toByte())
            },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.suspend_behavior)) },
            value = suspendBehaviorIndex,
            items = suspendBehaviorEntries,
            onItemSelected = { index ->
                val policy = when (index) {
                    1 -> Container.SUSPEND_POLICY_NEVER
                    2 -> Container.SUSPEND_POLICY_MANUAL
                    else -> Container.SUSPEND_POLICY_DEFAULT
                }
                state.config.value = config.copy(suspendPolicy = policy)
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity)) },
            value = config.cpuList,
            onValueChange = {
                state.config.value = config.copy(cpuList = it)
            },
        )
        SettingsCPUList(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.processor_affinity_32bit)) },
            value = config.cpuListWoW64,
            onValueChange = { state.config.value = config.copy(cpuListWoW64 = it) },
        )
    }
}
