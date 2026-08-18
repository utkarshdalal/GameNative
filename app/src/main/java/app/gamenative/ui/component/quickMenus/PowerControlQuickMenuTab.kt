package app.gamenative.ui.component.quickMenus

import androidx.compose.foundation.focusGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.powercontrol.CpuDisplayInfo
import app.gamenative.powercontrol.GpuDisplayInfo
import app.gamenative.powercontrol.PowerControlUiState
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.RamDisplayInfo
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
fun PowerControlQuickMenuTab(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val uiState by PowerManager.uiState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val isDriverSupported = remember { PowerManager.isDriverSupported() }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            PowerManager.refreshUiState()
        }
    }

    PowerControlQuickMenuContent(
        uiState = uiState,
        isDriverSupported = isDriverSupported,
        onFanControlToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableFanControl = enabled))
                }
                PowerManager.refreshUiState()
            }
        },
        onGamePinningToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableGamePinning = enabled))
                }
                PowerManager.refreshUiState()
            }
        },
        onTuningModeSelected = { perCluster ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enablePerClusterTuning = perCluster))
                }
                PowerManager.refreshUiState()
            }
        },
        onAdaptiveFpsCapToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableAdaptiveFpsCap = enabled))
                }
                PowerManager.refreshUiState()
            }
        },
        onAutoTuningToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                // Update current profile
                PowerManager.currentProfile?.let { profile ->
                    val updatedProfile = profile.copy(
                        enableAutoTuning = enabled,
                        name = PerformancePreset.CUSTOM.displayName
                    )
                    PowerManager.setCurrentProfile(updatedProfile)
                }

                PowerManager.refreshUiState()
            }
        },
        onTuningStrategySelected = { strategy ->
            coroutineScope.launch(Dispatchers.IO) {
                // Update current profile with new tuning strategy
                PowerManager.currentProfile?.let { profile ->
                    val updatedProfile = profile.copy(
                        tuningStrategy = strategy,
                        name = PerformancePreset.CUSTOM.displayName
                    )
                    PowerManager.setCurrentProfile(updatedProfile)
                }

                PowerManager.refreshUiState()
            }
        },
        onProfileSelected = { profile ->
            coroutineScope.launch(Dispatchers.IO) {
                Timber.d("Applying profile: $profile")

                // Update PowerManager's current profile reference immediately
                // Preserve current enableFanControl and enableGamePinning settings
                val currentProfile = PowerManager.currentProfile
                val updatedProfile = profile.copy(
                    enableAdaptiveFpsCap = currentProfile?.enableAdaptiveFpsCap ?: profile.enableAdaptiveFpsCap,
                    enableAutoTuning = false,
                    enablePerClusterTuning = false,
                    enableFanControl = currentProfile?.enableFanControl ?: profile.enableFanControl,
                    enableGamePinning = currentProfile?.enableGamePinning ?: profile.enableGamePinning,
                )
                PowerManager.setCurrentProfile(updatedProfile)

                val success = PowerManager.update {
                    name(updatedProfile.name)
                    governor(updatedProfile.governor.governorName)
                    minCpuValue(updatedProfile.minCpuFreq)
                    maxCpuValue(updatedProfile.maxCpuFreq)
                    if (PowerManager.isGpuSupported()) {
                        minGpuPowerLevel(updatedProfile.minGpuPowerLevel)
                        maxGpuPowerLevel(updatedProfile.maxGpuPowerLevel)
                    }
                    if (PowerManager.isBusSupported()) {
                        minBusLevel(updatedProfile.minBusLevel)
                        maxBusLevel(updatedProfile.maxBusLevel)
                    }
                }

                Timber.d("Profile application result: $success")
                PowerManager.refreshUiState()
            }
        },
        onGovernorSelected = { governor ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setGovernor(governor)
                PowerManager.refreshUiState()
            }
        },
        onMinCpuValueChanged = { freqIndex ->
            if (uiState is PowerControlUiState.Success) {
                val freq = (uiState as PowerControlUiState.Success).cpuInfo.availableFrequencies[freqIndex]
                coroutineScope.launch(Dispatchers.IO) {
                    PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                    PowerManager.setMinCpuValue(freq)
                    PowerManager.refreshUiState()
                }
            }
        },
        onMaxCpuValueChanged = { freqIndex ->
            if (uiState is PowerControlUiState.Success) {
                val freq = (uiState as PowerControlUiState.Success).cpuInfo.availableFrequencies[freqIndex]
                coroutineScope.launch(Dispatchers.IO) {
                    PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                    PowerManager.setMaxCpuValue(freq)
                    PowerManager.refreshUiState()
                }
            }
        },
        onMinGpuPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMinGpuPowerLevel(powerLevel)
                PowerManager.refreshUiState()
            }
        },
        onMaxGpuPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMaxGpuPowerLevel(powerLevel)
                PowerManager.refreshUiState()
            }
        },
        onMinRamValueChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMinBusLevel(powerLevel)
                PowerManager.refreshUiState()
            }
        },
        onMaxRamValueChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMaxBusLevel(powerLevel)
                PowerManager.refreshUiState()
            }
        },
        firstItemFocusRequester = focusRequester,
        modifier = modifier.focusGroup(),
    )
}

