package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import java.util.Locale
import timber.log.Timber
import kotlin.jvm.JvmStatic

object BionicFgManager {
    private const val TAG = "BionicFgManager"

    private const val ASSET_DIR = "bionic_fg/android_arm64_v8a"
    private const val LIB_FILENAME = "libbionic-fg-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_BIONIC_framegen.json"
    private const val VERSION_FILENAME = ".bionic_fg_runtime_version"
    private const val RUNTIME_VERSION = "10"

    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"
    private const val CONFIG_RELATIVE_PATH = ".config/bionic-fg/conf.toml"

    const val ENV_ENABLE = "BIONIC_FG_ENABLE"
    const val ENV_DISABLE = "DISABLE_BIONIC_FG"
    const val ENV_CONFIG = "BIONIC_FG_CONFIG"
    const val ENV_MULTIPLIER = "BIONIC_FG_MULTIPLIER"
    const val ENV_FLOW_SCALE = "BIONIC_FG_FLOW_SCALE"
    const val ENV_MODEL = "BIONIC_FG_MODEL"
    const val ENV_DEBUG_TIMING = "BIONIC_FG_DEBUG_TIMING"
    const val ENV_DEBUG_SUMMARY_EVERY = "BIONIC_FG_DEBUG_SUMMARY_EVERY"
    const val ENV_PACE_PRESENT = "BIONIC_FG_PACE_PRESENT"
    const val ENV_PACE_INTERVAL_MS = "BIONIC_FG_PACE_INTERVAL_MS"

