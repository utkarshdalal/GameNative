package app.gamenative.texturepack

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.core.GPUInformation
import java.io.File
import kotlinx.serialization.json.Json
import timber.log.Timber

object TexturePackGate {

    const val CONTAINER_EXTRA_SKIP = "texturePackSkip"
    const val CONTAINER_EXTRA_PENDING_SIG = "texturePackPendingSig"
    const val CONTAINER_EXTRA_DONE_SIG = "texturePackDoneSig"
    const val CONTAINER_EXTRA_FINGERPRINT = "texturePackFingerprint"
    const val CONTAINER_EXTRA_PLATFORM = "texturePackPlatform"
    const val CONTAINER_EXTRA_STORE_ID = "texturePackStoreId"
    const val CONTAINER_EXTRA_INSTALL_DIR = "texturePackInstallDir"
    const val CONTAINER_EXTRA_SERVER_ENTRIES = "texturePackServerEntries"
    const val CONTAINER_EXTRA_TITLE = "texturePackTitle"
    const val CONTAINER_EXTRA_POLICY = "texturePackPolicy"
    const val NEEDS_FULL_RES_MARKER = "needs_full_res"

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

    fun gpuTranscodeEnv(sync: Boolean, prefOn: Boolean, containerTranscoderIsGpu: Boolean): String {
        val gpu = if (sync) prefOn else containerTranscoderIsGpu
        return if (gpu) "1" else "0"
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

    fun shouldOffer(context: Context, appId: String, source: GameSource, installPath: String?): Boolean {
        if (!PrefManager.texturePackEnabled) return false
        if (platformFor(source) == null) return false
        if (installPath.isNullOrBlank() || !File(installPath).isDirectory) return false
        if (!needsTexturePack(context)) return false
        if (isSkipped(context, appId)) return false
        return doneSignature(context, appId) != installSignature(installPath)
    }

    fun syncEnabled(context: Context): Boolean =
        PrefManager.texturePackEnabled && needsTexturePack(context)

    fun syncEnabled(context: Context, appId: String): Boolean =
        syncEnabled(context) && !isSkipped(context, appId)

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

    data class LaunchInfo(
        val platform: String,
        val storeId: String,
        val installDir: String,
        val title: String? = null,
    )

    fun launchInfo(context: Context, appId: String): LaunchInfo? = try {
        if (!ContainerUtils.hasContainer(context, appId)) {
            null
        } else {
            val container = ContainerUtils.getContainer(context, appId)
            val platform = container.getExtra(CONTAINER_EXTRA_PLATFORM, "")
            val installDir = container.getExtra(CONTAINER_EXTRA_INSTALL_DIR, "")
            if (platform.isBlank() || installDir.isBlank() || !File(installDir).isDirectory) {
                null
            } else {
                LaunchInfo(
                    platform,
                    container.getExtra(CONTAINER_EXTRA_STORE_ID, ""),
                    installDir,
                    cleanTitle(container.getExtra(CONTAINER_EXTRA_TITLE, "")),
                )
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "could not read texture pack launch info for $appId")
        null
    }

    fun installSignature(installPath: String): String {
        val root = File(installPath)
        val prefix = root.absolutePath.length + 1
        val lines = root.walkTopDown()
            .filter { it.isFile }
            .map { "${it.absolutePath.substring(prefix)}\u0000${it.length()}\u0000${it.lastModified()}" }
            .sorted()
            .toList()
        val hash = net.jpountz.xxhash.XXHashFactory.fastestJavaInstance().newStreamingHash64(0)
        for (line in lines) {
            val bytes = (line + "\n").toByteArray()
            hash.update(bytes, 0, bytes.size)
        }
        return "%016x".format(hash.value)
    }

    fun setPendingSignature(context: Context, appId: String, signature: String) {
        try {
            val container = ContainerUtils.getContainer(context, appId)
            container.putExtra(CONTAINER_EXTRA_PENDING_SIG, signature)
            container.saveData()
        } catch (_: Exception) {
        }
    }

    fun markDone(context: Context, appId: String) {
        try {
            val container = ContainerUtils.getContainer(context, appId)
            val pending = container.getExtra(CONTAINER_EXTRA_PENDING_SIG, "")
            if (pending.isBlank()) return
            container.putExtra(CONTAINER_EXTRA_DONE_SIG, pending)
            container.saveData()
        } catch (_: Exception) {
        }
    }

    private fun doneSignature(context: Context, appId: String): String = try {
        ContainerUtils.getContainer(context, appId).getExtra(CONTAINER_EXTRA_DONE_SIG, "")
    } catch (_: Exception) {
        ""
    }
}
