package app.gamenative.utils

import app.gamenative.ui.screen.xserver.resolveDisplayX11
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

    /** Persist the backend and hot-apply it. */
    fun applyBackend(container: Container, backend: String) {
        applyExecutor.execute {
            container.putExtra(LsfgVkManager.EXTRA_BACKEND, backend)
            container.saveData()
            applySettings(container, readSettings(container))
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

    fun applySettings(container: Container, settings: Settings, fpsLimit: Int? = null) {
        val multiplier = sanitizeMultiplier(settings.multiplier)
        val flowScale = sanitizeFlowScale(settings.flowScale)

        container.putExtra(LsfgVkManager.EXTRA_MULTIPLIER, multiplier.toString())
        container.putExtra(LsfgVkManager.EXTRA_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale))
        container.putExtra(LsfgVkManager.EXTRA_PERFORMANCE_MODE, settings.performanceMode.toString())
        container.saveData()

        // The container's LSFG switch is the authority, not the multiplier. A
        // container whose switch is off keeps a stored multiplier (it is only
        // zeroed when the QuickMenu's own row turns generation off), so reading
        // the multiplier alone arms a session the user disabled. This path runs
        // at session start from the fps-limiter apply, which is how a title with
        // LSFG off still ended up with generation running in the presenter.
        val effectiveEnabled = LsfgVkManager.isArmed(container) && multiplier >= 2
        val effectiveMultiplier = if (effectiveEnabled) multiplier else 2

        if (resolveDisplayX11(container)) {
            LsfgVkManager.updateConfigAtRuntime(
                container,
                effectiveEnabled,
                effectiveMultiplier,
                flowScale,
                settings.performanceMode,
            )
            LsfgVkManager.refreshNativeRuntime(container)
        } else {
            // A gamescope session has no renderer to push into: the presenter gets
            // the new settings through the daemon. Synchronous, because the session
            // screen sends them right before the daemon starts and the presenter opens
            // its session from whatever the daemon holds at the first present.
            val applied = Settings(effectiveMultiplier, flowScale, settings.performanceMode)
            // `effectiveEnabled` carries "off": the presenter cannot infer it from
            // the multiplier, which is clamped to 2 when disabled.
            LsfgVkManager.applyDrmRuntime(
                container,
                applied,
                effectiveEnabled,
                fpsLimit ?: LsfgVkManager.gamescopeFpsLimit(container),
            )
        }
    }

    /** Push the container's saved settings to the presenter, for a launch. */
    @JvmStatic
    fun applyDrmSettings(container: Container) {
        val settings = readSettings(container)
        // Same authority as `applySettings`: a stored multiplier does not mean
        // the user wants generation, so the switch decides.
        val enabled = LsfgVkManager.isArmed(container) && settings.multiplier >= 2
        LsfgVkManager.applyDrmRuntime(
            container,
            Settings(if (enabled) settings.multiplier else 2, settings.flowScale, settings.performanceMode),
            enabled,
            LsfgVkManager.gamescopeFpsLimit(container),
        )
    }
}
