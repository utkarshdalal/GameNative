package app.gamenative.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.WindowManager
import java.util.concurrent.Executors
import app.gamenative.BuildConfig
import app.gamenative.PluviaApp
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import com.winlator.renderer.lsfg.LosslessScaling
import java.io.File
import java.util.Locale
import timber.log.Timber
import kotlin.jvm.JvmStatic

/**
 * Manages the lsfg-vk Vulkan implicit layer for frame generation.
 *
 * The layer works by intercepting vkQueuePresentKHR inside the container's
 * Vulkan driver and running Lossless Scaling frame generation (LSFG_3_1 /
 * LSFG_3_1P) transparently. No overlay, no MediaProjection — it hooks the
 * real swapchain presentation path.
 *
 * Flow:
 * 1. At launch time: install the layer .so + manifest into the container's
 *    filesystem where the Vulkan loader discovers implicit layers.
 * 2. Copy Lossless.dll from the Steam install dir (app 993090) into the
 *    container's ~/.local/share/lsfg-vk/ directory.
 * 3. Write conf.toml with the DLL path, multiplier, flow scale, and
 *    performance mode. Set env vars so the layer finds its config.
 * 4. At runtime: the Vulkan loader loads the layer, which hooks
 *    vkCreateSwapchainKHR / vkQueuePresentKHR and runs framegen on the
 *    game's actual swapchain images.
 */
object LsfgVkManager {
    private const val TAG = "LsfgVkManager"

    // Steam app ID for Lossless Scaling (used to auto-find the DLL)
    const val LOSSLESS_SCALING_APP_ID = 993090
    private const val LOSSLESS_DLL_NAME = "Lossless.dll"

    // Paths inside the container's HOME (relative to rootDir)
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val DLL_RELATIVE_DIR = ".local/share/lsfg-vk"
    private const val MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json"

    // Container extra keys
    const val EXTRA_ARMED = "lsfgEnabled"
    const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    const val EXTRA_PRESENT_MODE = "lsfgPresentMode"
    const val EXTRA_TARGET_RATE = "lsfgTargetRate"
    const val EXTRA_PRESET = "lsfgPreset"

    // FPS limiter extras (owned by XServerScreen)
    private const val EXTRA_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"
    private const val EXTRA_FPS_LIMITER_TARGET = "fpsLimiterTarget"

    // ---- Public API --------------------------------------------------------

    /** Whether LSFG is supported for this container's variant. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** Whether LSFG is armed (enabled + Lossless.dll available + Steam ownership) for this container. The DLL is copied into the container at launch time by ensureRuntimeInstalled(). */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            (parseBool(container.getExtra(EXTRA_ARMED, "false")) || parseBool(container.getExtra("frameGen", "0"))) &&
            isDllAvailable() &&
            ownsLosslessScaling()

    /** Whether Lossless Scaling is installed (Lossless.dll exists in internal storage, container, or Steam dir). */
    @JvmStatic
    @JvmOverloads
    fun isDllAvailable(context: Context? = null): Boolean {
        val ctx = context ?: PluviaApp.getAppContext()
        if (ctx != null) {
            if (LosslessScaling.getDllFile(ctx).isFile) return true
        }
        return findSteamDll() != null
    }

