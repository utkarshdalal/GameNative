package app.gamenative.powercontrol

import androidx.annotation.StringRes
import app.gamenative.R
import app.gamenative.powercontrol.autotuning.DeviceGate
import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset
import kotlinx.serialization.Serializable

enum class AutoTuningStrategy(@param:StringRes val displayNameRes: Int, @param:StringRes val descriptionRes: Int) {
    POWER_EFFICIENT(R.string.power_control_strategy_power_efficient, R.string.power_control_strategy_power_efficient_desc),
    BALANCED(R.string.power_control_strategy_balanced, R.string.power_control_strategy_balanced_desc),
    AGGRESSIVE(R.string.power_control_strategy_aggressive, R.string.power_control_strategy_aggressive_desc),
    CONSERVATIVE(R.string.power_control_strategy_conservative, R.string.power_control_strategy_conservative_desc)
}

@Serializable
data class PowerProfile(
    var enableAdaptiveFpsCap: Boolean = true,
    var enableAutoTuning: Boolean = false,
    var enablePerClusterTuning: Boolean = false,
    var tuningStrategy: AutoTuningStrategy = AutoTuningStrategy.BALANCED,
    var enableFanControl: Boolean = false,
    var enableGamePinning: Boolean = false,
    var name: String,
    var governor: CpuGovernor,
    var minCpuFreq: Long,
    var maxCpuFreq: Long,
    var minGpuPowerLevel: Int = 0,
    var maxGpuPowerLevel: Int = 0,
    var minBusLevel: Int = 0,
    var maxBusLevel: Int = 0,
)

