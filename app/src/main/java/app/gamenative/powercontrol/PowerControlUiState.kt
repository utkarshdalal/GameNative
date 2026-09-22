package app.gamenative.powercontrol

import app.gamenative.powercontrol.drivers.PServerDriver.CpuCluster

sealed class PowerControlUiState {
    object Loading : PowerControlUiState()
    data class Success(
        val selectedProfile: PowerProfile,
        val availableProfiles: List<PowerProfile>,
        val cpuInfo: CpuDisplayInfo?,
        val gpuInfo: GpuDisplayInfo?,
        val ramInfo: RamDisplayInfo?,
        val cpuTopology: CpuTopologyDisplayInfo? = null,
    ) : PowerControlUiState()
}

data class CpuTopologyDisplayInfo(
    val cores: List<Int>,
    val clusterByCore: Map<Int, CpuCluster>,
    val presentClusters: List<CpuCluster>,
)

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
