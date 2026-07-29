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
 * The layer is self-contained: the interpolation shaders are embedded in it, so
 * no external DLL or Steam purchase is required. It intercepts
 * vkCreateSwapchainKHR / vkQueuePresentKHR inside the container's Vulkan driver
 * and injects generated frames. Settings hot-reload because the layer polls the
 * config file timestamp during presentation.
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
    // config so its pacing engine engages instead of presenting frames in bursts
    private const val FPS_LIMITER_ENABLED_EXTRA = "fpsLimiterEnabled"
    private const val FPS_LIMITER_TARGET_EXTRA = "fpsLimiterTarget"
    private const val DEFAULT_FPS_LIMITER_TARGET_HZ = 60

    private const val ENV_ENABLE = "BIONIC_FG_ENABLE"
    private const val ENV_DISABLE = "BIONIC_FG_DISABLE"
    private const val ENV_CONFIG = "BIONIC_FG_CONFIG"

    // Bumped when the bundled .so changes
    private const val RUNTIME_VERSION = "b233e6e-clean-arm64-v8a"

    private const val ASSET_DIR = "bionic_fg/android_arm64_v8a"
    private const val ASSET_LIB = "$ASSET_DIR/$LIB_FILENAME"
    private const val ASSET_MANIFEST = "$ASSET_DIR/$MANIFEST_FILENAME"

    // ---- Public API --------------------------------------------------------

    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** LSFG takes precedence if both are enabled (the UI enforces exclusivity). */
    @JvmStatic
    fun isArmed(container: Container): Boolean =
        isSupported(container) &&
            parseBool(container.getExtra(EXTRA_ARMED, "false")) &&
            !LsfgVkManager.isArmed(container)

    /** Frame multiplier (0=Off, 2-4). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return if (raw == 0) 0 else raw.coerceIn(2, 4)
    }

    fun flowScale(container: Container): Float =
        container.getExtra(EXTRA_FLOW_SCALE, "0.60").toFloatOrNull()?.coerceIn(0.2f, 1.0f) ?: 0.60f

    /** Interpolation model (0=Standard, 1=Clear). Clear is the wider, default variant. */
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
     * Install the layer .so and manifest into the container's filesystem, where
     * the Vulkan loader discovers implicit layers. Versioned so repeat launches
     * skip the copy.
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
        if (installedVersion == RUNTIME_VERSION && libFile.isFile) {
            // applyLaunchEnv deletes the manifest whenever framegen is off, so a
            // missing manifest must not force a full reinstall of the
            // multi-megabyte layer on every launch. Restore just the manifest,
            // and only when it will actually be used.
            if (isArmed(container) && !manifestFile.isFile) {
                FileUtils.copy(context, ASSET_MANIFEST, manifestFile)
                if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
            }
            return true
        }

        return try {
            localLibDir.mkdirs()
            layerDir.mkdirs()

            // The bundled manifest's library_path is already relative to
            // implicit_layer.d (../../../lib/libbionic_fg.so), so no patching
            FileUtils.copy(context, ASSET_LIB, libFile)
            FileUtils.copy(context, ASSET_MANIFEST, manifestFile)
            FileUtils.writeString(versionFile, RUNTIME_VERSION)

            if (libFile.exists()) FileUtils.chmod(libFile, 0b111101101)
            if (manifestFile.exists()) FileUtils.chmod(manifestFile, 0b110100100)
            if (versionFile.exists()) FileUtils.chmod(versionFile, 0b110100100)

            val ok = libFile.isFile && manifestFile.isFile
            if (!ok) {
                Timber.tag(TAG).e("Runtime installation verification failed")
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install bionic-fg runtime")
            false
        }
    }

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

    @JvmStatic
    fun applyLaunchEnv(container: Container, envVars: EnvVars): Boolean {
        envVars.remove(ENV_ENABLE)
        envVars.remove(ENV_DISABLE)
        envVars.remove(ENV_CONFIG)

        if (!isSupported(container) || !isArmed(container)) {
            disableLayerInContainer(container)
            return false
        }

        envVars.put(ENV_ENABLE, "1")
        envVars.put(ENV_CONFIG, configFile(container).absolutePath)

        val containerLayerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingLayerPath = envVars["VK_LAYER_PATH"] ?: ""
        if (existingLayerPath.isNotEmpty()) {
            if (!existingLayerPath.contains(containerLayerDir.absolutePath)) {
                envVars.put("VK_LAYER_PATH", "$existingLayerPath:${containerLayerDir.absolutePath}")
            }
        } else {
            envVars.put("VK_LAYER_PATH", containerLayerDir.absolutePath)
        }
        return true
    }

    /** Remove the manifest so the Vulkan loader can't discover a disabled layer. */
    private fun disableLayerInContainer(container: Container) {
        val manifest = File(File(container.rootDir, LAYER_RELATIVE_DIR), MANIFEST_FILENAME)
        if (manifest.exists()) {
            manifest.delete()
        }
    }

    /**
     * Update conf.toml while the container is running. flow_scale and fps_limit
     * reload in place, multiplier/model rebuild the framegen context, and
     * multiplier = 0 turns generation off without recreating the app swapchain.
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
            Timber.tag(TAG).e(t, "Failed to hot-reload conf.toml")
            false
        }
    }

    // ---- Helpers -----------------------------------------------------------

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    // `enabled` stays true and on/off is expressed via multiplier = 0: the layer
    // treats an enabled flip as a swapchain-recreate event, while a multiplier
    // change hot-reloads against the existing swapchain.
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

    private fun parseBool(value: String): Boolean =
        value.equals("true", ignoreCase = true) || value == "1"
}