    const val EXTRA_ENABLED = "bionicFgEnabled"
    const val EXTRA_MULTIPLIER = "bionicFgMultiplier"
    const val EXTRA_FLOW_SCALE = "bionicFgFlowScale"
    const val EXTRA_MODEL = "bionicFgModel"

    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    fun isEnabled(container: Container): Boolean =
        isSupported(container) && container.getExtra(EXTRA_ENABLED, "false") == "true"

    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return sanitizeMultiplier(raw)
    }

    fun flowScale(container: Container): Float {
        val raw = container.getExtra(EXTRA_FLOW_SCALE, "0.80").toFloatOrNull() ?: 0.80f
        return raw.coerceIn(0.25f, 1.0f)
    }

    fun model(container: Container): String {
        val raw = container.getExtra(EXTRA_MODEL, "0")
        return if (raw == "1") "1" else "0"
    }

    @JvmStatic
    fun ensureInstalled(context: Context, container: Container): Boolean {
        if (!isSupported(container)) return false

        val rootDir = container.rootDir
        val libFile = File(rootDir, "$LIB_RELATIVE_DIR/$LIB_FILENAME")
        val manifestFile = File(rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        val versionFile = File(rootDir, "$LAYER_RELATIVE_DIR/$VERSION_FILENAME")

        return try {
            File(rootDir, LIB_RELATIVE_DIR).mkdirs()
            File(rootDir, LAYER_RELATIVE_DIR).mkdirs()

            FileUtils.copy(context, "$ASSET_DIR/$LIB_FILENAME", libFile)
            FileUtils.chmod(libFile, 0b111101101)

            FileUtils.copy(context, "$ASSET_DIR/$MANIFEST_FILENAME", manifestFile)
            FileUtils.chmod(manifestFile, 0b110100100)

            versionFile.writeText(RUNTIME_VERSION)
            FileUtils.chmod(versionFile, 0b110100100)

            Timber.tag(TAG).i("Refreshed Bionic-FG in %s", rootDir)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install Bionic-FG")
            false
        }
    }

    @JvmStatic
    fun writeConfig(container: Container): Boolean =
        updateConfigAtRuntime(
            container = container,
            enabled = isEnabled(container),
            multiplier = multiplier(container),
            flowScale = flowScale(container),
            model = model(container),
        )

    @JvmStatic
    fun updateConfigAtRuntime(
        container: Container,
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        model: String,
    ): Boolean {
        if (!isSupported(container)) return false

        return try {
            val configFile = configFile(container)
            val sanitizedMultiplier = sanitizeMultiplier(multiplier)
            val sanitizedFlowScale = flowScale.coerceIn(0.25f, 1.0f)
            val sanitizedModel = sanitizeModel(model)
            val configText = buildConfigToml(
                enabled = enabled,
                multiplier = sanitizedMultiplier,
                flowScale = sanitizedFlowScale,
                model = sanitizedModel,
            )
            val ok = FileUtils.writeString(configFile, configText)
            if (ok && configFile.exists()) {
                FileUtils.chmod(configFile, 0b110100100)
                Timber.tag(TAG).i(
                    "Updated Bionic-FG config: enabled=%s, mult=%d, flow=%.2f, model=%s",
                    enabled,
                    sanitizedMultiplier,
                    sanitizedFlowScale,
                    sanitizedModel,
                )
            }
            ok
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to update Bionic-FG conf.toml")
            false
        }
    }

    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars): Boolean {
        listOf(
            ENV_ENABLE,
            ENV_DISABLE,
            ENV_CONFIG,
            ENV_MULTIPLIER,
            ENV_FLOW_SCALE,
            ENV_MODEL,
            ENV_DEBUG_TIMING,
            ENV_DEBUG_SUMMARY_EVERY,
            ENV_PACE_PRESENT,
            ENV_PACE_INTERVAL_MS,
        ).forEach {
            envVars.remove(it)
        }

        if (!isEnabled(container)) {
            disableLayerInContainer(container)
            envVars.put(ENV_DISABLE, "1")
            Timber.tag(TAG).d("Bionic-FG disabled")
            return false
        }

        ensureInstalled(context, container)
        writeConfig(container)

        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingPath = envVars["VK_LAYER_PATH"] ?: ""
        envVars.put(
            "VK_LAYER_PATH",
            if (existingPath.isNotEmpty()) "$existingPath:${layerDir.absolutePath}"
            else layerDir.absolutePath,
        )

        envVars.put(ENV_ENABLE, "1")
        envVars.remove(ENV_DISABLE)
        envVars.put(ENV_CONFIG, configFile(container).absolutePath)
        envVars.put(ENV_MULTIPLIER, multiplier(container).toString())
        envVars.put(ENV_FLOW_SCALE, String.format(Locale.US, "%.2f", flowScale(container)))
        envVars.put(ENV_MODEL, model(container))
        envVars.put(ENV_DEBUG_TIMING, "1")
        envVars.put(ENV_DEBUG_SUMMARY_EVERY, "60")
        envVars.put(ENV_PACE_PRESENT, "1")
        envVars.put(ENV_PACE_INTERVAL_MS, "8.333")

        Timber.tag(TAG).i(
            "Bionic-FG enabled: mult=%d, flow=%.2f, model=%s, debugTiming=%s, summaryEvery=%d, pacePresent=%s, paceIntervalMs=%.3f",
            multiplier(container),
            flowScale(container),
            model(container),
            true,
            60,
            true,
            8.333f,
        )
        return true
    }

    private fun configFile(container: Container): File =
        File(container.rootDir, CONFIG_RELATIVE_PATH)

    private fun sanitizeMultiplier(multiplier: Int): Int =
        if (multiplier < 2) 0 else multiplier.coerceIn(2, 4)

    private fun sanitizeModel(model: String): String =
        if (model == "1") "1" else "0"

    private fun buildConfigToml(
        enabled: Boolean,
        multiplier: Int,
        flowScale: Float,
        model: String,
    ): String = buildString {
        appendLine("version = 1")
        appendLine()
        appendLine("[global]")
        appendLine("enabled = ${if (enabled) "true" else "false"}")
        appendLine("multiplier = ${sanitizeMultiplier(multiplier)}")
        appendLine("flow_scale = ${String.format(Locale.US, "%.2f", flowScale.coerceIn(0.25f, 1.0f))}")
        appendLine("model = ${sanitizeModel(model)}")
    }

    private fun disableLayerInContainer(container: Container) {
        val manifestFile = File(container.rootDir, "$LAYER_RELATIVE_DIR/$MANIFEST_FILENAME")
        if (manifestFile.exists()) {
            if (manifestFile.delete()) {
                Timber.tag(TAG).d("Removed Bionic-FG manifest to disable layer")
            } else {
                Timber.tag(TAG).w("Failed to remove Bionic-FG manifest at %s", manifestFile.absolutePath)
            }
        }
    }
}
