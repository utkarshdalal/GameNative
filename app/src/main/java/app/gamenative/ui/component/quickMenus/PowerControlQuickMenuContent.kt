package app.gamenative.ui.component.quickMenus

import android.annotation.SuppressLint
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.PowerControlUiState
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.drivers.PerformanceDriver
import app.gamenative.ui.component.QuickMenuAdjustmentRow
import app.gamenative.ui.component.QuickMenuToggleRow
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.MathUtils.normalizedProgress
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PowerControlQuickMenuContent(
    uiState: PowerControlUiState,
    isDriverSupported: Boolean,
    onAutoTuningToggled: (Boolean) -> Unit,
    onTuningStrategySelected: (AutoTuningStrategy) -> Unit,
    onProfileSelected: (PowerProfile) -> Unit,
    onGovernorSelected: (String) -> Unit,
    onMinCpuValueChanged: (Int) -> Unit,
    onMaxCpuValueChanged: (Int) -> Unit,
    onMinGpuPowerChanged: (Int) -> Unit,
    onMaxGpuPowerChanged: (Int) -> Unit,
    onMinRamValueChanged: (Int) -> Unit,
    onMaxRamValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onFanControlToggled: (Boolean) -> Unit = {},
    onGamePinningToggled: (Boolean) -> Unit = {},
    onTuningModeSelected: (Boolean) -> Unit = {},
    onAdaptiveFpsCapToggled: (Boolean) -> Unit = {},
    firstItemFocusRequester: FocusRequester? = null,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        when (uiState) {
            is PowerControlUiState.Loading -> {
                LoadingView()
            }
            is PowerControlUiState.Success -> {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    maxItemsInEachRow = 2,
                ) {
                    SuccessView(
                        state = uiState,
                        isDriverSupported = isDriverSupported,
                        onAutoTuningToggled = onAutoTuningToggled,
                        onFanControlToggled = onFanControlToggled,
                        onGamePinningToggled = onGamePinningToggled,
                        onTuningModeSelected = onTuningModeSelected,
                        onAdaptiveFpsCapToggled = onAdaptiveFpsCapToggled,
                        onTuningStrategySelected = onTuningStrategySelected,
                        onProfileSelected = onProfileSelected,
                        onGovernorSelected = onGovernorSelected,
                        onMinCpuValueChanged = onMinCpuValueChanged,
                        onMaxCpuValueChanged = onMaxCpuValueChanged,
                        onMinGpuPowerChanged = onMinGpuPowerChanged,
                        onMaxGpuPowerChanged = onMaxGpuPowerChanged,
                        onMinRamValueChanged = onMinRamValueChanged,
                        onMaxRamValueChanged = onMaxRamValueChanged,
                        firstItemFocusRequester = firstItemFocusRequester,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.main_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowScope.SuccessView(
    state: PowerControlUiState.Success,
    isDriverSupported: Boolean,
    onAutoTuningToggled: (Boolean) -> Unit,
    onFanControlToggled: (Boolean) -> Unit,
    onGamePinningToggled: (Boolean) -> Unit,
    onTuningModeSelected: (Boolean) -> Unit,
    onAdaptiveFpsCapToggled: (Boolean) -> Unit,
    onTuningStrategySelected: (AutoTuningStrategy) -> Unit,
    onProfileSelected: (PowerProfile) -> Unit,
    onGovernorSelected: (String) -> Unit,
    onMinCpuValueChanged: (Int) -> Unit,
    onMaxCpuValueChanged: (Int) -> Unit,
    onMinGpuPowerChanged: (Int) -> Unit,
    onMaxGpuPowerChanged: (Int) -> Unit,
    onMinRamValueChanged: (Int) -> Unit,
    onMaxRamValueChanged: (Int) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    var isProfileDropdownExpanded by remember { mutableStateOf(false) }
    var isGovernorDropdownExpanded by remember { mutableStateOf(false) }
    var isTuningStrategyDropdownExpanded by remember { mutableStateOf(false) }
    var isTuningModeDropdownExpanded by remember { mutableStateOf(false) }
    var selectedMinFreqIndex by remember { mutableIntStateOf(state.cpuInfo.selectedMinFreqIndex) }
    var selectedMaxFreqIndex by remember { mutableIntStateOf(state.cpuInfo.selectedMaxFreqIndex) }
    var selectedMinGpuPowerLevel by remember { mutableIntStateOf(state.gpuInfo?.minPowerLevel ?: 0) }
    var selectedMaxGpuPowerLevel by remember { mutableIntStateOf(state.gpuInfo?.maxPowerLevel ?: 0) }
    var selectedMinRamValue by remember { mutableIntStateOf(state.ramInfo?.minBusLevel ?: 0) }
    var selectedMaxRamValue by remember { mutableIntStateOf(state.ramInfo?.maxBusLevel ?: 0) }

    LaunchedEffect(state.cpuInfo.selectedMinFreqIndex, state.cpuInfo.selectedMaxFreqIndex) {
        selectedMinFreqIndex = state.cpuInfo.selectedMinFreqIndex
        selectedMaxFreqIndex = state.cpuInfo.selectedMaxFreqIndex
    }

    LaunchedEffect(state.gpuInfo?.minPowerLevel, state.gpuInfo?.maxPowerLevel) {
        selectedMinGpuPowerLevel = state.gpuInfo?.minPowerLevel ?: 0
        selectedMaxGpuPowerLevel = state.gpuInfo?.maxPowerLevel ?: 0
    }

    LaunchedEffect(state.ramInfo?.minBusLevel, state.ramInfo?.maxBusLevel) {
        selectedMinRamValue = state.ramInfo?.minBusLevel ?: 0
        selectedMaxRamValue = state.ramInfo?.maxBusLevel ?: 0
    }

    @SuppressLint("DefaultLocale")
    fun formatFrequency(freqKhz: Long): String {
        return when (PowerManager.getDisplayUnit()) {
            PerformanceDriver.DisplayUnit.HZ -> {
                when {
                    freqKhz >= 1_000_000 -> String.format("%.2f GHz", freqKhz / 1_000_000.0)
                    freqKhz >= 1_000 -> String.format("%.0f MHz", freqKhz / 1_000.0)
                    else -> "$freqKhz KHz"
                }
            }
            PerformanceDriver.DisplayUnit.INTEGER -> {
                freqKhz.toString()
            }
        }
    }

    QuickMenuToggleRow(
        title = stringResource(R.string.power_control_adaptive_fps),
        subtitle = stringResource(R.string.power_control_adaptive_fps_desc),
        enabled = state.selectedProfile.enableAdaptiveFpsCap,
        onToggle = { onAdaptiveFpsCapToggled(!state.selectedProfile.enableAdaptiveFpsCap) },
        accentColor = accentColor,
        focusRequester = firstItemFocusRequester,
        modifier = Modifier.weight(1f),
    )

    QuickMenuToggleRow(
        title = stringResource(R.string.power_control_auto_tuning),
        subtitle = stringResource(R.string.power_control_auto_tuning_desc),
        enabled = state.selectedProfile.enableAutoTuning,
        selectable = isDriverSupported,
        onToggle = {
            onAutoTuningToggled(!state.selectedProfile.enableAutoTuning)
        },
        accentColor = accentColor,
        modifier = Modifier.weight(1f),
    )

    // Tuning Mode Dropdown (only shown when auto-tuning is enabled)
    val isClusterTuningAvailable = PowerManager.isClusterTuningAvailable()
    if (state.selectedProfile.enableAutoTuning) {
        val isPerClusterSelected = isClusterTuningAvailable && state.selectedProfile.enablePerClusterTuning

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.power_control_tuning_mode),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            SelectorRow(
                valueText = if (isPerClusterSelected) {
                    stringResource(R.string.power_control_tuning_mode_per_cluster)
                } else {
                    stringResource(R.string.power_control_tuning_mode_whole_cpu)
                },
                accentColor = accentColor,
                expanded = isTuningModeDropdownExpanded,
                onExpandedChange = { isTuningModeDropdownExpanded = it },
            ) { menuFocusRequester ->
                SelectorMenuItem(
                    accentColor = accentColor,
                    enabled = isClusterTuningAvailable,
                    focusRequester = if (isClusterTuningAvailable) menuFocusRequester else null,
                    onClick = {
                        isTuningModeDropdownExpanded = false
                        onTuningModeSelected(true)
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.power_control_tuning_mode_per_cluster),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                )
                SelectorMenuItem(
                    accentColor = accentColor,
                    focusRequester = if (isClusterTuningAvailable) null else menuFocusRequester,
                    onClick = {
                        isTuningModeDropdownExpanded = false
                        onTuningModeSelected(false)
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.power_control_tuning_mode_whole_cpu),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                )
            }
        }
    }

    if (isDriverSupported) {
        // Tuning Strategy Dropdown (only shown when auto-tuning is enabled)
        if (state.selectedProfile.enableAutoTuning) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.power_control_tuning_strategy),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                SelectorRow(
                    valueText = stringResource(state.selectedProfile.tuningStrategy.displayNameRes),
                    descriptionText = stringResource(state.selectedProfile.tuningStrategy.descriptionRes),
                    accentColor = accentColor,
                    expanded = isTuningStrategyDropdownExpanded,
                    onExpandedChange = { isTuningStrategyDropdownExpanded = it },
                ) { menuFocusRequester ->
                    AutoTuningStrategy.entries.forEachIndexed { index, strategy ->
                        SelectorMenuItem(
                            accentColor = accentColor,
                            focusRequester = if (index == 0) menuFocusRequester else null,
                            onClick = {
                                isTuningStrategyDropdownExpanded = false
                                onTuningStrategySelected(strategy)
                            },
                            text = {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = stringResource(strategy.displayNameRes),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = stringResource(strategy.descriptionRes),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val isFanControlAvailable = PowerManager.isFanControlAvailable()
    QuickMenuToggleRow(
        title = stringResource(R.string.power_control_fan_control),
        subtitle = stringResource(R.string.power_control_fan_control_desc),
        modifier = Modifier.weight(1f),
        selectable = isDriverSupported && isFanControlAvailable,
        enabled = state.selectedProfile.enableFanControl,
        onToggle = {
            if (isFanControlAvailable) {
                onFanControlToggled(!state.selectedProfile.enableFanControl)
            }
        },
        accentColor = accentColor,
    )

    val isGamePinningAvailable = PowerManager.isGamePinningAvailable()
    QuickMenuToggleRow(
        title = stringResource(R.string.power_control_game_pinning),
        subtitle = stringResource(R.string.power_control_game_pinning_desc),
        modifier = Modifier.weight(1f),
        selectable = isDriverSupported && isGamePinningAvailable,
        enabled = state.selectedProfile.enableGamePinning,
        onToggle = {
            if (isGamePinningAvailable) {
                onGamePinningToggled(!state.selectedProfile.enableGamePinning)
            }
        },
        accentColor = accentColor,
    )

    if (isDriverSupported) {
        // Only show manual controls when auto-tuning is disabled
        if (!state.selectedProfile.enableAutoTuning) {
            SectionHeader(title = "Profile")

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.power_control_profiles),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                SelectorRow(
                    valueText = state.selectedProfile.name,
                    accentColor = accentColor,
                    expanded = isProfileDropdownExpanded,
                    onExpandedChange = { isProfileDropdownExpanded = it },
                ) { menuFocusRequester ->
                    state.availableProfiles.forEachIndexed { index, profile ->
                        SelectorMenuItem(
                            accentColor = accentColor,
                            focusRequester = if (index == 0) menuFocusRequester else null,
                            onClick = {
                                isProfileDropdownExpanded = false
                                onProfileSelected(profile)
                            },
                            text = {
                                Column {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${formatFrequency(profile.minCpuFreq)} - ${formatFrequency(profile.maxCpuFreq)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

        SectionHeader(title = "CPU")

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.power_control_governor),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            SelectorRow(
                valueText = state.cpuInfo.currentGovernor.replaceFirstChar { it.uppercase() },
                accentColor = accentColor,
                expanded = isGovernorDropdownExpanded,
                onExpandedChange = { isGovernorDropdownExpanded = it },
            ) { menuFocusRequester ->
                state.cpuInfo.availableGovernors.forEachIndexed { index, governor ->
                    SelectorMenuItem(
                        accentColor = accentColor,
                        focusRequester = if (index == 0) menuFocusRequester else null,
                        onClick = {
                            isGovernorDropdownExpanded = false
                            onGovernorSelected(governor)
                        },
                        text = {
                            Text(
                                text = governor.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                    )
                }
            }
        }

        // Only show manual controls when auto-tuning is disabled
        if (!state.selectedProfile.enableAutoTuning) {
            if (state.cpuInfo.availableFrequencies.isNotEmpty()) {
                val maxFreqIndex = state.cpuInfo.availableFrequencies.size - 1

                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.power_control_cpu_min_freq),
                    modifier = Modifier.weight(1f),
                    valueText = formatFrequency(
                        state.cpuInfo.availableFrequencies[selectedMinFreqIndex.coerceIn(0, maxFreqIndex)],
                    ),
                    progress = normalizedProgress(selectedMinFreqIndex.toFloat(), 0f, maxFreqIndex.toFloat()),
                    onDecrease = {
                        val newIndex = (selectedMinFreqIndex - 1).coerceAtLeast(0)
                        if (newIndex != selectedMinFreqIndex) {
                            selectedMinFreqIndex = newIndex
                            onMinCpuValueChanged(newIndex)
                        }
                    },
                    onIncrease = {
                        val newIndex = (selectedMinFreqIndex + 1).coerceAtMost(selectedMaxFreqIndex)
                        if (newIndex != selectedMinFreqIndex) {
                            selectedMinFreqIndex = newIndex
                            onMinCpuValueChanged(newIndex)
                        }
                    },
                    accentColor = accentColor,
                )

                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.power_control_cpu_max_freq),
                    modifier = Modifier.weight(1f),
                    valueText = formatFrequency(
                        state.cpuInfo.availableFrequencies[selectedMaxFreqIndex.coerceIn(0, maxFreqIndex)],
                    ),
                    progress = normalizedProgress(selectedMaxFreqIndex.toFloat(), 0f, maxFreqIndex.toFloat()),
                    onDecrease = {
                        val newIndex = (selectedMaxFreqIndex - 1).coerceAtLeast(selectedMinFreqIndex)
                        if (newIndex != selectedMaxFreqIndex) {
                            selectedMaxFreqIndex = newIndex
                            onMaxCpuValueChanged(newIndex)
                        }
                    },
                    onIncrease = {
                        val newIndex = (selectedMaxFreqIndex + 1).coerceAtMost(maxFreqIndex)
                        if (newIndex != selectedMaxFreqIndex) {
                            selectedMaxFreqIndex = newIndex
                            onMaxCpuValueChanged(newIndex)
                        }
                    },
                    accentColor = accentColor,
                )
            }

            state.gpuInfo?.let { gpuInfo ->
                SectionHeader(title = "GPU")

                if (gpuInfo.availableFrequencies.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.power_control_gpu_freq),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Slider(
                                value = gpuInfo.currentFreqIndex.toFloat(),
                                onValueChange = { },
                                enabled = false,
                                valueRange = 0f..(gpuInfo.availableFrequencies.size - 1).toFloat(),
                                steps = gpuInfo.availableFrequencies.size - 2,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatFrequency(gpuInfo.availableFrequencies[gpuInfo.currentFreqIndex]),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(80.dp)
                            )
                        }
                    }
                }

                if (gpuInfo.maxAvailablePowerLevel > 0) {
                    QuickMenuAdjustmentRow(
                        title = stringResource(R.string.power_control_gpu_min_power),
                        modifier = Modifier.weight(1f),
                        valueText = selectedMinGpuPowerLevel.toString(),
                        progress = normalizedProgress(
                            selectedMinGpuPowerLevel.toFloat(),
                            0f,
                            gpuInfo.maxAvailablePowerLevel.toFloat(),
                        ),
                        onDecrease = {
                            val newLevel = (selectedMinGpuPowerLevel - 1).coerceAtLeast(0)
                            if (newLevel != selectedMinGpuPowerLevel) {
                                selectedMinGpuPowerLevel = newLevel
                                onMinGpuPowerChanged(newLevel)
                            }
                        },
                        onIncrease = {
                            val newLevel = (selectedMinGpuPowerLevel + 1).coerceAtMost(selectedMaxGpuPowerLevel)
                            if (newLevel != selectedMinGpuPowerLevel) {
                                selectedMinGpuPowerLevel = newLevel
                                onMinGpuPowerChanged(newLevel)
                            }
                        },
                        accentColor = accentColor,
                    )

                    QuickMenuAdjustmentRow(
                        title = stringResource(R.string.power_control_gpu_max_power),
                        modifier = Modifier.weight(1f),
                        valueText = selectedMaxGpuPowerLevel.toString(),
                        progress = normalizedProgress(
                            selectedMaxGpuPowerLevel.toFloat(),
                            0f,
                            gpuInfo.maxAvailablePowerLevel.toFloat(),
                        ),
                        onDecrease = {
                            val newLevel = (selectedMaxGpuPowerLevel - 1).coerceAtLeast(selectedMinGpuPowerLevel)
                            if (newLevel != selectedMaxGpuPowerLevel) {
                                selectedMaxGpuPowerLevel = newLevel
                                onMaxGpuPowerChanged(newLevel)
                            }
                        },
                        onIncrease = {
                            val newLevel = (selectedMaxGpuPowerLevel + 1).coerceAtMost(gpuInfo.maxAvailablePowerLevel)
                            if (newLevel != selectedMaxGpuPowerLevel) {
                                selectedMaxGpuPowerLevel = newLevel
                                onMaxGpuPowerChanged(newLevel)
                            }
                        },
                        accentColor = accentColor,
                    )
                }
            }

            state.ramInfo?.let { ramInfo ->
                if (ramInfo.maxAvailableBusLevel > 0) {
                    SectionHeader(title = "RAM")

                    QuickMenuAdjustmentRow(
                        title = stringResource(R.string.power_control_ram_min_power),
                        modifier = Modifier.weight(1f),
                        valueText = selectedMinRamValue.toString(),
                        progress = normalizedProgress(
                            selectedMinRamValue.toFloat(),
                            0f,
                            ramInfo.maxAvailableBusLevel.toFloat(),
                        ),
                        onDecrease = {
                            val newLevel = (selectedMinRamValue - 1).coerceAtLeast(0)
                            if (newLevel != selectedMinRamValue) {
                                selectedMinRamValue = newLevel
                                onMinRamValueChanged(newLevel)
                            }
                        },
                        onIncrease = {
                            val newLevel = (selectedMinRamValue + 1).coerceAtMost(selectedMaxRamValue)
                            if (newLevel != selectedMinRamValue) {
                                selectedMinRamValue = newLevel
                                onMinRamValueChanged(newLevel)
                            }
                        },
                        accentColor = accentColor,
                    )

                    QuickMenuAdjustmentRow(
                        title = stringResource(R.string.power_control_ram_max_power),
                        modifier = Modifier.weight(1f),
                        valueText = selectedMaxRamValue.toString(),
                        progress = normalizedProgress(
                            selectedMaxRamValue.toFloat(),
                            0f,
                            ramInfo.maxAvailableBusLevel.toFloat(),
                        ),
                        onDecrease = {
                            val newLevel = (selectedMaxRamValue - 1).coerceAtLeast(selectedMinRamValue)
                            if (newLevel != selectedMaxRamValue) {
                                selectedMaxRamValue = newLevel
                                onMaxRamValueChanged(newLevel)
                            }
                        },
                        onIncrease = {
                            val newLevel = (selectedMaxRamValue + 1).coerceAtMost(ramInfo.maxAvailableBusLevel)
                            if (newLevel != selectedMaxRamValue) {
                                selectedMaxRamValue = newLevel
                                onMaxRamValueChanged(newLevel)
                            }
                        },
                        accentColor = accentColor,
                    )
                }
            }
        } // End of auto-tuning check
    }
}

@Composable
private fun SelectorRow(
    valueText: String,
    accentColor: Color,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    descriptionText: String? = null,
    focusRequester: FocusRequester? = null,
    menuContent: @Composable (FocusRequester) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val menuFocusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded) {
        if (expanded) {
            repeat(3) {
                try {
                    menuFocusRequester.requestFocus()
                    return@LaunchedEffect
                } catch (_: Exception) {
                    delay(80)
                }
            }
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(shape)
                .background(
                    if (isFocused) {
                        Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.16f),
                                accentColor.copy(alpha = 0.08f),
                            ),
                        )
                    } else {
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                            ),
                        )
                    },
                )
                .then(
                    if (isFocused) {
                        Modifier.border(
                            width = 2.dp,
                            color = accentColor.copy(alpha = 0.7f),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .focusable(interactionSource = interactionSource)
                .onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BUTTON_A,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER -> {
                                onExpandedChange(true)
                                true
                            }

                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .selectable(
                    selected = isFocused,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { onExpandedChange(true) },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (!descriptionText.isNullOrBlank()) {
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            menuContent(menuFocusRequester)
        }
    }
}

@Composable
private fun SelectorMenuItem(
    accentColor: Color,
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    DropdownMenuItem(
        text = text,
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier
            .background(
                if (isFocused) accentColor.copy(alpha = 0.16f) else Color.Transparent,
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            ),
    )
}
