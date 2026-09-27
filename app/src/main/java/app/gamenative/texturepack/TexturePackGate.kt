package app.gamenative.texturepack

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import java.io.File
import kotlinx.serialization.json.Json
import timber.log.Timber

object TexturePackGate {

    const val CONTAINER_EXTRA_SKIP = "texturePackSkip"
    const val CONTAINER_EXTRA_FINGERPRINT = "texturePackFingerprint"
    const val CONTAINER_EXTRA_PLATFORM = "texturePackPlatform"
    const val CONTAINER_EXTRA_STORE_ID = "texturePackStoreId"
    const val CONTAINER_EXTRA_INSTALL_DIR = "texturePackInstallDir"
    const val CONTAINER_EXTRA_SERVER_ENTRIES = "texturePackServerEntries"
    const val CONTAINER_EXTRA_TITLE = "texturePackTitle"
    const val CONTAINER_EXTRA_POLICY = "texturePackPolicy"
    const val NEEDS_FULL_RES_MARKER = "needs_full_res"
    const val COMPATIBLE_DRIVER = "wrapper-gamenative"

    private val POLICY_FORMAT_ORDER = listOf("bc1", "bc2", "bc3", "bc4", "bc5", "bc7", "bc6h")
    private val POLICY_FORMAT = Regex("[a-z0-9]+")
    private val POLICY_BLOCK = Regex("[0-9]{1,2}x[0-9]{1,2}")

    private val policyJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val NATIVE_BCN_RENDERERS = listOf("Adreno", "Xclipse")
    private val EMULATED_BCN_RENDERERS = listOf("Mali", "Immortalis", "PowerVR")

    fun needsTexturePack(context: Context): Boolean {
        val renderer = GPUInformation.getRenderer(context).orEmpty()
        if (NATIVE_BCN_RENDERERS.any { renderer.contains(it, ignoreCase = true) }) return false
        if (EMULATED_BCN_RENDERERS.any { renderer.contains(it, ignoreCase = true) }) return true
        return true
    }

    fun encodePolicy(policy: PackPolicy?): String =
        policy?.let { policyJson.encodeToString(PackPolicy.serializer(), it) } ?: ""

    fun decodePolicy(text: String?): PackPolicy? {
        if (text.isNullOrBlank()) return null
        return try {
            policyJson.decodeFromString(PackPolicy.serializer(), text)
        } catch (e: Exception) {
            Timber.w(e, "could not parse the stored texture pack policy")
            null
        }
    }

    fun policyOf(container: Container): PackPolicy? =
        decodePolicy(container.getExtra(CONTAINER_EXTRA_POLICY, ""))

    fun policyFor(context: Context, appId: String): PackPolicy? = try {
        if (ContainerUtils.hasContainer(context, appId)) policyOf(ContainerUtils.getContainer(context, appId)) else null
    } catch (e: Exception) {
        Timber.w(e, "could not read texture pack policy for $appId")
        null
    }

    fun policyDisabled(policy: PackPolicy?): Boolean = policy != null && !policy.enabled

