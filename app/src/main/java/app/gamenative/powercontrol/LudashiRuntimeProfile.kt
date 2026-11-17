package app.gamenative.powercontrol

import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet

/**
 * Applies a LUDASHI preset to one container's real Windows runtime configuration.
 *
 * The preset deliberately changes only settings which are already consumed by GameNative:
 * guest resolution, Box64/FEXCore dynarec preset, and DXVK frame-rate limit.
 * GPU driver choice, CPU affinity, clocks and Android thermal policy are left untouched.
 */
object LudashiRuntimeProfile {
    fun apply(
        original: ContainerData,
        mode: LudashiPerformanceMode,
    ): ContainerData {
        val dxvkConfig = KeyValueSet(original.dxwrapperConfig).apply {
            put("framerate", mode.suggestedFps.toString())
        }

        return original.copy(
            screenSize = mode.runtimeResolution,
            box64Preset = mode.box64Preset,
            fexcorePreset = mode.fexcorePreset,
            dxwrapperConfig = dxvkConfig.toString(),
        )
    }
}
