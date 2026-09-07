package app.gamenative.utils

import app.gamenative.model.FrameGenPreset
import com.winlator.container.Container
import com.winlator.renderer.VulkanRenderer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class RollingFpsCounter(private val windowNs: Long = 1_000_000_000L) {
    private val maxSamples = 8
    private val sampleNano = LongArray(maxSamples)
    private val sampleTotal = LongArray(maxSamples)
    private val sampleReal = LongArray(maxSamples)
    private val sampleGenerated = LongArray(maxSamples)
    private var samplesStart = 0
    private var samplesCount = 0
    var outputFps = 0f
        private set
    var sourceFps = 0f
        private set
    var isGenerating = false
        private set

    @Synchronized
    fun sample(renderer: VulkanRenderer?): Float {
        if (renderer == null || !renderer.isFrameGenerationEnabled) {
            samplesCount = 0
            outputFps = 0f
            sourceFps = 0f
            isGenerating = false
            return 0f
        }
        val total = renderer.presentedFrameCount
        val generated = renderer.generatedFrameCount
        val real = renderer.realFrameCount
        val nowNano = System.nanoTime()

        val index: Int
        if (samplesCount == maxSamples) {
            index = (samplesStart + samplesCount - 1) % maxSamples
            samplesStart = (samplesStart + 1) % maxSamples
        } else {
            index = (samplesStart + samplesCount) % maxSamples
            samplesCount++
        }
        sampleNano[index] = nowNano
        sampleTotal[index] = total
        sampleReal[index] = real
        sampleGenerated[index] = generated

        if (samplesCount < 2) return if (renderer.isFrameGenerationEnabled) outputFps else 0f

        var baseline = samplesStart
        for (i in 0 until samplesCount - 1) {
            val candidate = (samplesStart + i) % maxSamples
            if (nowNano - sampleNano[candidate] >= windowNs) {
                baseline = candidate
            } else {
                break
            }
        }

        val elapsedNano = nowNano - sampleNano[baseline]
        val totalFrames = total - sampleTotal[baseline]
        val realFrames = real - sampleReal[baseline]
        val genFrames = generated - sampleGenerated[baseline]
        if (elapsedNano < 250_000_000L || totalFrames < 0) return if (renderer.isFrameGenerationEnabled) outputFps else 0f
        outputFps = (totalFrames * 1_000_000_000.0f) / elapsedNano
        sourceFps = (realFrames * 1_000_000_000.0f) / elapsedNano
        isGenerating = (genFrames > 0)
        return if (renderer.isFrameGenerationEnabled && outputFps > 0f) outputFps else 0f
    }
}

/** Helpers for Quick Menu LSFG state persistence and runtime hot-reload. */
object LsfgQuickMenuHelper {
    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
        val targetRate: Int = 0,
    )

    fun isAvailable(container: Container): Boolean =
        LsfgVkManager.isSupported(container) && LsfgVkManager.isArmed(container)

    fun readSettings(container: Container): Settings = Settings(
        multiplier = LsfgVkManager.multiplier(container),
        flowScale = LsfgVkManager.flowScale(container),
        performanceMode = LsfgVkManager.performanceMode(container),
        targetRate = LsfgVkManager.targetRate(container),
    )

    private val applyExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-apply").apply { isDaemon = true } }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    fun targetRate(container: Container): Int = LsfgVkManager.targetRate(container)

    /** Hot-apply an adaptive-cap step without persisting it: the user's saved
     *  limiter target survives interrupted sessions. */
    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val settings = readSettings(container)
            val effectiveEnabled = settings.multiplier >= 2 || settings.targetRate > 0
            LsfgVkManager.updateConfigAtRuntime(
                container,
                effectiveEnabled,
                if (settings.multiplier >= 2) settings.multiplier else 2,
                settings.flowScale,
                settings.performanceMode,
                fpsLimitOverride = capFps.coerceAtLeast(0),
                targetRate = settings.targetRate,
            )
        }
    }

    /** Persist the present mode and hot-apply it. */
    fun applyPresentMode(container: Container, mode: String) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, mode)
            container.rendererPresentMode = mode
            container.saveData()
            applySettings(container, readSettings(container))
        }
    }

    /** Persist the target rate and hot-apply it. */
    fun applyTargetRate(container: Container, targetRate: Int) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_TARGET_RATE, targetRate.toString())
            container.saveData()
            val settings = readSettings(container).copy(targetRate = targetRate)
            val effectiveEnabled = settings.multiplier >= 2 || targetRate > 0
            LsfgVkManager.updateConfigAtRuntime(
                container = container,
                enabled = effectiveEnabled,
                multiplier = if (settings.multiplier >= 2) settings.multiplier else 2,
                flowScale = settings.flowScale,
                performanceMode = settings.performanceMode,
                targetRate = targetRate,
            )
        }
    }

    fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    fun sanitizeFlowScale(flowScale: Float): Float =
        flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)
        val targetRate = settings.targetRate

        val flowScalePct = (flowScale * 100).roundToInt()
        val preset = FrameGenPreset.fromFlowScale(flowScalePct)
        val presetName = if (flowScalePct == preset.flowScale) preset.name else "CUSTOM"

        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.putExtra(LsfgVkManager.EXTRA_TARGET_RATE, targetRate.toString())
        container.putExtra(LsfgVkManager.EXTRA_PRESET, presetName)
        container.saveData()

        val effectiveEnabled = multiplier >= 2 || targetRate > 0
        val effectiveMultiplier = if (multiplier >= 2) multiplier else 2
        LsfgVkManager.updateConfigAtRuntime(
            container,
            effectiveEnabled,
            effectiveMultiplier,
            flowScale,
            settings.performanceMode,
            targetRate = targetRate,
        )
    }
}
