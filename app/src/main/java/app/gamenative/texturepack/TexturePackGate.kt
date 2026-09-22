package app.gamenative.texturepack

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.utils.ContainerUtils
import com.winlator.core.GPUInformation
import java.io.File
import timber.log.Timber

object TexturePackGate {

    const val CONTAINER_EXTRA_SKIP = "texturePackSkip"
    const val CONTAINER_EXTRA_PENDING_SIG = "texturePackPendingSig"
    const val CONTAINER_EXTRA_DONE_SIG = "texturePackDoneSig"

    private val NATIVE_BCN_RENDERERS = listOf("Adreno", "Xclipse")
    private val EMULATED_BCN_RENDERERS = listOf("Mali", "Immortalis", "PowerVR")

    fun needsTexturePack(context: Context): Boolean {
        val renderer = GPUInformation.getRenderer(context).orEmpty()
        if (NATIVE_BCN_RENDERERS.any { renderer.contains(it, ignoreCase = true) }) return false
        if (EMULATED_BCN_RENDERERS.any { renderer.contains(it, ignoreCase = true) }) return true
        return true
    }

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
