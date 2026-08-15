package app.gamenative.utils

import com.winlator.container.Container
import java.util.Locale
import java.util.concurrent.Executors

/** Helpers for Quick Menu LSFG state persistence and runtime hot-reload. */
object LsfgQuickMenuHelper {
    data class Settings(
        val multiplier: Int,
        val flowScale: Float,
        val performanceMode: Boolean,
    )

    fun isAvailable(container: Container): Boolean =
        LsfgVkManager.isSupported(container) && LsfgVkManager.isArmed(container)

    fun readSettings(container: Container): Settings = Settings(
        multiplier = LsfgVkManager.multiplier(container),
        flowScale = LsfgVkManager.flowScale(container),
        performanceMode = LsfgVkManager.performanceMode(container),
    )

    private val applyExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-apply").apply { isDaemon = true } }

    fun presentMode(container: Container): String = LsfgVkManager.presentMode(container)

    /** Hot-apply an adaptive-cap step without persisting it: the user's saved
     *  limiter target survives interrupted sessions. */
    fun applyLiveFpsCap(container: Container, capFps: Int) {
        applyExecutor.execute {
            val settings = readSettings(container)
            LsfgVkManager.updateConfigAtRuntime(
                container,
                settings.multiplier >= 2,
                if (settings.multiplier >= 2) settings.multiplier else 2,
                settings.flowScale,
                settings.performanceMode,
                fpsLimitOverride = capFps.coerceAtLeast(0),
            )
        }
    }

    /** Persist the present mode and hot-apply it. */
    fun applyPresentMode(container: Container, mode: String) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_PRESENT_MODE, mode)
            container.saveData()
            applySettings(container, readSettings(container))
        }
    }

    fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    fun sanitizeFlowScale(flowScale: Float): Float =
        flowScale.coerceIn(0.25f, 1.0f)

    fun applySettings(container: Container, settings: Settings) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)

        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.saveData()

        val effectiveEnabled = multiplier >= 2
        val effectiveMultiplier = if (effectiveEnabled) multiplier else 2
        LsfgVkManager.updateConfigAtRuntime(
            container,
            effectiveEnabled,
            effectiveMultiplier,
            flowScale,
            settings.performanceMode,
        )
    }
}