object PowerProfiles {
    /**
     * Generate default power profiles based on available governors and frequencies.
     *
     * Reference values based on tested devices:
     *
     * AYN Odin 3 (Snapdragon 8 Elite):
     * - Processor: Qualcomm Snapdragon 8 Elite (2 x Prime cores @ 4.32 GHz, 6 x Performance cores @ 3.53 GHz)
     * - Available governors: walt, conservative, powersave, performance, schedutil
     * - Available frequencies: 384000 - 3532800 KHz (384 MHz - 3.53 GHz, 16 steps)
     * - Frequency steps: 384, 556, 748, 960, 1152, 1363, 1555, 1785, 1996, 2227, 2400, 2745, 2918, 3072, 3321, 3532 MHz
     *
     * Retroid Pocket 6 (Snapdragon 8 Gen 2):
     * - Processor: Qualcomm Snapdragon 8 Gen 2 (1 x Cortex-X3 @ 3.2 GHz, 4 x Cortex-A715 @ 2.8 GHz, 3 x Cortex-A510 @ 2.0 GHz)
     * - Available governors: walt, conservative, powersave, performance, schedutil
     * - Available frequencies: 307200 - 2016000 KHz (307 MHz - 2.02 GHz, 16 steps)
     * - Frequency steps: 307, 441, 556, 672, 787, 902, 1017, 1113, 1228, 1344, 1459, 1555, 1670, 1785, 1900, 2016 MHz
     */
    fun getDefaultProfiles(
        availableGovernors: List<String>,
        availableFrequencies: List<Long>,
        maxGpuPowerLevel: Int = 0
    ): List<PowerProfile> {
        if (availableFrequencies.isEmpty()) return emptyList()

        val minFreq = availableFrequencies.first()  // Odin 3: 384 MHz, RP6: 307 MHz
        val maxFreq = availableFrequencies.last()   // Odin 3: 3532 MHz, RP6: 2016 MHz
        val midFreq = availableFrequencies[availableFrequencies.size / 2]  // Odin 3: ~2227 MHz, RP6: ~1344 MHz (50%)

        // For better granularity on devices with many frequency steps
        val lowFreq = if (availableFrequencies.size > 4) {
            availableFrequencies[availableFrequencies.size / 4]  // Odin 3: ~960 MHz, RP6: ~672 MHz (25%)
        } else {
            minFreq
        }
        val highFreq = if (availableFrequencies.size > 4) {
            availableFrequencies[(availableFrequencies.size * 3) / 4]  // Odin 3: ~2918 MHz, RP6: ~1785 MHz (75%)
        } else {
            maxFreq
        }

        // GPU power level calculations (similar to CPU frequency tiers)
        val lowGpuLevel = if (maxGpuPowerLevel > 4) {
            maxGpuPowerLevel / 4  // 25%
        } else {
            0
        }
        val midGpuLevel = if (maxGpuPowerLevel > 0) {
            maxGpuPowerLevel / 2  // 50%
        } else {
            0
        }
        val highGpuLevel = if (maxGpuPowerLevel > 4) {
            (maxGpuPowerLevel * 3) / 4  // 75%
        } else {
            maxGpuPowerLevel
        }

        // Use Driver default profile and fill default values, for default profiles, automatic updates are disabled
        val defaultProfile = PowerManager.getDriverDefaultProfile().copy(
            enableAutoTuning = false,
            enablePerClusterTuning = false,
        )

        return buildList {
            // Power Save - lowest frequency range with powersave governor
            // CPU: Odin 3: 384 MHz - 960 MHz, RP6: 307 MHz - 672 MHz
            // GPU: 0 - 25% of max power level
            if (availableGovernors.contains(CpuGovernor.POWERSAVE.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.POWER_SAVE.displayName,
                    governor = CpuGovernor.POWERSAVE,
                    minCpuFreq = minFreq,
                    maxCpuFreq = lowFreq,
                    minGpuPowerLevel = 0,
                    maxGpuPowerLevel = lowGpuLevel
                ))
            }

            // Balanced - schedutil is best for modern devices, falls back to conservative or interactive
            // For gaming, start at midFreq (50%) to ensure adequate performance while saving battery
            // CPU: Odin 3: 2227 MHz - 3532 MHz, RP6: 1344 MHz - 2016 MHz
            // GPU: 50% - 100% of max power level
            if (availableGovernors.contains(CpuGovernor.SCHEDUTIL.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.BALANCED.displayName,
                    governor = CpuGovernor.SCHEDUTIL,
                    minCpuFreq = midFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = midGpuLevel,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            } else if (availableGovernors.contains(CpuGovernor.CONSERVATIVE.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.BALANCED.displayName,
                    governor = CpuGovernor.CONSERVATIVE,
                    minCpuFreq = midFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = midGpuLevel,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            } else if (availableGovernors.contains(CpuGovernor.INTERACTIVE.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.BALANCED.displayName,
                    governor = CpuGovernor.INTERACTIVE,
                    minCpuFreq = midFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = midGpuLevel,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            }

            // Performance - maximum performance with performance governor
            // CPU: Odin 3: 2918 MHz - 3532 MHz, RP6: 1785 MHz - 2016 MHz
            // GPU: 75% - 100% of max power level
            if (availableGovernors.contains(CpuGovernor.PERFORMANCE.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.PERFORMANCE.displayName,
                    governor = CpuGovernor.PERFORMANCE,
                    minCpuFreq = highFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = highGpuLevel,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            }

            // On Demand - responsive but power-aware (legacy governor, not available on Odin 3 or RP6)
            // CPU: Full range, GPU: Full range
            if (availableGovernors.contains(CpuGovernor.ONDEMAND.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.ON_DEMAND.displayName,
                    governor = CpuGovernor.ONDEMAND,
                    minCpuFreq = minFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = 0,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            }

            // WALT (Window Assisted Load Tracking) - Qualcomm's scheduler-based governor
            // CPU: Odin 3: 384 MHz - 3532 MHz, RP6: 307 MHz - 2016 MHz
            // GPU: Full range
            if (availableGovernors.contains(CpuGovernor.WALT.governorName)) {
                add(defaultProfile.copy(
                    name = PerformancePreset.WALT.displayName,
                    governor = CpuGovernor.WALT,
                    minCpuFreq = minFreq,
                    maxCpuFreq = maxFreq,
                    minGpuPowerLevel = 0,
                    maxGpuPowerLevel = maxGpuPowerLevel
                ))
            }
        }
    }
}
