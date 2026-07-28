package app.gamenative.ui.component.quickMenus

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.powercontrol.AutoTuningStrategy
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.PowerProfile
import app.gamenative.powercontrol.drivers.PerformanceDriver

@Composable
fun PowerControlQuickMenuContent(
    uiState: PowerControlUiState,
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
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (uiState) {
            is PowerControlUiState.Loading -> {
                LoadingView()
            }
            is PowerControlUiState.Success -> {
                SuccessView(
                    state = uiState,
                    onAutoTuningToggled = onAutoTuningToggled,
                    onTuningStrategySelected = onTuningStrategySelected,
                    onProfileSelected = onProfileSelected,
                    onGovernorSelected = onGovernorSelected,
                    onMinCpuValueChanged = onMinCpuValueChanged,
                    onMaxCpuValueChanged = onMaxCpuValueChanged,
                    onMinGpuPowerChanged = onMinGpuPowerChanged,
                    onMaxGpuPowerChanged = onMaxGpuPowerChanged,
                    onMinRamValueChanged = onMinRamValueChanged,
                    onMaxRamValueChanged = onMaxRamValueChanged,
                )
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

@Composable
private fun SuccessView(
    state: PowerControlUiState.Success,
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
) {
    var isProfileDropdownExpanded by remember { mutableStateOf(false) }
    var isGovernorDropdownExpanded by remember { mutableStateOf(false) }
    var isTuningStrategyDropdownExpanded by remember { mutableStateOf(false) }
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

    // Auto-Tuning Toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.power_control_auto_tuning),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.power_control_auto_tuning_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
        Switch(
            checked = state.selectedProfile.enableAutoTuning,
            onCheckedChange = onAutoTuningToggled
        )
    }

    // Tuning Strategy Dropdown (only shown when auto-tuning is enabled)
    if (state.selectedProfile.enableAutoTuning) {
        Text(
            text = stringResource(R.string.power_control_tuning_strategy),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { isTuningStrategyDropdownExpanded = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(state.selectedProfile.tuningStrategy.displayNameRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(state.selectedProfile.tuningStrategy.descriptionRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = isTuningStrategyDropdownExpanded,
                onDismissRequest = { isTuningStrategyDropdownExpanded = false }
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    AutoTuningStrategy.entries.forEach { strategy ->
                        DropdownMenuItem(
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
                            onClick = {
                                isTuningStrategyDropdownExpanded = false
                                onTuningStrategySelected(strategy)
                            }
                        )
                    }
                }
            }
        }
    }

    // Only show manual controls when auto-tuning is disabled
    if (!state.selectedProfile.enableAutoTuning) {
        SectionHeader(title = "Profile")

        Text(
            text = stringResource(R.string.power_control_profiles),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { isProfileDropdownExpanded = true }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.selectedProfile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DropdownMenu(
                expanded = isProfileDropdownExpanded,
                onDismissRequest = { isProfileDropdownExpanded = false }
            ) {
                state.availableProfiles.forEach { profile ->
                    DropdownMenuItem(
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
                        onClick = {
                            isProfileDropdownExpanded = false
                            onProfileSelected(profile)
                        }
                    )
                }
            }
        }
    }

    SectionHeader(title = "CPU")

    Text(
        text = stringResource(R.string.power_control_governor),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable { isGovernorDropdownExpanded = true }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.cpuInfo.currentGovernor.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        DropdownMenu(
            expanded = isGovernorDropdownExpanded,
            onDismissRequest = { isGovernorDropdownExpanded = false }
        ) {
            state.cpuInfo.availableGovernors.forEach { governor ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = governor.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    onClick = {
                        isGovernorDropdownExpanded = false
                        onGovernorSelected(governor)
                    }
                )
            }
        }
    }

    // Only show manual controls when auto-tuning is disabled
    if (!state.selectedProfile.enableAutoTuning) {
        if (state.cpuInfo.availableFrequencies.isNotEmpty()) {
            Text(
                text = stringResource(R.string.power_control_cpu_min_freq),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = selectedMinFreqIndex.toFloat(),
                    onValueChange = { newValue ->
                        val newIndex = newValue.toInt()
                        if (newIndex <= selectedMaxFreqIndex) {
                            selectedMinFreqIndex = newIndex
                        }
                    },
                    onValueChangeFinished = {
                        onMinCpuValueChanged(selectedMinFreqIndex)
                    },
                    valueRange = 0f..(state.cpuInfo.availableFrequencies.size - 1).toFloat(),
                    steps = state.cpuInfo.availableFrequencies.size - 2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatFrequency(state.cpuInfo.availableFrequencies[selectedMinFreqIndex]),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp)
                )
            }

            Text(
                text = stringResource(R.string.power_control_cpu_max_freq),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Slider(
                    value = selectedMaxFreqIndex.toFloat(),
                    onValueChange = { newValue ->
                        val newIndex = newValue.toInt()
                        if (newIndex >= selectedMinFreqIndex) {
                            selectedMaxFreqIndex = newIndex
                        }
                    },
                    onValueChangeFinished = {
                        onMaxCpuValueChanged(selectedMaxFreqIndex)
                    },
                    valueRange = 0f..(state.cpuInfo.availableFrequencies.size - 1).toFloat(),
                    steps = state.cpuInfo.availableFrequencies.size - 2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatFrequency(state.cpuInfo.availableFrequencies[selectedMaxFreqIndex]),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp)
                )
            }
        }

        state.gpuInfo?.let { gpuInfo ->
            SectionHeader(title = "GPU")

            if (gpuInfo.availableFrequencies.isNotEmpty()) {
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

            if (gpuInfo.maxAvailablePowerLevel > 0) {
                Text(
                    text = stringResource(R.string.power_control_gpu_min_power),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = selectedMinGpuPowerLevel.toFloat(),
                        onValueChange = { newValue ->
                            val newLevel = newValue.toInt()
                            if (newLevel <= selectedMaxGpuPowerLevel) {
                                selectedMinGpuPowerLevel = newLevel
                            }
                        },
                        onValueChangeFinished = {
                            onMinGpuPowerChanged(selectedMinGpuPowerLevel)
                        },
                        valueRange = 0f..gpuInfo.maxAvailablePowerLevel.toFloat(),
                        steps = gpuInfo.maxAvailablePowerLevel - 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = selectedMinGpuPowerLevel.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.power_control_gpu_max_power),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = selectedMaxGpuPowerLevel.toFloat(),
                        onValueChange = { newValue ->
                            val newLevel = newValue.toInt()
                            if (newLevel >= selectedMinGpuPowerLevel) {
                                selectedMaxGpuPowerLevel = newLevel
                            }
                        },
                        onValueChangeFinished = {
                            onMaxGpuPowerChanged(selectedMaxGpuPowerLevel)
                        },
                        valueRange = 0f..gpuInfo.maxAvailablePowerLevel.toFloat(),
                        steps = gpuInfo.maxAvailablePowerLevel - 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = selectedMaxGpuPowerLevel.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp)
                    )
                }
            }
        }

        state.ramInfo?.let { ramInfo ->
            if (ramInfo.maxAvailableBusLevel > 0) {
                SectionHeader(title = "RAM")

                Text(
                    text = stringResource(R.string.power_control_ram_min_power),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = selectedMinRamValue.toFloat(),
                        onValueChange = { newValue ->
                            val newLevel = newValue.toInt()

                            if (newLevel <= selectedMaxRamValue) {
                                selectedMinRamValue = newLevel
                            }
                        },
                        onValueChangeFinished = {
                            onMinRamValueChanged(selectedMinRamValue)
                        },
                        valueRange = 0f..ramInfo.maxAvailableBusLevel.toFloat(),
                        steps = (ramInfo.maxAvailableBusLevel - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = selectedMinRamValue.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp)
                    )
                }

                Text(
                    text = stringResource(R.string.power_control_ram_max_power),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = selectedMaxRamValue.toFloat(),
                        onValueChange = { newValue ->
                            val newLevel = newValue.toInt()

                            if (newLevel >= selectedMinRamValue) {
                                selectedMaxRamValue = newLevel
                            }
                        },
                        onValueChangeFinished = {
                            onMaxRamValueChanged(selectedMaxRamValue)
                        },
                        valueRange = 0f..ramInfo.maxAvailableBusLevel.toFloat(),
                        steps = (ramInfo.maxAvailableBusLevel - 1).coerceAtLeast(0),
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = selectedMaxRamValue.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp)
                    )
                }
            }
        }
    } // End of auto-tuning check
}