    /** Whether the user is signed into Steam and owns Lossless Scaling in their Steam library. */
    @JvmStatic
    fun ownsLosslessScaling(): Boolean =
        SteamService.isLoggedIn && SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID) != null

    /** Get the DLL path inside the container, or null if the copy doesn't exist. */
    @JvmStatic
    fun containerDllPath(container: Container): String? {
        val dllFile = File(container.rootDir, "$DLL_RELATIVE_DIR/$LOSSLESS_DLL_NAME")
        return dllFile.absolutePath.takeIf { dllFile.isFile }
    }

    /** Get the multiplier (0=Off, 2-4, default 2). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "").toIntOrNull()
            ?: container.getExtra("frameGenMultiplier", "2").toIntOrNull() ?: 2
        return if (raw == 0) 0 else raw.coerceIn(2, 4)
    }

    /** Get the flow scale (0.25-1.0, default 0.70). */
    fun flowScale(container: Container): Float {
        val raw = container.getExtra(EXTRA_FLOW_SCALE, "").toFloatOrNull()
        if (raw != null) return raw.coerceIn(0.25f, 1.0f)
        val fgFlow = container.getExtra("frameGenFlowScale", "").toIntOrNull()
        if (fgFlow != null) return (fgFlow / 100.0f).coerceIn(0.25f, 1.0f)
        return 0.70f
    }

    /** Get the target rate (0=Off, 60, 90, 120, 144). */
    fun targetRate(container: Container): Int {
        return container.getExtra(EXTRA_TARGET_RATE, "").toIntOrNull()
            ?: container.getExtra("frameGenTargetRate", "0").toIntOrNull() ?: 0
    }


    /**
     * Swapchain present mode while frame generation runs ("fifo" or
     * "mailbox"). FIFO is the default: universally supported across all
     * Vulkan implementations and Android surfaces without swapchain creation failures.
     */
    fun presentMode(container: Container): String =
        container.getExtra(EXTRA_PRESENT_MODE, "fifo")
            .takeIf { it == "fifo" || it == "mailbox" } ?: "fifo"

    /**
     * Base fps cap for the layer's limiter (0 = uncapped). The layer
     * phase-locks its schedule to the vsync grid published by
     * [startVsyncClock]; without that file it falls back to free-running.
     */
    fun fpsLimit(container: Container): Int {
        if (!parseBool(container.getExtra(EXTRA_FPS_LIMITER_ENABLED, "false"))) return 0
        return container.getExtra(EXTRA_FPS_LIMITER_TARGET, "0").toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    // ---- Vsync clock ------------------------------------------------------

    private var vsyncClockHandler: Handler? = null
    private val vsyncWriteExecutor by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "lsfg-vsync").apply { isDaemon = true } }
    }

    /**
     * Publish the display's vsync timestamp and period to vsync.txt next to
     * conf.toml, once a second, so the layer can phase-lock its frame limiter
     * to the display instead of free-running against it. Choreographer frame
     * timestamps are CLOCK_MONOTONIC, the clock the layer paces with.
     */
    @JvmStatic
    fun startVsyncClock(context: Context, container: Container) {
        stopVsyncClock()
        val file = File(container.rootDir, ".config/lsfg-vk/vsync.txt")
        val handler = Handler(Looper.getMainLooper())
        vsyncClockHandler = handler
        val tick = object : Runnable {
            override fun run() {
                if (vsyncClockHandler !== handler) return
                Choreographer.getInstance().postFrameCallback { frameTimeNanos ->
                    if (vsyncClockHandler !== handler) return@postFrameCallback
                    val refreshRate = runCatching {
                        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                            ?.defaultDisplay?.refreshRate
                    }.getOrNull()?.takeIf { it > 1f } ?: 60f
                    val periodNs = (1_000_000_000.0 / refreshRate).toLong()
                    vsyncWriteExecutor.execute {
                        runCatching {
                            file.parentFile?.mkdirs()
                            file.writeText("vsync_ns=$frameTimeNanos\nperiod_ns=$periodNs\n")
                        }
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(tick)
    }

    @JvmStatic
    fun stopVsyncClock() {
        vsyncClockHandler?.removeCallbacksAndMessages(null)
        vsyncClockHandler = null
    }

    /**
     * Read the fps the layer actually presented, measured on-device.
     * Returns null when the stats file is missing or stale (layer not running),
     * in which case callers should fall back to their own estimate.
     */
    @JvmStatic
    @Volatile private var lastPresentedFrames: Long = 0L
    @Volatile private var lastFpsTimeNs: Long = 0L
    @Volatile private var liveFps: Float? = null

    /** Served from host VulkanRenderer presented frame counter; callers poll ~1/s. */
    fun readMeasuredFps(container: Container): Float? {
        val vulkanRenderer = app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer
        if (vulkanRenderer != null) {
            val nowNs = System.nanoTime()
            val presented = vulkanRenderer.presentedFrameCount
            val dt = (nowNs - lastFpsTimeNs) / 1_000_000_000.0
            if (lastFpsTimeNs != 0L && dt >= 0.5) {
                val dFrames = presented - lastPresentedFrames
                if (dFrames >= 0) {
                    liveFps = (dFrames / dt).toFloat()
                }
                lastPresentedFrames = presented
                lastFpsTimeNs = nowNs
            } else if (lastFpsTimeNs == 0L) {
                lastPresentedFrames = presented
                lastFpsTimeNs = nowNs
            }
            return liveFps
        }
        return null
    }

    /**
     * Remove the layer manifest so the Vulkan loader can't discover it.
     * Called when LSFG is disabled to ensure no stale layer is loaded.
     */
    @JvmStatic
    fun disableLayerInContainer(container: Container) {
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifest = File(layerDir, MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
            Timber.tag(TAG).d("Removed LSFG manifest to disable layer")
        }
        val usrManifest = File(container.rootDir, "usr/share/vulkan/implicit_layer.d/$MANIFEST_FILENAME")
        if (usrManifest.exists()) {
            usrManifest.delete()
        }
    }

    // ---- DLL discovery -----------------------------------------------------

    /**
     * Find Lossless.dll in the Steam install directory for app 993090.
     * Returns the File if it exists, null otherwise.
     *
     * This function searches all possible Steam install paths directly
     * without creating a container for the Lossless Scaling app.
     */
    @JvmStatic
    fun findSteamDll(): File? {
        return findSteamDllDirect()
    }

    /**
     * Find Lossless.dll by searching all Steam install paths directly.
     * This avoids creating a container for app 993090 (Lossless Scaling).
     *
     * Search order:
     * 1. Check if app is already installed in any known Steam path
     * 2. Look for common install directory names
     *
     * @return File pointing to Lossless.dll if found, null otherwise
     */
    private fun findSteamDllDirect(): File? {
        // Get app info to find the install directory name
        val appInfo = SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID)
        val installDirName = appInfo?.installDir?.takeIf { it.isNotBlank() } ?: "Lossless Scaling"

        // Search all possible Steam install paths
        val searchPaths = SteamService.allInstallPaths

        for (basePath in searchPaths) {
            val appDir = File(basePath, installDirName)
            val dll = File(appDir, LOSSLESS_DLL_NAME)
            if (dll.isFile) {
                Timber.tag(TAG).d("Found Lossless.dll at: %s", dll.absolutePath)
                return dll
            }
        }

        // Fallback: search for any directory containing Lossless.dll in Steam paths
        for (basePath in searchPaths) {
            val baseDir = File(basePath)
            if (!baseDir.exists() || !baseDir.isDirectory) continue

            baseDir.listFiles()?.forEach { subDir ->
                if (subDir.isDirectory) {
                    val dll = File(subDir, LOSSLESS_DLL_NAME)
                    if (dll.isFile) {
                        Timber.tag(TAG).d("Found Lossless.dll in fallback search at: %s", dll.absolutePath)
                        return dll
                    }
                }
            }
        }

        Timber.tag(TAG).w("Lossless.dll not found in any Steam install path")
        return null
    }

    // ---- Helpers -----------------------------------------------------------

    /** Parse boolean from container extra (handles "true"/"false" and "1"/"0"). */
    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"

    // ---- Runtime hot-reload -----------------------------------------------

    /**
     * Update the lsfg-vk conf.toml while the container is running.
     * The layer detects the file timestamp change on the next present call
     * and returns VK_ERROR_OUT_OF_DATE_KHR, which forces a swapchain recreation
     * with the new settings.
     *
     * @param container The running container
     * @param enabled Whether frame generation is active (sets multiplier to 1 if false)
     * @param multiplier Frame generation multiplier (2-4)
     * @param flowScale Flow scale factor (0.25-1.0)
     * @return true if the config was updated successfully
     */
    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        fpsLimitOverride: Int? = null,
        targetRate: Int = targetRate(container),
    ): Boolean {
        val vulkanRenderer = app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer
        if (vulkanRenderer != null) {
            val ctx = app.gamenative.PluviaApp.xServerView?.context
            if (ctx != null) {
                val cache = com.winlator.renderer.lsfg.LosslessScaling.resolveOrBuildCache(ctx, container, true)
                if (cache != null && cache.isFile) {
                    vulkanRenderer.setFrameGenerationShaders(cache.absolutePath)
                }
            }
            val flowScalePct = Math.round(flowScale * 100f)
            vulkanRenderer.setFrameGenerationMode(
                if (multiplier >= 2) multiplier else 2,
                targetRate,
                flowScalePct
            )
            val refreshRate = if (ctx != null) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    ctx.display?.refreshRate ?: 60f
                } else {
                    val wm = ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
                    @Suppress("DEPRECATION")
                    wm?.defaultDisplay?.refreshRate ?: 60f
                }
            } else 60f
            val stored = container.getExtra(EXTRA_PRESENT_MODE, "")
            val pm = if (stored.isNotEmpty()) presentMode(container)
                     else container.rendererPresentMode.ifEmpty { "fifo" }
            val vkMode = when (pm.lowercase(java.util.Locale.ROOT)) {
                "mailbox" -> 1
                "immediate" -> 0
                "relaxed" -> 3
                else -> 2
            }
            vulkanRenderer.setVkPresentMode(vkMode)
            vulkanRenderer.setFrameGenerationRefreshRate(refreshRate)
            vulkanRenderer.setFrameGenerationEnabled(enabled)
            Timber.tag(TAG).i(
                "Configured host VulkanRenderer LSFG: enabled=%s, multiplier=%d, flowScale=%.2f, targetRate=%d",
                enabled, multiplier, flowScale, targetRate
            )
            return true
        }
        return false
    }

}
