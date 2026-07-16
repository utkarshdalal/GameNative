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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.tooling.preview.Preview
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import app.gamenative.powercontrol.PowerProfiles
import kotlinx.coroutines.Dispatchers
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
    val minPowerLevel: Int,
    val maxPowerLevel: Int,
    val maxAvailablePowerLevel: Int
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
        onProfileSelected = { profile ->
            coroutineScope.launch(Dispatchers.IO) {
                Timber.d("Applying profile: $profile")

                // Update PowerManager's current profile reference immediately
                PowerManager.setCurrentProfile(profile)

                val success = PowerManager.update {
                    name(profile.name)
                    governor(profile.governor.governorName)
                    minCpuValue(profile.minCpuFreq)
                    maxCpuValue(profile.maxCpuFreq)
                    if (PowerManager.isGpuSupported()) {
                        minGpuPowerLevel(profile.minGpuPowerLevel)
                        maxGpuPowerLevel(profile.maxGpuPowerLevel)
                    }
                    if (PowerManager.isBusSupported()) {
                        minBusLevel(profile.minBusLevel)
                        maxBusLevel(profile.maxBusLevel)
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
        onMinFreqChanged = { freqIndex ->
            if (uiState is PowerControlUiState.Success) {
                val freq = (uiState as PowerControlUiState.Success).cpuInfo.availableFrequencies[freqIndex]
                coroutineScope.launch(Dispatchers.IO) {
                    PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                    PowerManager.setMinCpuValue(freq)
                    refreshTrigger++
                }
            }
        },
        onMaxFreqChanged = { freqIndex ->
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
        onMinRamPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMinBusLevel(powerLevel)
                refreshTrigger++
            }
        },
        onMaxRamPowerChanged = { powerLevel ->
            coroutineScope.launch(Dispatchers.IO) {
                PowerManager.setProfileName(PerformancePreset.CUSTOM.displayName)
                PowerManager.setMaxBusLevel(powerLevel)
                refreshTrigger++
            }
        },
        modifier = modifier
            .focusGroup()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
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
                                minPowerLevel = busInfo.minBusLevel,
                                maxPowerLevel = busInfo.maxBusLevel,
                                maxAvailablePowerLevel = busInfo.numBusLevels - 1
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

                    selectedProfile = matchingProfile ?: PowerProfile(
                        name = PerformancePreset.CUSTOM.displayName,
                        governor = currentGovernor ?: CpuGovernor.SCHEDUTIL,
                        minCpuFreq = cpuInfo.currentMinValue,
                        maxCpuFreq = cpuInfo.currentMaxValue,
                        minGpuPowerLevel = gpuDisplayInfo?.minPowerLevel ?: 0,
                        maxGpuPowerLevel = gpuDisplayInfo?.maxPowerLevel ?: 0,
                        minBusLevel = ramDisplayInfo?.minPowerLevel ?: 0,
                        maxBusLevel = ramDisplayInfo?.maxPowerLevel ?: 0
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
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinFreqChanged = {},
            onMaxFreqChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamPowerChanged = {},
            onMaxRamPowerChanged = {}
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
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinFreqChanged = {},
            onMaxFreqChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamPowerChanged = {},
            onMaxRamPowerChanged = {}
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
                    minPowerLevel = 0,
                    maxPowerLevel = 4,
                    maxAvailablePowerLevel = 4
                ),
            ),
            onProfileSelected = {},
            onGovernorSelected = {},
            onMinFreqChanged = {},
            onMaxFreqChanged = {},
            onMinGpuPowerChanged = {},
            onMaxGpuPowerChanged = {},
            onMinRamPowerChanged = {},
            onMaxRamPowerChanged = {}
        )
    }
}
