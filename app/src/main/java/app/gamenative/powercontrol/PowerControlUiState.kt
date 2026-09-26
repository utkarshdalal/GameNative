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

/** Discovered CPU cores per cluster, for the colored Manual pinning checkboxes. */
data class CpuTopologyDisplayInfo(val coresByCluster: Map<CpuCluster, List<Int>>) {
    /** Clusters the device has, efficiency first. */
    val presentClusters: List<CpuCluster> = CpuCluster.entries.filter { !coresByCluster[it].isNullOrEmpty() }

    /** Every core, in index order for display. */
    val cores: List<Int> = coresByCluster.values.flatten().sorted()

    val clusterByCore: Map<Int, CpuCluster> =
        coresByCluster.flatMap { (cluster, clusterCores) -> clusterCores.map { it to cluster } }.toMap()
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
