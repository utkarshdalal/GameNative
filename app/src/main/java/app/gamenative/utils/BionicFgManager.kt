package app.gamenative.utils

import android.content.Context
import com.winlator.container.Container
import com.winlator.core.FileUtils
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber
import kotlin.jvm.JvmStatic

/**
 * Minimal Bionic-FG layer manager.
 * Prebuilt .so asset + env var activation. No hot-reload.
 */
object BionicFgManager {
    private const val TAG = "BionicFgManager"

    // Prebuilt asset paths (bundled in APK)
    private const val ASSET_DIR = "bionic_fg/android_arm64_v8a"
    private const val LIB_FILENAME = "libbionic-fg-layer.so"
    private const val MANIFEST_FILENAME = "VkLayer_BIONIC_framegen.json"
    private const val VERSION_FILENAME = ".bionic_fg_runtime_version"
    private const val RUNTIME_VERSION = "7"

    // Install paths inside container
    private const val LIB_RELATIVE_DIR = ".local/lib"
    private const val LAYER_RELATIVE_DIR = ".local/share/vulkan/implicit_layer.d"

    // Environment variables consumed by Bionic-FG layer
    const val ENV_ENABLE = "BIONIC_FG_ENABLE"
    const val ENV_DISABLE = "DISABLE_BIONIC_FG"
    const val ENV_MULTIPLIER = "BIONIC_FG_MULTIPLIER"
    const val ENV_FLOW_SCALE = "BIONIC_FG_FLOW_SCALE"
    const val ENV_MODEL = "BIONIC_FG_MODEL"

    // Container extra keys (persisted settings)
    const val EXTRA_ENABLED = "bionicFgEnabled"
    const val EXTRA_MULTIPLIER = "bionicFgMultiplier"  // "2", "3", "4"
    const val EXTRA_FLOW_SCALE = "bionicFgFlowScale"     // "0.80"
    const val EXTRA_MODEL = "bionicFgModel"              // "0" or "1"

    /** Only Bionic containers supported. */
    @JvmStatic
    fun isSupported(container: Container): Boolean =
        container.containerVariant.equals(Container.BIONIC, ignoreCase = true)

    /** Is Bionic-FG enabled for this container? */
    fun isEnabled(container: Container): Boolean =
        isSupported(container) && container.getExtra(EXTRA_ENABLED, "false") == "true"

    /** Get multiplier (2-4, default 2). */
    fun multiplier(container: Container): Int {
        val raw = container.getExtra(EXTRA_MULTIPLIER, "2").toIntOrNull() ?: 2
        return raw.coerceIn(2, 4)
    }

    /** Get flow scale (0.25-1.0, default 0.80). */
    fun flowScale(container: Container): Float {
        val raw = container.getExtra(EXTRA_FLOW_SCALE, "0.80").toFloatOrNull() ?: 0.80f
        return raw.coerceIn(0.25f, 1.0f)
    }

    /** Get model ("0" or "1", default "0"). */
    fun model(container: Container): String {
        val raw = container.getExtra(EXTRA_MODEL, "0")
        return if (raw == "1") "1" else "0"
    }

    /**
     * Install layer files into container (if not already present).
     */
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

            val installedVersion = versionFile.takeIf { it.exists() }?.readText()?.trim()
            if (!libFile.exists() || installedVersion != RUNTIME_VERSION) {
                FileUtils.copy(context, "$ASSET_DIR/$LIB_FILENAME", libFile)
                FileUtils.chmod(libFile, 0b111101101)
                versionFile.writeText(RUNTIME_VERSION)
                FileUtils.chmod(versionFile, 0b110100100)
            }

            // Always refresh the manifest. It is tiny, and doing this ensures
            // fixed layer entrypoint metadata replaces old bad manifests.
            FileUtils.copy(context, "$ASSET_DIR/$MANIFEST_FILENAME", manifestFile)
            FileUtils.chmod(manifestFile, 0b110100100)

            Timber.tag(TAG).i("Installed Bionic-FG into %s", rootDir)
            true
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to install Bionic-FG")
            false
        }
    }

    /**
     * Apply Bionic-FG environment variables for launch.
     * Called by launcher component.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, container: Container, envVars: EnvVars): Boolean {
        // Clear stale vars. The manifest is an implicit Vulkan layer, so a stale
        // install in ~/.local/share/vulkan/implicit_layer.d can still be loaded
        // by the Vulkan loader even when VK_LAYER_PATH is not amended.
        listOf(ENV_ENABLE, ENV_DISABLE, ENV_MULTIPLIER, ENV_FLOW_SCALE, ENV_MODEL).forEach {
            envVars.remove(it)
        }

        if (!isEnabled(container)) {
            disableLayerInContainer(container)
            envVars.put(ENV_DISABLE, "1")
            Timber.tag(TAG).d("Bionic-FG disabled")
            return false
        }

        ensureInstalled(context, container)

        val layerDir = File(container.rootDir, LAYER_RELATIVE_DIR)
        val existingPath = envVars["VK_LAYER_PATH"] ?: ""
        envVars.put("VK_LAYER_PATH",
            if (existingPath.isNotEmpty()) "$existingPath:${layerDir.absolutePath}"
            else layerDir.absolutePath
        )

        envVars.put(ENV_ENABLE, "1")
        envVars.remove(ENV_DISABLE)
        envVars.put(ENV_MULTIPLIER, multiplier(container).toString())
        envVars.put(ENV_FLOW_SCALE, String.format(java.util.Locale.US, "%.2f", flowScale(container)))
        envVars.put(ENV_MODEL, model(container))

        Timber.tag(TAG).i("Bionic-FG enabled: mult=%d, flow=%.2f, model=%s",
            multiplier(container), flowScale(container), model(container))
        return true
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