    fun policyEnv(policy: PackPolicy?): String? {
        if (policy == null || !policy.enabled) return null
        val formats = POLICY_FORMAT_ORDER.filter { it in policy.blocks } +
            policy.blocks.keys.filterNot { it in POLICY_FORMAT_ORDER }.sorted()
        val parts = formats.mapNotNull { format ->
            val block = policy.blocks[format] ?: return@mapNotNull null
            if (POLICY_FORMAT.matches(format) && POLICY_BLOCK.matches(block)) "$format=$block" else null
        }.toMutableList()
        policy.maxDim?.takeIf { it > 0 }?.let { parts += "maxdim=$it" }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun needsFullRes(cacheDir: File): Boolean = File(cacheDir, NEEDS_FULL_RES_MARKER).exists()

    fun platformFor(source: GameSource): String? = when (source) {
        GameSource.STEAM -> "steam"
        GameSource.GOG -> "gog"
        GameSource.EPIC -> "epic"
        GameSource.CUSTOM_GAME -> "custom"
        else -> null
    }

    fun storeIdFor(source: GameSource, appId: String): String = when (source) {
        GameSource.CUSTOM_GAME -> ""
        else -> appId.substringAfter('_', appId)
    }

    fun isSkipped(context: Context, appId: String): Boolean = try {
        ContainerUtils.hasContainer(context, appId) &&
            ContainerUtils.getContainer(context, appId).getExtra(CONTAINER_EXTRA_SKIP, "false").toBoolean()
    } catch (e: Exception) {
        Timber.w(e, "could not read texture pack skip flag for $appId")
        false
    }

    fun resetServerEntries(context: Context, appId: String) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return
            val container = ContainerUtils.getContainer(context, appId)
            if (container.getExtra(CONTAINER_EXTRA_SERVER_ENTRIES, "0") == "0") return
            container.putExtra(CONTAINER_EXTRA_SERVER_ENTRIES, "0")
            container.saveData()
        } catch (e: Exception) {
            Timber.w(e, "could not reset texture pack server entries for $appId")
        }
    }

    fun setSkipped(context: Context, appId: String, skipped: Boolean) {
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return
            val container = ContainerUtils.getContainer(context, appId)
            container.putExtra(CONTAINER_EXTRA_SKIP, skipped.toString())
            container.saveData()
        } catch (e: Exception) {
            Timber.w(e, "could not persist texture pack skip flag for $appId")
        }
    }

    fun syncEnabled(context: Context): Boolean =
        PrefManager.texturePackEnabled && needsTexturePack(context)

    fun compatible(driver: String?, bcnEmulation: String?): Boolean =
        driver.equals(COMPATIBLE_DRIVER, ignoreCase = true) &&
            !bcnEmulation.equals("none", ignoreCase = true)

    fun containerCompatible(container: Container): Boolean {
        val config = KeyValueSet(container.graphicsDriverConfig)
        return compatible(container.graphicsDriver, config.get("bcnEmulation"))
    }

    fun containerCompatible(context: Context, appId: String): Boolean = try {
        !ContainerUtils.hasContainer(context, appId) ||
            containerCompatible(ContainerUtils.getContainer(context, appId))
    } catch (e: Exception) {
        Timber.w(e, "could not read texture pack container compatibility for $appId")
        false
    }

    fun syncEnabled(context: Context, appId: String): Boolean =
        syncEnabled(context) && !isSkipped(context, appId) && containerCompatible(context, appId)

    enum class ExitUploadAction { NONE, PROMPT, ENQUEUE }

    fun exitUploadAction(launchedViaIntent: Boolean, syncEnabled: Boolean, hasSources: Boolean): ExitUploadAction = when {
        !launchedViaIntent -> ExitUploadAction.PROMPT
        syncEnabled && hasSources -> ExitUploadAction.ENQUEUE
        else -> ExitUploadAction.NONE
    }

    fun serverPackPresent(context: Context, appId: String): Boolean = try {
        syncEnabled(context, appId) &&
            ContainerUtils.hasContainer(context, appId) &&
            ContainerUtils.getContainer(context, appId).let { container ->
                packPresent(
                    container.getExtra(CONTAINER_EXTRA_FINGERPRINT, ""),
                    container.getExtra(CONTAINER_EXTRA_SERVER_ENTRIES, "0"),
                )
            }
    } catch (e: Exception) {
        Timber.w(e, "could not read texture pack state for $appId")
        false
    }

    fun packPresent(fingerprint: String, serverEntries: String): Boolean =
        fingerprint.isNotBlank() && (serverEntries.toIntOrNull() ?: 0) > 0

    fun cleanTitle(name: String?): String? =
        name?.trim()?.takeUnless { it.isEmpty() || it == "Unknown" }

    fun rememberLaunchInfo(
        context: Context,
        appId: String,
        source: GameSource,
        installPath: String?,
        title: String? = null,
    ) {
        val platform = platformFor(source) ?: return
        if (installPath.isNullOrBlank() || !File(installPath).isDirectory) return
        try {
            if (!ContainerUtils.hasContainer(context, appId)) return
            val container = ContainerUtils.getContainer(context, appId)
            container.putExtra(CONTAINER_EXTRA_PLATFORM, platform)
            container.putExtra(CONTAINER_EXTRA_STORE_ID, storeIdFor(source, appId))
            container.putExtra(CONTAINER_EXTRA_INSTALL_DIR, installPath)
            cleanTitle(title)?.let { container.putExtra(CONTAINER_EXTRA_TITLE, it) }
            container.saveData()
        } catch (e: Exception) {
            Timber.w(e, "could not persist texture pack launch info for $appId")
        }
    }
}
