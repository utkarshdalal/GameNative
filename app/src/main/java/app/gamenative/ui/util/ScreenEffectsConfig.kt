package app.gamenative.ui.util

import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.effects.CRTEffect
import com.winlator.renderer.effects.ColorEffect
import com.winlator.renderer.effects.Effect
import com.winlator.renderer.effects.FSR1EasuEffect
import com.winlator.renderer.effects.FSR1RcasEffect
import com.winlator.renderer.effects.FXAAEffect
import com.winlator.renderer.effects.NTSCCombinedEffect
import com.winlator.renderer.effects.SGSR1Effect
import com.winlator.renderer.effects.ScalingModeEffect
import com.winlator.renderer.effects.ToonEffect
import com.winlator.renderer.effects.VividEffect
import kotlin.math.abs

data class ScreenEffectsConfig(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val gamma: Float = 1.0f,
    val scalingMode: Int = SCALING_MODE_NONE,
    val fsrSharpnessLevel: Int = FSR_DEFAULT_LEVEL,
    val enableToon: Boolean = false,
    val enableFXAA: Boolean = false,
    val enableVivid: Boolean = false,
    val enableCRT: Boolean = false,
    val enableNTSC: Boolean = false,
) {
    companion object {
        const val SCALING_MODE_NONE = 0
        const val SCALING_MODE_NEAREST = 1
        const val SCALING_MODE_LINEAR = 2
        const val SCALING_MODE_FILL = 3
        const val SCALING_MODE_STRETCH = 4
        const val SCALING_MODE_FSR = 5
        const val SCALING_MODE_FSR_ASPECT = 6
        const val SCALING_MODE_SGSR = 7
        const val SCALING_MODE_SGSR_ASPECT = 8
        const val SCALING_MODE_MAX = SCALING_MODE_SGSR_ASPECT

        const val FSR_MIN_LEVEL = 1
        const val FSR_MAX_LEVEL = 5
        const val FSR_DEFAULT_LEVEL = 3

        const val SGSR_MIN_PERCENT = 0
        const val SGSR_MAX_PERCENT = 100
        const val SGSR_DEFAULT_PERCENT = 50

        const val KEY_BRIGHTNESS = "screenEffectsBrightness"
        const val KEY_CONTRAST = "screenEffectsContrast"
        const val KEY_GAMMA = "screenEffectsGamma"
        const val KEY_SCALING_MODE = "screenEffectsScalingMode"
        const val KEY_FSR_SHARPNESS = "screenEffectsFsrSharpness"
        const val KEY_ENABLE_TOON = "screenEffectsEnableToon"
        const val KEY_ENABLE_FXAA = "screenEffectsEnableFXAA"
        const val KEY_ENABLE_VIVID = "screenEffectsEnableVivid"
        const val KEY_ENABLE_CRT = "screenEffectsEnableCRT"
        const val KEY_ENABLE_NTSC = "screenEffectsEnableNTSC"

        fun isFsrScalingMode(mode: Int): Boolean =
            mode == SCALING_MODE_FSR || mode == SCALING_MODE_FSR_ASPECT

        fun isSgsrScalingMode(mode: Int): Boolean =
            mode == SCALING_MODE_SGSR || mode == SCALING_MODE_SGSR_ASPECT
    }
}

private fun normalizeScalingMode(value: Int): Int = value.coerceIn(
    ScreenEffectsConfig.SCALING_MODE_NONE,
    ScreenEffectsConfig.SCALING_MODE_MAX,
)

private fun normalizeFsrSharpnessLevel(value: Int): Int = value.coerceIn(
    ScreenEffectsConfig.FSR_MIN_LEVEL,
    ScreenEffectsConfig.FSR_MAX_LEVEL,
)

fun loadScreenEffectsConfig(container: Container?): ScreenEffectsConfig {
    if (container == null) return ScreenEffectsConfig()
    return ScreenEffectsConfig(
        brightness = container.getExtra(ScreenEffectsConfig.KEY_BRIGHTNESS)?.toFloatOrNull() ?: 0f,
        contrast = container.getExtra(ScreenEffectsConfig.KEY_CONTRAST)?.toFloatOrNull() ?: 0f,
        gamma = container.getExtra(ScreenEffectsConfig.KEY_GAMMA)?.toFloatOrNull() ?: 1f,
        scalingMode = normalizeScalingMode(
            container.getExtra(ScreenEffectsConfig.KEY_SCALING_MODE)?.toIntOrNull()
                ?: ScreenEffectsConfig.SCALING_MODE_NONE,
        ),
        fsrSharpnessLevel = normalizeFsrSharpnessLevel(
            container.getExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS)?.toIntOrNull()
                ?: ScreenEffectsConfig.FSR_DEFAULT_LEVEL,
        ),
        enableToon = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_TOON)?.toBooleanStrictOrNull() ?: false,
        enableFXAA = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA)?.toBooleanStrictOrNull() ?: false,
        enableVivid = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID)?.toBooleanStrictOrNull() ?: false,
        enableCRT = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_CRT)?.toBooleanStrictOrNull() ?: false,
        enableNTSC = container.getExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC)?.toBooleanStrictOrNull() ?: false,
    )
}

