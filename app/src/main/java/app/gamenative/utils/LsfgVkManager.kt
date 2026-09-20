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
    const val EXTRA_BACKEND = "lsfgBackend"

    const val BACKEND_LEGACY = "legacy"
    const val BACKEND_NATIVE = "native"

    private const val RUNTIME_VERSION = "2026.04.1"
    private const val ASSET_DIR = "lsfg_vk/android_arm64_v8a"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val VERSION_FILENAME = ".lsfg_vk_runtime_version"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"
    private const val PROCESS_EXE_IDENTIFIER = "gamenative-lsfg"
    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val STATS_RELATIVE_PATH = ".config/lsfg-vk/stats.txt"
    private const val STATS_FRESHNESS_MS = 2000L

    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_CONFIG = "LSFG_CONFIG"
    private const val ENV_PROCESS = "LSFG_PROCESS"

    // FPS limiter extras (owned by XServerScreen)
    private const val EXTRA_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"
    private const val EXTRA_FPS_LIMITER_TARGET = "fpsLimiterTarget"
    private const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"

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
     * Frame-generation backend: the legacy lsfg-vk Vulkan layer inside the
     * container, or the native pipeline in the host renderer.
     */
    fun backend(container: Container): String =
        container.getExtra(EXTRA_BACKEND, BACKEND_NATIVE)
            .takeIf { it == BACKEND_LEGACY } ?: BACKEND_NATIVE

    @JvmStatic
    fun isNativeBackend(container: Container): Boolean =
        backend(container) == BACKEND_NATIVE

    fun performanceMode(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "false"))


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

    /** Served from host VulkanRenderer presented frame counter or layer stats; callers poll ~1/s. */
    @Volatile private var cachedMeasuredFps: Float? = null
    @Volatile private var lastStatsReadMs: Long = 0L

    @Volatile private var lastPresentedFrames: Long = 0L
    @Volatile private var lastFpsTimeNs: Long = 0L
    @Volatile private var liveFps: Float? = null

    fun readMeasuredFps(container: Container): Float? {
        if (!isNativeBackend(container)) {
            val now = System.currentTimeMillis()
            if (now - lastStatsReadMs >= 500L) {
                lastStatsReadMs = now
                val statsFile = File(container.rootDir, STATS_RELATIVE_PATH)
                cachedMeasuredFps = try {
                    if (statsFile.isFile && (now - statsFile.lastModified()) <= STATS_FRESHNESS_MS) {
                        val line = statsFile.useLines { lines ->
                            lines.firstOrNull { it.startsWith("fps=") }
                        }
                        line?.substringAfter("fps=")?.trim()?.toFloatOrNull()
                    } else null
                } catch (t: Throwable) {
                    null
                }
            }
            if (cachedMeasuredFps != null) return cachedMeasuredFps
        }

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

    @JvmStatic
    @Synchronized
    fun prepareNativeCache(context: Context, container: Container): String? {
        val cache = LosslessScaling.resolveOrBuildCache(context, container, true)
        return cache?.absolutePath
    }

    @JvmStatic
    fun ensureRuntimeInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        val rootDir = container.rootDir
        val localLibDir = File(rootDir, LIB_RELATIVE_DIR)
        val layerDir = File(rootDir, LAYER_RELATIVE_DIR)
        val dllDir = File(rootDir, DLL_RELATIVE_DIR)
        val libFile = File(localLibDir, LIB_FILENAME)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        val needsInstall = installedVersion != RUNTIME_VERSION ||
            !libFile.isFile || !manifestFile.isFile

        var success = true

        if (needsInstall) {
            try {
                localLibDir.mkdirs()
                layerDir.mkdirs()

                // Copy the layer .so from native library directory (jniLibs)
                val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
                val sourceLib = File(nativeLibDir, LIB_FILENAME)
                if (!sourceLib.exists()) {
                    Timber.tag(TAG).e("Native library not found: %s", sourceLib.absolutePath)
                    return false
                }
                sourceLib.inputStream().use { input ->
                    libFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                // Write the manifest with patched library_path
                val manifestText = context.assets.open(ASSET_MANIFEST)
                    .bufferedReader().use { it.readText() }
                    .replace(
                        "\"library_path\": \"$LIB_FILENAME\"",
                        "\"library_path\": \"$MANIFEST_LIBRARY_PATH\""
                    )
                FileUtils.writeString(manifestFile, manifestText)
                FileUtils.writeString(versionFile, RUNTIME_VERSION)

                // Set executable permissions
                if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
                if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
                if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

                val ok = libFile.isFile && manifestFile.isFile
                if (ok) {
                    Timber.tag(TAG).i("Installed LSFG runtime %s into %s", RUNTIME_VERSION, rootDir)
                } else {
                    Timber.tag(TAG).e("Runtime installation verification failed")
                    success = false
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to install LSFG runtime")
                success = false
            }
        } else {
            Timber.tag(TAG).d("Runtime %s already installed in %s", RUNTIME_VERSION, rootDir)
        }

        // Delete the Lossless Scaling container if it exists (no longer needed)
        deleteLosslessScalingContainerIfExists(context)

        // Copy Lossless.dll from Steam install dir (or internal store) into the container
        val dllFile = File(dllDir, LOSSLESS_DLL_NAME)
        val sourceDll = findSteamDll() ?: LosslessScaling.getDllFile(context).takeIf { it.isFile }
        if (sourceDll != null) {
            try {
                if (!dllFile.isFile || dllFile.length() != sourceDll.length()) {
                    dllDir.mkdirs()
                    sourceDll.inputStream().use { input ->
                        dllFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (dllFile.exists()) FileUtils.chmod(dllFile, 0b110100100)
                    Timber.tag(TAG).i("Copied Lossless.dll (%d bytes) into %s", dllFile.length(), dllDir)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to copy Lossless.dll into container")
                success = false
            }
        } else if (isArmed(container)) {
            Timber.tag(TAG).w("LSFG enabled but Lossless.dll not found in Steam dir or app store")
            success = false
        }

        return success
    }

    @JvmStatic
    fun writeConfig(container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val dllPath = containerDllPath(container)
            val savedMultiplier = multiplier(container)
            val isNative = isNativeBackend(container)
            val frameGenActive = parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
                dllPath != null && savedMultiplier >= 2 && !isNative
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = frameGenActive,
                multiplier = if (frameGenActive) savedMultiplier else 1,
                flowScale = flowScale(container),
                performanceMode = performanceMode(container) && frameGenActive,
                fpsLimit = fpsLimit(container),
                presentMode = presentMode(container),
            )
            writeConfigAtomic(configFile, configText)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write LSFG conf.toml")
            false
        }
    }

    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROCESS)

        if (!isSupported(container)) {
            disableLayerInContainer(container)
            return false
        }

        val dllPath = containerDllPath(container)
        val armed = isArmed(container) && dllPath != null

        if (!armed) {
            disableLayerInContainer(container)
            Timber.tag(TAG).i("LSFG disabled (enabled=%s, dll=%s)",
                container.getExtra(EXTRA_ARMED, "false"), dllPath ?: "null")
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_PROCESS, PROCESS_EXE_IDENTIFIER)

        val containerLayerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.isNotEmpty()) {
            envVars.put("VK_LAYER_PATH", "$existingLayerPath:${containerLayerDir.absolutePath}")
        } else {
            envVars.put("VK_LAYER_PATH", containerLayerDir.absolutePath)
        }

        Timber.tag(TAG).i(
            "LSFG armed: backend=%s, dll=%s, multiplier=%d, flowScale=%.2f, perf=%s",
            backend(container), dllPath, multiplier(container), flowScale(container),
            if (performanceMode(container)) "on" else "off"
        )
        return true
    }

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

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    private fun writeConfigAtomic(file: File, text: String): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            if (!FileUtils.writeString(tmp, text)) return false
            FileUtils.chmod(tmp, 0b110100100)
            tmp.renameTo(file)
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    private fun buildConfigToml(
        dllPath: String?,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        fpsLimit: Int,
        presentMode: String,
    ): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        if (!dllPath.isNullOrBlank()) {
            appendLine("dll = ${tomlString(dllPath)}")
        }
        appendLine("no_fp16 = false")
        appendLine()

        if (!dllPath.isNullOrBlank()) {
            val effectiveMultiplier = if (enabled) multiplier.coerceIn(2, 4) else 1
            appendLine("[[game]]")
            appendLine("exe = ${tomlString(PROCESS_EXE_IDENTIFIER)}")
            appendLine("multiplier = $effectiveMultiplier")
            appendLine("flow_scale = ${formatFlowScale(flowScale)}")
            appendLine("performance_mode = ${if (enabled && performanceMode) "true" else "false"}")
            appendLine("hdr_mode = false")
            appendLine("fps_limit = ${fpsLimit.coerceAtLeast(0)}")
            appendLine("experimental_present_mode = ${tomlString(if (enabled) presentMode else "fifo")}")
        }
    }

    private fun tomlString(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun formatFlowScale(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))

    private fun deleteLosslessScalingContainerIfExists(context: Context) {
        try {
            val containerManager = ContainerManager(context)
            val losslessContainer = containerManager.getContainerById("STEAM_$LOSSLESS_SCALING_APP_ID")
            if (losslessContainer != null) {
                Timber.tag(TAG).i("Deleting Lossless Scaling container to save storage")
                if (FileUtils.delete(losslessContainer.rootDir)) {
                    containerManager.containers.remove(losslessContainer)
                    Timber.tag(TAG).i("Successfully deleted Lossless Scaling container")
                } else {
                    Timber.tag(TAG).w("Failed to delete Lossless Scaling container directory")
                }
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "Error while trying to delete Lossless Scaling container")
        }
    }

    /** Parse boolean from container extra (handles "true"/"false" and "1"/"0"). */
    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"

    // ---- Runtime hot-reload -----------------------------------------------

    /**
     * Update runtime frame generation settings while the container is running.
     * In Native backend, configures the host VulkanRenderer.
     * In Legacy backend, updates conf.toml which the layer detects on the next present.
     *
     * @param container The running container
     * @param enabled Whether frame generation is active
     * @param multiplier Frame generation multiplier (2-4)
     * @param flowScale Flow scale factor (0.25-1.0)
     * @return true if config was updated successfully
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
        val isNative = isNativeBackend(container)
        val vulkanRenderer = app.gamenative.PluviaApp.xServerView?.renderer as? com.winlator.renderer.VulkanRenderer
        if (vulkanRenderer != null) {
            val ctx = app.gamenative.PluviaApp.xServerView?.context
            if (ctx != null && isNative) {
                val cache = com.winlator.renderer.lsfg.LosslessScaling.resolveOrBuildCache(ctx, container, true)
                if (cache != null && cache.isFile) {
                    vulkanRenderer.setFrameGenerationShaders(cache.absolutePath)
                }
            }
            val flowScalePct = Math.round(flowScale * 100f)
            vulkanRenderer.setFrameGenerationMode(
                if (multiplier >= 2) multiplier else 2,
                if (isNative) targetRate else 0,
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
            vulkanRenderer.setFrameGenerationEnabled(enabled && isNative)
            Timber.tag(TAG).i(
                "Configured host VulkanRenderer LSFG: enabled=%s (isNative=%s), multiplier=%d, flowScale=%.2f, targetRate=%d",
                enabled && isNative, isNative, multiplier, flowScale, targetRate
            )
        }

        // Also update conf.toml for legacy layer
        val dllPath = containerDllPath(container)
        val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
        if (configFile.exists() && dllPath != null) {
            try {
                val frameGenActive = enabled && !isNative
                val configText = buildConfigToml(
                    dllPath = dllPath,
                    enabled = frameGenActive,
                    multiplier = if (frameGenActive) multiplier.coerceIn(2, 4) else 1,
                    flowScale = flowScale.coerceIn(0.25f, 1.0f),
                    performanceMode = performanceMode(container) && frameGenActive,
                    fpsLimit = fpsLimitOverride ?: fpsLimit(container),
                    presentMode = presentMode(container),
                )
                val ok = writeConfigAtomic(configFile, configText)
                if (ok) {
                    Timber.tag(TAG).i(
                        "Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f",
                        frameGenActive, multiplier, flowScale
                    )
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "Failed to hot-reload conf.toml")
            }
        }

        return true
    }

}
