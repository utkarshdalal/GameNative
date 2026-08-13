package app.gamenative.ui.component.quickMenus

import androidx.compose.foundation.focusGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import app.gamenative.powercontrol.PowerProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

sealed class PowerControlUiState {
    object Loading : PowerControlUiState()
    data class Success(
        val cpuInfo: CpuDisplayInfo,
        val gpuInfo: GpuDisplayInfo?,
        val ramInfo: RamDisplayInfo?,
        val selectedProfile: PowerProfile,
        val availableProfiles: List<PowerProfile>
    ) : PowerControlUiState()
}

data class CpuDisplayInfo(
    val currentGovernor: String,
    val availableGovernors: List<String>,
    val availableFrequencies: List<Long>,
    val currentMinValue: Long,
    val currentMaxValue: Long,
    val selectedMinFreqIndex: Int,
    val selectedMaxFreqIndex: Int
)

data class GpuDisplayInfo(
    val availableFrequencies: List<Long>,
    val currentFreqIndex: Int,
    val minPowerLevel: Int,
    val maxPowerLevel: Int,
    val maxAvailablePowerLevel: Int
)

data class RamDisplayInfo(
    val minBusLevel: Int,
    val maxBusLevel: Int,
    val maxAvailableBusLevel: Int
)

@Composable
fun PowerControlQuickMenuTab(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val uiState by rememberPowerControlState(refreshTrigger)
    val coroutineScope = rememberCoroutineScope()

    PowerControlQuickMenuContent(
        uiState = uiState,
        onFanControlToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableFanControl = enabled))
                }
                refreshTrigger++
            }
        },
        onGamePinningToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableGamePinning = enabled))
                }
                refreshTrigger++
            }
        },
        onTuningModeSelected = { perCluster ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enablePerClusterTuning = perCluster))
                }
                refreshTrigger++
            }
        },
        onAdaptiveFpsCapToggled = { enabled ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.currentProfile?.let { profile ->
                    PowerManager.setCurrentProfile(profile.copy(enableAdaptiveFpsCap = enabled))
                }
                refreshTrigger++
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

                refreshTrigger++
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

                refreshTrigger++
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
                refreshTrigger++
            }
        },
        onGovernorSelected = { governor ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setGovernor(governor)
                refreshTrigger++
            }
        },
        onMinCpuValueChanged = { freqIndex ->
            if (uiState is PowerControlUiState.Success) {
                val freq = (uiState as PowerControlUiState.Success).cpuInfo.availableFrequencies[freqIndex]
                coroutineScope.launch(Dispatchers.IO) {
                    PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                    PowerManager.setMinCpuValue(freq)
                    refreshTrigger++
                }
            }
        },
        onMaxCpuValueChanged = { freqIndex ->
            if (uiState is PowerControlUiState.Success) {
                val freq = (uiState as PowerControlUiState.Success).cpuInfo.availableFrequencies[freqIndex]
                coroutineScope.launch(Dispatchers.IO) {
                    PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                    PowerManager.setMaxCpuValue(freq)
                    refreshTrigger++
                }
            }
        },
        onMinGpuPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMinGpuPowerLevel(powerLevel)
                refreshTrigger++
            }
        },
        onMaxGpuPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMaxGpuPowerLevel(powerLevel)
                refreshTrigger++
            }
        },
        onMinRamValueChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMinBusLevel(powerLevel)
                refreshTrigger++
            }
        },
        onMaxRamValueChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMaxBusLevel(powerLevel)
                refreshTrigger++
            }
        },
        firstItemFocusRequester = focusRequester,
        modifier = modifier.focusGroup(),
    )
}

