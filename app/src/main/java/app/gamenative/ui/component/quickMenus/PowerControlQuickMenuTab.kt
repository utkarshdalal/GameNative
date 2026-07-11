package app.gamenative.ui.component.quickMenus

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.powercontrol.PowerManager
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import app.gamenative.powercontrol.PowerProfiles
import app.gamenative.powercontrol.drivers.PerformanceDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PowerControlQuickMenuTab(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val coroutineScope = rememberCoroutineScope()

    // General state
    var selectedProfileName by rememberSaveable { mutableStateOf(PerformancePreset.CUSTOM) }
    var isInitialized by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasPServer by remember { mutableStateOf(false) }

    // CPU state
    var cpuInfo by remember { mutableStateOf<PowerManager.CpuInfo?>(null) }
    var availableGovernors by remember { mutableStateOf<List<String>>(emptyList()) }
    var availableFrequencies by remember { mutableStateOf<List<Long>>(emptyList()) }
    var isProfileDropdownExpanded by remember { mutableStateOf(false) }
    var isGovernorDropdownExpanded by remember { mutableStateOf(false) }
    var selectedMinFreqIndex by remember { mutableIntStateOf(0) }
    var selectedMaxFreqIndex by remember { mutableIntStateOf(0) }

    // GPU state
    var hasGpuSupport by remember { mutableStateOf(false) }
    var gpuInfo by remember { mutableStateOf<PowerManager.GpuInfo?>(null) }
    var availableGpuFrequencies by remember { mutableStateOf<List<Long>>(emptyList()) }
    var selectedGpuFreqIndex by remember { mutableIntStateOf(0) }
    var selectedMinGpuPowerLevel by remember { mutableIntStateOf(0) }
    var selectedMaxGpuPowerLevel by remember { mutableIntStateOf(0) }
    var maxGpuPowerLevel by remember { mutableIntStateOf(0) }

    /**
     * Format frequency value for display based on driver's DisplayUnit
     */
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

    /**
     * Update GPU Display from gpuInfo
     * Power levels are already normalized (higher = better performance)
     */
    fun updateGpuDisplay(info: PowerManager.GpuInfo?) {
        if (info != null && availableGpuFrequencies.isNotEmpty()) {
            selectedMinGpuPowerLevel = info.minGpuPowerLevel
            selectedMaxGpuPowerLevel = info.maxGpuPowerLevel
            selectedGpuFreqIndex = availableGpuFrequencies.indexOfFirst { it >= info.currentGpuValue }.coerceAtLeast(0)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                hasPServer = PowerManager.isPServerAvailable()
                hasGpuSupport = PowerManager.isGpuSupported()

                val info = PowerManager.getCpuInfo()
                if (info != null) {
                    cpuInfo = info
                    availableGovernors = PowerManager.getAvailableGovernors()
                    availableFrequencies = PowerManager.getAvailableCpuFrequencies()

                    if (hasGpuSupport) {
                        gpuInfo = PowerManager.getGpuInfo()
                        availableGpuFrequencies = PowerManager.getAvailableGpuFrequencies()
                        if (gpuInfo != null && gpuInfo!!.numGpuPowerLevels > 0) {
                            maxGpuPowerLevel = gpuInfo!!.numGpuPowerLevels - 1
                        }
                    }

                    // Only determine profile on first load, preserve user selection on subsequent opens
                    if (!isInitialized) {
                        val profiles = PowerProfiles.getDefaultProfiles(availableGovernors, availableFrequencies)
                        // Match by governor only since users can't set custom frequencies
                        val currentGovernor = CpuGovernor.fromString(info.currentGovernor)
                        val matchingProfile = profiles.find { it.governor == currentGovernor }
                        selectedProfileName = matchingProfile?.name ?: PerformancePreset.CUSTOM

                        // Initialize slider positions based on current frequencies
                        selectedMinFreqIndex = availableFrequencies.indexOfFirst { it >= info.currentMinValue }.coerceAtLeast(0)
                        selectedMaxFreqIndex = availableFrequencies.indexOfFirst { it >= info.currentMaxValue }.coerceAtLeast(0)

                        if (hasGpuSupport) {
                            updateGpuDisplay(gpuInfo)
                        }

                        isInitialized = true
                    }

                    errorMessage = null
                } else {
                    errorMessage = "Failed to read CPU frequency information"
                }
            } catch (e: Exception) {
                errorMessage = "Error: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .focusGroup()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isLoading) {
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
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = errorMessage ?: "Unknown error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (cpuInfo != null) {
            val info = cpuInfo!!

            if (!hasPServer) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.power_control_pserver_required),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.power_control_pserver_required_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Power Profile Dropdown
            Text(
                text = stringResource(R.string.power_control_profiles),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            val profiles = remember(availableGovernors, availableFrequencies) {
                PowerProfiles.getDefaultProfiles(availableGovernors, availableFrequencies)
            }

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
                        text = selectedProfileName.displayName,
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
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = profile.name.displayName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "${formatFrequency(profile.minFreq)} - ${formatFrequency(profile.maxFreq)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedProfileName = profile.name
                                isProfileDropdownExpanded = false
                                // Update slider indices to match profile frequencies
                                selectedMinFreqIndex = availableFrequencies.indexOfFirst { it >= profile.minFreq }.coerceAtLeast(0)
                                selectedMaxFreqIndex = availableFrequencies.indexOfFirst { it >= profile.maxFreq }.coerceAtLeast(0)
                                coroutineScope.launch(Dispatchers.IO) {
                                    PowerManager.setGovernor(profile.governor.governorName)
                                    PowerManager.setMinCpuValue(profile.minFreq)
                                    PowerManager.setMaxCpuValue(profile.maxFreq)
                                    // Refresh CPU info after applying profile
                                    cpuInfo = PowerManager.getCpuInfo()
                                }
                            }
                        )
                    }
                }
            }

            // Governor Dropdown
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
                        text = info.currentGovernor.replaceFirstChar { it.uppercase() },
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
                    availableGovernors.forEach { governor ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = governor.replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            onClick = {
                                selectedProfileName = PerformancePreset.CUSTOM
                                isGovernorDropdownExpanded = false
                                coroutineScope.launch(Dispatchers.IO) {
                                    PowerManager.setGovernor(governor)
                                    // Refresh CPU info after changing governor
                                    cpuInfo = PowerManager.getCpuInfo()
                                }
                            }
                        )
                    }
                }
            }

            // Frequency Sliders
            if (availableFrequencies.isNotEmpty()) {
                // Min Frequency Slider
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
                        // Ensure min doesn't exceed max
                        if (newIndex <= selectedMaxFreqIndex) {
                            selectedMinFreqIndex = newIndex
                        }
                    },
                    onValueChangeFinished = {
                        selectedProfileName = PerformancePreset.CUSTOM
                        coroutineScope.launch(Dispatchers.IO) {
                            PowerManager.setMinCpuValue(availableFrequencies[selectedMinFreqIndex])
                            cpuInfo = PowerManager.getCpuInfo()
                        }
                    },
                    valueRange = 0f..(availableFrequencies.size - 1).toFloat(),
                    steps = availableFrequencies.size - 2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatFrequency(availableFrequencies[selectedMinFreqIndex]),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp)
                )
            }

                // Max Frequency Slider
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
                        // Ensure max doesn't go below min
                        if (newIndex >= selectedMinFreqIndex) {
                            selectedMaxFreqIndex = newIndex
                        }
                    },
                    onValueChangeFinished = {
                        selectedProfileName = PerformancePreset.CUSTOM
                        coroutineScope.launch(Dispatchers.IO) {
                            PowerManager.setMaxCpuValue(availableFrequencies[selectedMaxFreqIndex])
                            cpuInfo = PowerManager.getCpuInfo()
                        }
                    },
                    valueRange = 0f..(availableFrequencies.size - 1).toFloat(),
                    steps = availableFrequencies.size - 2,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatFrequency(availableFrequencies[selectedMaxFreqIndex]),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(80.dp)
                )
            }

            // GPU Frequency Slider (disabled - GPU frequencies cannot be set manually)
            if (hasGpuSupport && availableGpuFrequencies.isNotEmpty()) {
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
                        value = selectedGpuFreqIndex.toFloat(),
                        onValueChange = { },
                        enabled = false,
                        valueRange = 0f..(availableGpuFrequencies.size - 1).toFloat(),
                        steps = availableGpuFrequencies.size - 2,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatFrequency(availableGpuFrequencies[selectedGpuFreqIndex]),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp)
                    )
                }
            }

            // GPU Power Level Sliders
            if (hasGpuSupport && maxGpuPowerLevel > 0) {
                // Min GPU Power Level
                // UI: 0 = lowest performance, higher = better performance
                // Sysfs: min_pwrlevel is the minimum performance cap (higher value = lower performance)
                // Conversion: sysfs_min_pwrlevel = maxGpuPowerLevel - ui_min_value
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
                            selectedProfileName = PerformancePreset.CUSTOM
                            coroutineScope.launch(Dispatchers.IO) {
                                PowerManager.setMinGpuPowerLevel(selectedMinGpuPowerLevel)
                                gpuInfo = PowerManager.getGpuInfo()
                                updateGpuDisplay(gpuInfo)
                            }
                        },
                        valueRange = 0f..maxGpuPowerLevel.toFloat(),
                        steps = maxGpuPowerLevel - 1,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = selectedMinGpuPowerLevel.toString(),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(20.dp)
                    )
                }

                // Max GPU Power Level
                // UI: 0 = lowest performance, higher = better performance
                // Sysfs: max_pwrlevel is the maximum performance cap (higher value = lower performance, 0 = fastest)
                // Conversion: sysfs_max_pwrlevel = maxGpuPowerLevel - ui_max_value
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
                            selectedProfileName = PerformancePreset.CUSTOM
                            coroutineScope.launch(Dispatchers.IO) {
                                PowerManager.setMaxGpuPowerLevel(selectedMaxGpuPowerLevel)
                                gpuInfo = PowerManager.getGpuInfo()
                                updateGpuDisplay(gpuInfo)
                            }
                        },
                        valueRange = 0f..maxGpuPowerLevel.toFloat(),
                        steps = maxGpuPowerLevel - 1,
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
    }
    }
}