@Preview(showBackground = true, name = "Loading State")
@Composable
fun PowerControlLoadingPreview() {
    MaterialTheme {
        PowerControlQuickMenuContent(
            uiState = PowerControlUiState.Loading,
            isDriverSupported = true,
            onAutoTuningToggled = {},
            onTuningStrategySelected = {},
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinCpuValueChanged = {},
            onMaxCpuValueChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamValueChanged = {},
            onMaxRamValueChanged = {},
        )
    }
}

@Preview(showBackground = true, name = "Success - CPU Only")
@Composable
fun PowerControlSuccessCpuOnlyPreview() {
    MaterialTheme {
        PowerControlQuickMenuContent(
            uiState = PowerControlUiState.Success(
                cpuInfo = CpuDisplayInfo(
                    currentGovernor = "performance",
                    availableGovernors = listOf("performance", "powersave", "ondemand", "schedutil"),
                    availableFrequencies = listOf(300000, 825000, 1400000, 1800000, 2200000, 2800000),
                    currentMinValue = 300000,
                    currentMaxValue = 2800000,
                    selectedMinFreqIndex = 0,
                    selectedMaxFreqIndex = 5
                ),
                gpuInfo = null,
                selectedProfile = PowerProfile(
                    name = PerformancePreset.PERFORMANCE.displayName,
                    governor = CpuGovernor.PERFORMANCE,
                    minCpuFreq = 300000,
                    maxCpuFreq = 2800000
                ),
                availableProfiles = listOf(
                    PowerProfile(
                        name = PerformancePreset.PERFORMANCE.displayName,
                        governor = CpuGovernor.PERFORMANCE,
                        minCpuFreq = 300000,
                        maxCpuFreq = 2800000
                    ),
                    PowerProfile(
                        name = PerformancePreset.BALANCED.displayName,
                        governor = CpuGovernor.SCHEDUTIL,
                        minCpuFreq = 300000,
                        maxCpuFreq = 2200000
                    ),
                    PowerProfile(
                        name = PerformancePreset.POWER_SAVE.displayName,
                        governor = CpuGovernor.POWERSAVE,
                        minCpuFreq = 300000,
                        maxCpuFreq = 1400000
                    )
                ),
                ramInfo = null
            ),
            isDriverSupported = true,
            onAutoTuningToggled = {},
            onTuningStrategySelected = {},
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinCpuValueChanged = {},
            onMaxCpuValueChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamValueChanged = {},
            onMaxRamValueChanged = {},
        )
    }
}

@Preview(showBackground = true, name = "Success - With GPU")
@Composable
fun PowerControlSuccessWithGpuPreview() {
    MaterialTheme {
        PowerControlQuickMenuContent(
            uiState = PowerControlUiState.Success(
                cpuInfo = CpuDisplayInfo(
                    currentGovernor = "schedutil",
                    availableGovernors = listOf("performance", "powersave", "ondemand", "schedutil"),
                    availableFrequencies = listOf(300000, 825000, 1400000, 1800000, 2200000, 2800000),
                    currentMinValue = 300000,
                    currentMaxValue = 2200000,
                    selectedMinFreqIndex = 0,
                    selectedMaxFreqIndex = 4
                ),
                gpuInfo = GpuDisplayInfo(
                    availableFrequencies = listOf(180000000, 305000000, 427000000, 587000000, 710000000),
                    currentFreqIndex = 3,
                    minPowerLevel = 0,
                    maxPowerLevel = 4,
                    maxAvailablePowerLevel = 4
                ),
                selectedProfile = PowerProfile(
                    name = PerformancePreset.BALANCED.displayName,
                    governor = CpuGovernor.SCHEDUTIL,
                    minCpuFreq = 300000,
                    maxCpuFreq = 2200000
                ),
                availableProfiles = listOf(
                    PowerProfile(
                        name = PerformancePreset.PERFORMANCE.displayName,
                        governor = CpuGovernor.PERFORMANCE,
                        minCpuFreq = 300000,
                        maxCpuFreq = 2800000
                    ),
                    PowerProfile(
                        name = PerformancePreset.BALANCED.displayName,
                        governor = CpuGovernor.SCHEDUTIL,
                        minCpuFreq = 300000,
                        maxCpuFreq = 2200000
                    )
                ),
                ramInfo = RamDisplayInfo(
                    minBusLevel = 0,
                    maxBusLevel = 4,
                    maxAvailableBusLevel = 4
                ),
            ),
            isDriverSupported = true,
            onAutoTuningToggled = {},
            onTuningStrategySelected = {},
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinCpuValueChanged = {},
            onMaxCpuValueChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamValueChanged = {},
            onMaxRamValueChanged = {},
        )
    }
}