@Composable
private fun rememberPowerControlState(refreshTrigger: Int): State<PowerControlUiState> {
    var uiState by remember { mutableStateOf<PowerControlUiState>(PowerControlUiState.Loading) }
    var selectedProfile by remember {
        mutableStateOf(
            PowerProfile(
                name = PerformancePreset.CUSTOM.displayName,
                governor = CpuGovernor.SCHEDUTIL,
                minCpuFreq = 0,
                maxCpuFreq = 0,
                minGpuPowerLevel = 0,
                maxGpuPowerLevel = 0,
                minBusLevel = 0,
                maxBusLevel = 0,
            )
        )
    }
    var isInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            try {
                val hasGpuSupport = PowerManager.isGpuSupported()
                val hasBusSupport = PowerManager.isBusSupported()

                val cpuInfo = PowerManager.getCpuInfo()
                if (cpuInfo != null) {
                    val availableGovernors = PowerManager.getAvailableGovernors()
                    val availableFrequencies = PowerManager.getAvailableCpuFrequencies()

                    val gpuDisplayInfo = if (hasGpuSupport) {
                        val gpuInfo = PowerManager.getGpuInfo()
                        val availableGpuFrequencies = PowerManager.getAvailableGpuFrequencies()
                        if (gpuInfo != null) {
                            val maxGpuPowerLevel = if (gpuInfo.numGpuPowerLevels > 0) {
                                gpuInfo.numGpuPowerLevels - 1
                            } else 0
                            val currentFreqIndex = if (availableGpuFrequencies.isNotEmpty()) {
                                availableGpuFrequencies.indexOfFirst {
                                    it >= gpuInfo.currentGpuValue
                                }.coerceAtLeast(0)
                            } else {
                                0
                            }
                            GpuDisplayInfo(
                                availableFrequencies = availableGpuFrequencies,
                                currentFreqIndex = currentFreqIndex,
                                minPowerLevel = gpuInfo.minGpuPowerLevel,
                                maxPowerLevel = gpuInfo.maxGpuPowerLevel,
                                maxAvailablePowerLevel = maxGpuPowerLevel
                            )
                        } else null
                    } else null

                    val ramDisplayInfo = if (hasBusSupport) {
                        val busInfo = PowerManager.getBusInfo()

                        if (busInfo != null && busInfo.numBusLevels > 0) {
                            RamDisplayInfo(
                                minBusLevel = busInfo.minBusLevel,
                                maxBusLevel = busInfo.maxBusLevel,
                                maxAvailableBusLevel = busInfo.numBusLevels - 1
                            )
                        } else {
                            null
                        }
                    } else {
                        null
                    }

                    val selectedMinFreqIndex = availableFrequencies.indexOfFirst {
                        it >= cpuInfo.currentMinValue
                    }.coerceAtLeast(0)
                    val selectedMaxFreqIndex = availableFrequencies.indexOfFirst {
                        it >= cpuInfo.currentMaxValue
                    }.coerceAtLeast(0)

                    val maxGpuPowerLevel = gpuDisplayInfo?.maxAvailablePowerLevel ?: 0
                    val profiles = PowerProfiles.getDefaultProfiles(availableGovernors, availableFrequencies, maxGpuPowerLevel)

                    val currentGovernor = CpuGovernor.fromString(cpuInfo.currentGovernor)

                    Timber.d("Current profile: $selectedProfile")
                    profiles.forEach { profile ->
                        Timber.d("Profile $profile")
                    }

                    // Try to match current settings against available profiles
                    // Match by PowerManager's current profile name
                    val matchingProfile = profiles.find { profile ->
                        profile.name == (PowerManager.currentProfile?.name ?: PerformancePreset.CUSTOM.displayName)
                    }

                    Timber.d("Matching profile: $matchingProfile")

                    selectedProfile = (matchingProfile ?: PowerProfile(
                        name = PerformancePreset.CUSTOM.displayName,
                        governor = currentGovernor ?: CpuGovernor.SCHEDUTIL,
                        minCpuFreq = cpuInfo.currentMinValue,
                        maxCpuFreq = cpuInfo.currentMaxValue,
                        minGpuPowerLevel = gpuDisplayInfo?.minPowerLevel ?: 0,
                        maxGpuPowerLevel = gpuDisplayInfo?.maxPowerLevel ?: 0,
                        minBusLevel = ramDisplayInfo?.minBusLevel ?: 0,
                        maxBusLevel = ramDisplayInfo?.maxBusLevel ?: 0
                    )).copy(
                        // Preserve enableAutoTuning and tuningStrategy from PowerManager's current profile
                        enableAutoTuning = PowerManager.currentProfile?.enableAutoTuning ?: true,
                        enablePerClusterTuning = PowerManager.currentProfile?.enablePerClusterTuning ?: true,
                        enableAdaptiveFpsCap = PowerManager.currentProfile?.enableAdaptiveFpsCap ?: true,
                        enableFanControl = PowerManager.currentProfile?.enableFanControl ?: true,
                        enableGamePinning = PowerManager.currentProfile?.enableGamePinning ?: false,
                        tuningStrategy = PowerManager.currentProfile?.tuningStrategy ?: AutoTuningStrategy.POWER_EFFICIENT
                    )

                    if (!isInitialized) {
                        isInitialized = true
                    }

                    uiState = PowerControlUiState.Success(
                        cpuInfo = CpuDisplayInfo(
                            currentGovernor = cpuInfo.currentGovernor,
                            availableGovernors = availableGovernors,
                            availableFrequencies = availableFrequencies,
                            currentMinValue = cpuInfo.currentMinValue,
                            currentMaxValue = cpuInfo.currentMaxValue,
                            selectedMinFreqIndex = selectedMinFreqIndex,
                            selectedMaxFreqIndex = selectedMaxFreqIndex
                        ),
                        gpuInfo = gpuDisplayInfo,
                        selectedProfile = selectedProfile,
                        availableProfiles = profiles,
                        ramInfo = ramDisplayInfo,
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    return remember { derivedStateOf { uiState } }
}

@Preview(showBackground = true, name = "Loading State")
@Composable
fun PowerControlLoadingPreview() {
    MaterialTheme {
        PowerControlQuickMenuContent(
            uiState = PowerControlUiState.Loading,
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
