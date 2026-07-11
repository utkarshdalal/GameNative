package app.gamenative.powercontrol

import app.gamenative.powercontrol.profiles.CpuGovernor
import app.gamenative.powercontrol.profiles.PerformancePreset

data class PowerProfile(
    val name: PerformancePreset,
    val governor: CpuGovernor,
    val minFreq: Long,
    val maxFreq: Long
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
    fun getDefaultProfiles(availableGovernors: List<String>, availableFrequencies: List<Long>): List<PowerProfile> {
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

        return buildList {
            // Power Save - lowest frequency range with powersave governor
            // Odin 3: 384 MHz - 960 MHz, RP6: 307 MHz - 672 MHz
            if (availableGovernors.contains(CpuGovernor.POWERSAVE.governorName)) {
                add(PowerProfile(PerformancePreset.POWER_SAVE, CpuGovernor.POWERSAVE, minFreq, lowFreq))
            }

            // Balanced - schedutil is best for modern devices, falls back to conservative or interactive
            // For gaming, start at midFreq (50%) to ensure adequate performance while saving battery
            // Odin 3: 2227 MHz - 3532 MHz, RP6: 1344 MHz - 2016 MHz
            if (availableGovernors.contains(CpuGovernor.SCHEDUTIL.governorName)) {
                add(PowerProfile(PerformancePreset.BALANCED, CpuGovernor.SCHEDUTIL, midFreq, maxFreq))
            } else if (availableGovernors.contains(CpuGovernor.CONSERVATIVE.governorName)) {
                add(PowerProfile(PerformancePreset.BALANCED, CpuGovernor.CONSERVATIVE, midFreq, maxFreq))
            } else if (availableGovernors.contains(CpuGovernor.INTERACTIVE.governorName)) {
                add(PowerProfile(PerformancePreset.BALANCED, CpuGovernor.INTERACTIVE, midFreq, maxFreq))
            }

            // Performance - maximum performance with performance governor
            // Odin 3: 2918 MHz - 3532 MHz, RP6: 1785 MHz - 2016 MHz
            if (availableGovernors.contains(CpuGovernor.PERFORMANCE.governorName)) {
                add(PowerProfile(PerformancePreset.PERFORMANCE, CpuGovernor.PERFORMANCE, highFreq, maxFreq))
            }

            // On Demand - responsive but power-aware (legacy governor, not available on Odin 3 or RP6)
            if (availableGovernors.contains(CpuGovernor.ONDEMAND.governorName)) {
                add(PowerProfile(PerformancePreset.ON_DEMAND, CpuGovernor.ONDEMAND, minFreq, maxFreq))
            }

            // WALT (Window Assisted Load Tracking) - Qualcomm's scheduler-based governor
            // Odin 3: 384 MHz - 3532 MHz, RP6: 307 MHz - 2016 MHz
            if (availableGovernors.contains(CpuGovernor.WALT.governorName)) {
                add(PowerProfile(PerformancePreset.WALT, CpuGovernor.WALT, minFreq, maxFreq))
            }
        }
    }
}
