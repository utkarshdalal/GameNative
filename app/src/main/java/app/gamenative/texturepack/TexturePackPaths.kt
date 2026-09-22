package app.gamenative.texturepack

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

object TexturePackPaths {

    const val DIR_NAME = "texcache"

    fun cacheDir(container: Container): File = File(container.rootDir, DIR_NAME)

    fun ensureCacheDir(container: Container): File = cacheDir(container).also { it.mkdirs() }

    fun cacheDirForApp(context: Context, appId: String): File? = try {
        if (ContainerUtils.hasContainer(context, appId)) {
            cacheDir(ContainerUtils.getContainer(context, appId))
        } else {
            null
        }
    } catch (e: Exception) {
        Timber.w(e, "could not resolve texture cache directory for $appId")
        null
    }

    fun clear(dir: File?): Boolean {
        if (dir == null || !dir.isDirectory) return false
        var ok = true
        dir.listFiles()?.forEach { if (!it.deleteRecursively()) ok = false }
        return ok
    }

    fun delete(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        return dir.deleteRecursively()
    }
}
