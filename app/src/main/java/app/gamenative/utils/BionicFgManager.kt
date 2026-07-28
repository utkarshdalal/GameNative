package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.Locale
import timber.log.Timber
import kotlin.jvm.JvmStatic

/**
 * Manages the bionic-fg Vulkan implicit layer for AI frame generation.
 *
 * Unlike LSFG, bionic-fg is fully self-contained: the interpolation shaders
 * are embedded in the layer itself, so no external DLL or Steam purchase is
 * required. The layer intercepts vkCreateSwapchainKHR / vkQueuePresentKHR
 * inside the container's Vulkan driver and injects generated frames.
 *
 * Flow:
 * 1. At launch time: install the layer .so + manifest into the container's
 *    filesystem where the Vulkan loader discovers implicit layers.
 * 2. Write conf.toml with multiplier, flow scale, and model. Set
 *    BIONIC_FG_ENABLE / BIONIC_FG_CONFIG so the loader enables the layer
 *    and the layer finds its config.
 * 3. At runtime: the layer polls the config file timestamp during
 *    presentation, so settings hot-reload without restarting the game.
 */
object BionicFgManager {
    private const val TAG = "BionicFgManager"

    // Paths inside the container's HOME (relative to rootDir)
    private const val CONFIG_RELATIVE_PATH = ".config/bionic-fg/conf.toml"
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val LIB_FILENAME = "libbionic_fg.so"
    private const val MANIFEST_FILENAME = "VkLayer_BIONIC_framegen.json"
    private const val VERSION_FILENAME = ".bionic_fg_runtime_version"

    // Container extra keys
    const val EXTRA_ARMED = "bfgEnabled"
    const val EXTRA_MULTIPLIER = "bfgMultiplier"
    const val EXTRA_FLOW_SCALE = "bfgFlowScale"
    const val EXTRA_MODEL = "bfgModel"

    // App fps limiter extras (owned by XServerScreen); mirrored into the layer
    // config so its pacing engine (cadence deadlines, even spacing of generated
    // presents) engages instead of presenting frames in bursts
    private const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
    private const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"
    private const val DEFAULT_FPS_LIMITER_TARGET_HZ = 60

    // Environment variables consumed by the Vulkan loader / layer
    private const val ENV_ENABLE = "BIONIC_FG_ENABLE"
    private const val ENV_DISABLE = "BIONIC_FG_DISABLE"
    private const val ENV_CONFIG = "BIONIC_FG_CONFIG"

    // Current runtime version (bumped when the bundled .so changes)
    private const val RUNTIME_VERSION = "2cd5ef9-clean-arm64-v8a"

    // Asset paths
    private const val ASSET_DIR = "bionic_fg/android_arm64_v8a"
    private const val ASSET_LIB = "$ASSET_DIR/$LIB_FILENAME"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

    // ---- Public API --------------------------------------------------------

    /** Whether bionic-fg is supported for this container's variant. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /**
     * Whether bionic-fg is armed for this container. LSFG takes precedence if
     * both are somehow enabled (the UI enforces mutual exclusivity).
     */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
            !LsfgVkManager.isArmed(container)

