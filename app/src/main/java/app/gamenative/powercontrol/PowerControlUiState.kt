package app.gamenative.powercontrol

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