fun persistScreenEffectsConfig(container: Container?, config: ScreenEffectsConfig) {
    if (container == null) return
    container.putExtra(ScreenEffectsConfig.KEY_BRIGHTNESS, config.brightness)
    container.putExtra(ScreenEffectsConfig.KEY_CONTRAST, config.contrast)
    container.putExtra(ScreenEffectsConfig.KEY_GAMMA, config.gamma)
    container.putExtra(ScreenEffectsConfig.KEY_SCALING_MODE, normalizeScalingMode(config.scalingMode))
    container.putExtra(ScreenEffectsConfig.KEY_FSR_SHARPNESS, normalizeFsrSharpnessLevel(config.fsrSharpnessLevel))
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_TOON, config.enableToon)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_FXAA, config.enableFXAA)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_VIVID, config.enableVivid)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_CRT, config.enableCRT)
    container.putExtra(ScreenEffectsConfig.KEY_ENABLE_NTSC, config.enableNTSC)
}

fun fsrQuickMenuLevelToStops(level: Int): Float {
    val clamped = normalizeFsrSharpnessLevel(level)
    return when (clamped) {
        1 -> 2.0f
        2 -> 1.5f
        3 -> 1.0f
        4 -> 0.5f
        else -> 0.0f
    }
}

fun applyScreenEffectsConfig(renderer: GLRenderer, config: ScreenEffectsConfig) {
    val composer = renderer.getEffectComposer()
    val effects = mutableListOf<Effect>()
    val scalingMode = normalizeScalingMode(config.scalingMode)

    when {
        ScreenEffectsConfig.isFsrScalingMode(scalingMode) -> {
            val easuEffect = composer.getEffect(FSR1EasuEffect::class.java) ?: FSR1EasuEffect()
            easuEffect.setPreserveAspect(scalingMode == ScreenEffectsConfig.SCALING_MODE_FSR_ASPECT)
            effects += easuEffect

            val rcasEffect = composer.getEffect(FSR1RcasEffect::class.java) ?: FSR1RcasEffect()
            rcasEffect.sharpnessStops = fsrQuickMenuLevelToStops(config.fsrSharpnessLevel)
            effects += rcasEffect
        }

        ScreenEffectsConfig.isSgsrScalingMode(scalingMode) -> {
            val sgsrEffect = composer.getEffect(SGSR1Effect::class.java) ?: SGSR1Effect()
            sgsrEffect.setPreserveAspect(scalingMode == ScreenEffectsConfig.SCALING_MODE_SGSR_ASPECT)
            effects += sgsrEffect
        }

        scalingMode == ScreenEffectsConfig.SCALING_MODE_NONE -> Unit

        else -> {
            val scalingEffect = composer.getEffect(ScalingModeEffect::class.java) ?: ScalingModeEffect()
            scalingEffect.mode = when (scalingMode) {
                ScreenEffectsConfig.SCALING_MODE_NEAREST -> ScalingModeEffect.Mode.NEAREST
                ScreenEffectsConfig.SCALING_MODE_FILL -> ScalingModeEffect.Mode.FILL
                ScreenEffectsConfig.SCALING_MODE_STRETCH -> ScalingModeEffect.Mode.STRETCH
                else -> ScalingModeEffect.Mode.LINEAR
            }
            effects += scalingEffect
        }
    }

    if (abs(config.brightness) > 0.001f || abs(config.contrast) > 0.001f || abs(config.gamma - 1.0f) > 0.001f) {
        val colorEffect = ColorEffect().apply {
            brightness = config.brightness / 100f
            contrast = config.contrast / 100f
            gamma = config.gamma
        }
        effects += colorEffect
    }

    if (config.enableToon) {
        effects += composer.getEffect(ToonEffect::class.java) ?: ToonEffect()
    }
    if (config.enableFXAA) {
        effects += composer.getEffect(FXAAEffect::class.java) ?: FXAAEffect()
    }
    if (config.enableVivid) {
        effects += composer.getEffect(VividEffect::class.java) ?: VividEffect()
    }
    if (config.enableCRT) {
        effects += composer.getEffect(CRTEffect::class.java) ?: CRTEffect()
    }
    if (config.enableNTSC) {
        effects += composer.getEffect(NTSCCombinedEffect::class.java) ?: NTSCCombinedEffect()
    }

    composer.setEffects(effects)
}
