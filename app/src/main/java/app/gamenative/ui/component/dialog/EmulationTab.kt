package app.gamenative.ui.component.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.winlator.contents.ContentProfile
import com.winlator.container.Container
import com.winlator.core.StringUtils
import java.util.Locale

@Composable
fun EmulationTabContent(state: ContainerConfigState) {
    val config = state.config.value
    val wineIsX8664 = config.wineVersion.contains("x86_64", true)
    val wineIsArm64Ec = config.wineVersion.contains("arm64ec", true)

    SettingsGroup() {
        if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)) {
            if (wineIsArm64Ec) {
                SettingsGroup() {
                    val fexcoreIndex = state.fexcoreOptions.ids.indexOfFirst { it == config.fexcoreVersion }.coerceAtLeast(0)
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(text = stringResource(R.string.fexcore_version)) },
                        value = fexcoreIndex,
                        items = state.fexcoreOptions.labels,
                        itemMuted = state.fexcoreOptions.muted,
                        onItemSelected = { idx ->
                            val selectedId = state.fexcoreOptions.ids.getOrNull(idx).orEmpty()
                            val isManifestNotInstalled = state.fexcoreOptions.muted.getOrNull(idx) == true
                            val manifestEntry = state.fexcoreManifestById[selectedId]
                            if (isManifestNotInstalled && manifestEntry != null) {
                                state.launchManifestContentInstall(
                                    manifestEntry,
                                    ContentProfile.ContentType.CONTENT_TYPE_FEXCORE,
                                ) {
                                    state.config.value = config.copy(fexcoreVersion = selectedId)
                                }
                                return@SettingsListDropdown
                            }
                            state.config.value = config.copy(fexcoreVersion = selectedId.ifEmpty { state.fexcoreOptions.labels[idx] })
                        },
                    )
                }
            }

            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.emulator_64bit)) },
                value = state.emulator64Index.value,
                items = state.emulatorEntries,
                enabled = false,
                onItemSelected = { },
            )
            LaunchedEffect(wineIsX8664, wineIsArm64Ec) {
                state.emulator64Index.value = if (wineIsX8664) 1 else 0
            }

            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.emulator_32bit)) },
                value = state.emulator32Index.value,
                items = state.emulatorEntries,
                enabled = when {
                    wineIsX8664 -> false
                    wineIsArm64Ec -> true
                    else -> true
                },
                onItemSelected = { idx ->
                    state.emulator32Index.value = idx
                    state.config.value = config.copy(emulator = state.emulatorEntries[idx])
                },
            )
            LaunchedEffect(wineIsX8664) {
                if (wineIsX8664) {
                    state.emulator32Index.value = 1
                    if (config.emulator != state.emulatorEntries[1]) {
                        state.config.value = config.copy(emulator = state.emulatorEntries[1])
                    }
                }
            }
            LaunchedEffect(wineIsArm64Ec) {
                if (wineIsArm64Ec) {
                    if (state.emulator32Index.value !in 0..1) state.emulator32Index.value = 0
                    if (config.emulator.isEmpty()) {
                        state.config.value = config.copy(emulator = state.emulatorEntries[0])
                    }
                }
            }
        }
        val box64OptionList = state.getVersionsForBox64()
        val box64Index = box64OptionList.ids.indexOfFirst { it == config.box64Version }.coerceAtLeast(0)
        val box64ManifestMap = if (config.wineVersion.contains("arm64ec", true)) {
            state.wowBox64ManifestById
        } else {
            state.box64ManifestById
        }
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.box64_version)) },
            value = box64Index,
            items = box64OptionList.labels,
            itemMuted = box64OptionList.muted,
            onItemSelected = { idx ->
                val selectedId = box64OptionList.ids.getOrNull(idx).orEmpty()
                val isManifestNotInstalled = box64OptionList.muted.getOrNull(idx) == true
                val manifestEntry = box64ManifestMap[selectedId.lowercase(Locale.ENGLISH)]
                if (isManifestNotInstalled && manifestEntry != null) {
                    val expectedType = if (config.wineVersion.contains("arm64ec", true)) {
                        ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64
                    } else {
                        ContentProfile.ContentType.CONTENT_TYPE_BOX64
                    }
                    state.launchManifestContentInstall(manifestEntry, expectedType) {
                        state.config.value = config.copy(box64Version = selectedId)
                    }
                    return@SettingsListDropdown
                }
                state.config.value = config.copy(box64Version = selectedId.ifEmpty { StringUtils.parseIdentifier(box64OptionList.labels[idx]) })
            },
        )
        SettingsListDropdown(
            colors = settingsTileColors(),
            title = { Text(text = stringResource(R.string.box64_preset)) },
            value = state.box64Presets.indexOfFirst { it.id == config.box64Preset }.coerceAtLeast(0),
            items = state.box64Presets.map { it.name },
            onItemSelected = {
                state.config.value = config.copy(box64Preset = state.box64Presets[it].id)
            },
        )
        if (config.containerVariant.equals(Container.BIONIC, ignoreCase = true)
            && config.wineVersion.contains("arm64ec", ignoreCase = true)) {
            SettingsListDropdown(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.fexcore_preset)) },
                value = state.fexcorePresets.indexOfFirst { it.id == config.fexcorePreset }.coerceAtLeast(0),
                items = state.fexcorePresets.map { it.name },
                onItemSelected = {
                    state.config.value = config.copy(fexcorePreset = state.fexcorePresets[it].id)
                },
            )
        }
    }
}
