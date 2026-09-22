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
        return !isSkipped(context, appId)
    }
}