    /** Get the multiplier (0=Off, 2-4, default 2). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return if (raw == 0) 0 else raw.coerceIn(2, 4)
    }

    /** Get the flow scale (0.2-1.0, default 0.60). */
    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "0.60").toFloatOrNull()?.coerceIn(0.2f, 1.0f) ?: 0.60f

    /**
     * Get the interpolation model (0=Standard, 1=Clear). Clear is the default:
     * it is the runtime-traced GameHub graph (flow stages at 1/5 scale and
     * below), while the reconstructed Standard graph dispatches its flow chain
     * at full resolution and costs ~25x as much GPU time.
     */
    fun model(container: Container): Int =
        (container.getExtra(EXTRA_MODEL, "1").toIntOrNull() ?: 1).coerceIn(0, 1)

    /** The app fps limiter target, or 0 when the limiter is off (layer semantics). */
    private fun fpsLimit(container: Container): Int {
        val enabled = parseBool(container.getExtra(FPS_LIMITER_ENABLED_EXTRA, "true"))
        if (!enabled) return 0
        return container.getExtra(FPS_LIMITER_TARGET_EXTRA, "").toIntOrNull()
            ?.coerceIn(10, 200) ?: DEFAULT_FPS_LIMITER_TARGET_HZ
    }

    /**
     * Install the layer runtime into the container's filesystem.
     * Called during container startup in BionicProgramLauncherComponent.
     *
     * Installs:
     * - libbionic_fg.so → ~/.local/lib/
     * - VkLayer_BIONIC_framegen.json → ~/.local/share/vulkan/implicit_layer.d/
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
        val libFile = File(localLibDir, LIB_FILENAME)
        val manifestFile = File(layerDir, MANIFEST_FILENAME)
        val versionFile = File(layerDir, VERSION_FILENAME)

        val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        if (installedVersion == RUNTIME_VERSION && libFile.isFile && manifestFile.isFile) {
            Timber.tag(TAG).d("Runtime %s already installed in %s", RUNTIME_VERSION, rootDir)
            return true
        }

        return try {
            localLibDir.mkdirs()
            layerDir.mkdirs()

            // The bundled manifest already uses a library_path relative to
            // implicit_layer.d (../../../lib/libbionic_fg.so), so no patching
            FileUtils.copy(context, ASSET_LIB, libFile)
            FileUtils.copy(context, ASSET_MANIFEST, manifestFile)
            FileUtils.writeString(versionFile, RUNTIME_VERSION)

            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
            if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

            val ok = libFile.isFile && manifestFile.isFile
            if (ok) {
                Timber.tag(TAG).i("Installed bionic-fg runtime %s into %s", RUNTIME_VERSION, rootDir)
            } else {
                Timber.tag(TAG).e("Runtime installation verification failed")
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install bionic-fg runtime")
            false
        }
    }

    /**
     * Write the bionic-fg conf.toml for this container.
     *
     * @return true if the config was written successfully
     */
    @JvmStatic
    fun writeConfig(container: Container): Boolean {
        if (!isSupported(container)) return false

        return try {
            val armed = isArmed(container)
            val savedMultiplier = multiplier(container)
            val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
            configFile.parentFile?.mkdirs()
            val configText = buildConfigToml(
                multiplier = if (armed && savedMultiplier >= 2) savedMultiplier else 0,
                flowScale = flowScale(container),
                model = model(container),
                fpsLimit = fpsLimit(container),
            )
            val ok = FileUtils.writeString(configFile, configText)
            if (ok && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to write bionic-fg conf.toml")
            false
        }
    }

    /**
     * Apply bionic-fg environment variables to the launch environment.
     * Called during container startup in BionicProgramLauncherComponent.
     *
     * @return true if bionic-fg is armed and env vars were applied
     */
    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_ENABLE)
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)

        if (!isSupported(container) || !isArmed(container)) {
            // Remove the manifest so the Vulkan loader can't find the layer
            disableLayerInContainer(container)
            return false
        }

        envVars.put(ENV_ENABLE, "1")
        envVars.put(ENV_CONFIG, configFile(container).absolutePath)

        // Add the container's implicit_layer.d to VK_LAYER_PATH so the
        // Vulkan loader discovers the layer installed there
        val containerLayerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.isNotEmpty()) {
            if (!existingLayerPath.contains(containerLayerDir.absolutePath)) {
                envVars.put("VK_LAYER_PATH", "$existingLayerPath:${containerLayerDir.absolutePath}")
            }
        } else {
            envVars.put("VK_LAYER_PATH", containerLayerDir.absolutePath)
        }

        Timber.tag(TAG).i(
            "bionic-fg armed: multiplier=%d, flowScale=%.2f, model=%d",
            multiplier(container), flowScale(container), model(container),
        )
        return true
    }

    /**
     * Remove the layer manifest so the Vulkan loader can't discover it.
     * Called when bionic-fg is disabled to ensure no stale layer is loaded.
     */
    private fun disableLayerInContainer(container: Container) {
        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val manifest = File(layerDir, MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
            Timber.tag(TAG).d("Removed bionic-fg manifest to disable layer")
        }
    }

    // ---- Runtime hot-reload -----------------------------------------------

    /**
     * Update conf.toml from the current container extras while the container
     * is running. The layer polls the file timestamp during presentation:
     * flow_scale and fps_limit reload in place, multiplier/model rebuild the
     * framegen context, and multiplier = 0 turns frame generation off without
     * recreating the app swapchain. Called after quick-menu framegen changes
     * and after fps-limiter changes (the limiter feeds the layer's pacing).
     *
     * @return true if the config was updated successfully
     */
    @JvmStatic
    fun updateConfigAtRuntime(container: Container): Boolean {
        if (!isSupported(container)) return false

        val configFile = File(container.rootDir, CONFIG_RELATIVE_PATH)
        if (!configFile.exists()) {
            Timber.tag(TAG).w("conf.toml not found, cannot hot-reload")
            return false
        }

        return try {
            val armed = isArmed(container)
            val savedMultiplier = multiplier(container)
            val effectiveMultiplier = if (armed && savedMultiplier >= 2) savedMultiplier else 0
            val fpsLimit = fpsLimit(container)
            val configText = buildConfigToml(
                multiplier = effectiveMultiplier,
                flowScale = flowScale(container),
                model = model(container),
                fpsLimit = fpsLimit,
            )
            val ok = FileUtils.writeString(configFile, configText)
            if (ok && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
            }
            if (ok) {
                Timber.tag(TAG).i(
                    "Hot-reloaded conf.toml: multiplier=%d, flowScale=%.2f, model=%d, fpsLimit=%d",
                    effectiveMultiplier, flowScale(container), model(container), fpsLimit,
                )
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to hot-reload conf.toml")
            false
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    // `enabled` stays true and on/off is expressed via multiplier = 0: the
    // layer treats an enabled flip as a swapchain-recreate event, while a
    // multiplier change hot-reloads against the existing swapchain.
    // fps_limit > 0 activates the layer's base pacer, generated-frame cadence
    // deadlines, and (with even_pace) even spacing of generated presents.
    private fun buildConfigToml(
        multiplier: Int,
        flowScale: Float,
        model: Int,
        fpsLimit: Int,
    ): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        appendLine("enabled = true")
        appendLine("multiplier = $multiplier")
        appendLine("flow_scale = ${String.format(Locale.US, "%.2f", flowScale)}")
        appendLine("model = $model")
        appendLine("fps_limit = $fpsLimit")
        appendLine("even_pace = true")
    }

    /** Parse boolean from container extra (handles "true"/"false" and "1"/"0"). */
    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"
}
