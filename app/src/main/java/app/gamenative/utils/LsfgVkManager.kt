package app.gamenative.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.WindowManager
import java.util.concurrent.Executors
import app.gamenative.BuildConfig
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
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
    private const val CONFIG_RELATIVE_PATH = ".config/lsfg-vk/conf.toml"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val DLL_RELATIVE_DIR = ".local/share/lsfg-vk"
    private const val LIB_FILENAME = "liblsfg-vk-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_LS_frame_generation.json"
    private const val VERSION_FILENAME = ".lsfg_vk_runtime_version"

    // Relative path from implicit_layer.d back to lib/
    private const val MANIFEST_LIBRARY_PATH = "../../../lib/$LIB_FILENAME"

    // Process identifier written to conf.toml [[game]] exe field.
    // Under Wine, /proc/self/exe points to the Wine loader, so we use this
    // stable identifier instead. Set via LSFG_PROCESS env var.
    private const val PROCESS_EXE_IDENTIFIER = "gamenative-lsfg"

    // Container extra keys
    const val EXTRA_ARMED = "lsfgEnabled"
    const val EXTRA_MULTIPLIER = "lsfgMultiplier"
    const val EXTRA_FLOW_SCALE = "lsfgFlowScale"
    const val EXTRA_PERFORMANCE_MODE = "lsfgPerformanceMode"
    const val EXTRA_PRESENT_MODE = "lsfgPresentMode"

    // FPS limiter extras (owned by XServerScreen)
    private const val EXTRA_FPS_LIMITER_ENABLED = "fpsLimiterEnabled"
    private const val EXTRA_FPS_LIMITER_TARGET = "fpsLimiterTarget"

    // Written by the layer next to conf.toml; measured presented/base fps
    private const val STATS_RELATIVE_PATH = ".config/lsfg-vk/stats.txt"
    private const val STATS_FRESHNESS_MS = 2000L

    // Environment variables consumed by the lsfg-vk layer
    private const val ENV_DISABLE = "DISABLE_LSFG"
    private const val ENV_CONFIG = "LSFG_CONFIG"
    private const val ENV_PROCESS = "LSFG_PROCESS"

    // Current runtime version (bumped when the bundled .so changes)
    private const val RUNTIME_VERSION = "v1.3.3-android-arm64-v8a"

    // Asset path for manifest (still in assets)
    private const val ASSET_DIR = "lsfg_vk/android_arm64_v8a"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

    // ---- Public API --------------------------------------------------------

    /** Whether LSFG is supported for this container's variant. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** Whether LSFG is armed (enabled + Lossless.dll available in Steam dir) for this container. The DLL is copied into the container at launch time by ensureRuntimeInstalled(). */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
            isDllAvailable()

    /** Whether Lossless Scaling is installed (Lossless.dll exists in Steam dir). */
    @JvmStatic
    fun isDllAvailable(): Boolean = findSteamDll() != null

    /** Whether the user owns Lossless Scaling in their Steam library. */
    @JvmStatic
    fun ownsLosslessScaling(): Boolean =
        SteamService.getAppInfoOf(LOSSLESS_SCALING_APP_ID) != null

    /** Get the DLL path inside the container, or null if the copy doesn't exist. */
    @JvmStatic
    fun containerDllPath(container: Container): String? {
        val dllFile = File(container.rootDir, "$DLL_RELATIVE_DIR/$LOSSLESS_DLL_NAME")
        return dllFile.absolutePath.takeIf { dllFile.isFile }
    }

    /** Get the multiplier (0=Off, 2-4, default 2). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return if (raw == 0) 0 else raw.coerceIn(2, 4)
    }

    /** Get the flow scale (0.25-1.0, default 0.80). */
    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "0.80").toFloatOrNull()?.coerceIn(0.25f, 1.0f) ?: 0.80f

    /** Get whether performance mode is enabled (default true). */
    fun performanceMode(container: Container): Boolean =
        parseBool(container.getExtra(EXTRA_PERFORMANCE_MODE, "true"))

    /**
     * Swapchain present mode while frame generation runs ("mailbox" or
     * "fifo"). Mailbox is the default: the layer already paces vsync-locked,
     * and mesa's FIFO queue underneath it breaks the display cadence.
     */
    fun presentMode(container: Container): String =
        container.getExtra(EXTRA_PRESENT_MODE, "mailbox")
            .takeIf { it == "fifo" || it == "mailbox" } ?: "mailbox"

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
    @Volatile private var cachedMeasuredFps: Float? = null
    @Volatile private var lastStatsReadMs: Long = 0L

    /** Served from a cache refreshed off the main thread; callers poll ~1/s. */
    fun readMeasuredFps(container: Container): Float? {
        val now = System.currentTimeMillis()
        if (now - lastStatsReadMs >= 500L) {
            lastStatsReadMs = now
            vsyncWriteExecutor.execute {
                cachedMeasuredFps = try {
                    val statsFile = File(container.rootDir, STATS_RELATIVE_PATH)
                    if (statsFile.isFile &&
                        System.currentTimeMillis() - statsFile.lastModified() <= STATS_FRESHNESS_MS
                    ) {
                        statsFile.readLines()
                            .firstOrNull { it.startsWith("fps=") }
                            ?.substringAfter("fps=")
                            ?.toFloatOrNull()
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
            }
        }
        return cachedMeasuredFps
    }

    /**
     * Install the layer runtime + DLL into the container's filesystem.
     * Called during container startup in BionicProgramLauncherComponent.
     *
     * Installs:
     * - liblsfg-vk-layer.so → ~/.local/lib/
     * - VkLayer_LS_frame_generation.json → ~/.local/share/vulkan/implicit_layer.d/
     * - Lossless.dll → ~/.local/share/lsfg-vk/  (copied from Steam install dir)
     *
     * Uses versioned caching to skip redundant copies.
     *
     * @return true if installation succeeded or was already up-to-date
     */
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
        // We now copy the DLL directly from Steam install dir instead of creating a container
        deleteLosslessScalingContainerIfExists(context)

        // Copy Lossless.dll from Steam install dir into the container
        val dllFile = File(dllDir, LOSSLESS_DLL_NAME)
        val steamDll = findSteamDll()
        if (steamDll != null) {
            try {
                if (!dllFile.isFile || dllFile.length() != steamDll.length()) {
                    dllDir.mkdirs()
                    steamDll.inputStream().use { input ->
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
        } else if (parseBool(container.getExtra(EXTRA_ARMED, "false"))) {
            Timber.tag(TAG).w("LSFG enabled but Lossless.dll not found in Steam dir")
            success = false
        }

        return success
    }

    /**
     * Write the lsfg-vk conf.toml for this container.
     * The layer reads this on init to find the DLL and game settings.
     *
     * @return true if the config was written successfully
     */
    @JvmStatic
    fun writeConfig(container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val dllPath = containerDllPath(container)
            val savedMultiplier = multiplier(container)
            val frameGenActive = parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
                dllPath != null && savedMultiplier >= 2
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
            val ok = writeConfigAtomic(configFile, configText)
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write LSFG conf.toml")
            false
        }
    }

    /**
     * Apply LSFG-related environment variables to the launch environment.
     * Called during container startup in BionicProgramLauncherComponent.
     *
     * @return true if LSFG is armed and env vars were applied
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        // Clear any stale env vars first
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)
        envVars.remove(ENV_PROCESS)

        if (!isSupported(container)) {
            // Remove the manifest so the Vulkan loader can't find the layer
            disableLayerInContainer(container)
            return false
        }

        val dllPath = containerDllPath(container)
        val armed = parseBool(container.getExtra(EXTRA_ARMED, "false")) && dllPath != null

        if (!armed) {
            // Remove the manifest so the Vulkan loader can't find the layer
            disableLayerInContainer(container)
            Timber.tag(TAG).i("LSFG disabled (enabled=%s, dll=%s)",
                container.getExtra(EXTRA_ARMED, "false"), dllPath ?: "null")
            return false
        }

        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_PROCESS, PROCESS_EXE_IDENTIFIER)

        // Add the container's implicit_layer.d to VK_LAYER_PATH so the
        // Vulkan loader discovers the lsfg-vk layer installed there.
        // The static VK_LAYER_PATH only covers /usr/share/vulkan/implicit_layer.d,
        // but we install the layer into the container's ~/.local/share/vulkan/.
        val containerLayerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.isNotEmpty()) {
            envVars.put("VK_LAYER_PATH", "$existingLayerPath:${containerLayerDir.absolutePath}")
        } else {
            envVars.put("VK_LAYER_PATH", containerLayerDir.absolutePath)
        }

        Timber.tag(TAG).i(
            "LSFG armed: dll=%s, multiplier=%d, flowScale=%.2f, perf=%s",
            dllPath, multiplier(container), flowScale(container),
            if (performanceMode(container)) "on" else "off"
        )
        return true
    }

    /**
     * Remove the layer manifest so the Vulkan loader can't discover it.
     * Called when LSFG is disabled to ensure no stale layer is loaded.
     */
    private fun disableLayerInContainer(container: Container) {
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifest = File(layerDir, MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
            Timber.tag(TAG).d("Removed LSFG manifest to disable layer")
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
    private fun findSteamDll(): File? {
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

    // The layer rereads conf.toml on mtime change and must never observe a
    // half-written file.
    private fun writeConfigAtomic(file: File, text: String): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
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

    /** Parse boolean from container extra (handles "true"/"false" and "1"/"0"). */
    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"

    private fun formatFlowScale(value: Float): String =
        String.format(Locale.US, "%.2f", value.coerceIn(0.25f, 1.0f))

    /**
     * Delete the Lossless Scaling container if it exists.
     * This container is no longer needed since we copy the DLL directly from
     * the Steam install directory instead of creating a container for app 993090.
     * This saves storage space.
     */
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
     * @param performanceMode Whether performance mode is enabled
     * @return true if the config was updated successfully
     */
    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        performanceMode: Boolean,
        fpsLimitOverride: Int? = null,
    ): Boolean {
        if (!isSupported(container)) return false

        val dllPath = containerDllPath(container)
        val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)

        if (!configFile.exists()) {
            Timber.tag(TAG).w("conf.toml not found, cannot hot-reload")
            return false
        }

        return try {
            val frameGenActive = enabled && dllPath != null
            val configText = buildConfigToml(
                dllPath = dllPath,
                enabled = frameGenActive,
                multiplier = if (frameGenActive) multiplier.coerceIn(2, 4) else 1,
                flowScale = flowScale.coerceIn(0.25f, 1.0f),
                performanceMode = performanceMode && frameGenActive,
                fpsLimit = fpsLimitOverride ?: fpsLimit(container),
                presentMode = presentMode(container),
            )

            val ok = writeConfigAtomic(configFile, configText)
            if (ok) {
                Timber.tag(TAG).i(
                    "Hot-reloaded conf.toml: enabled=%s, multiplier=%d, flowScale=%.2f, perf=%s, fpsLimit=%d",
                    frameGenActive, multiplier, flowScale, performanceMode, fpsLimit(container)
                )
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to hot-reload conf.toml")
            false
        }
    }

}
