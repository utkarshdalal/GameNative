package app.gamenative.texturepack

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import java.io.File
import timber.log.Timber

data class TextureCacheUsage(val appId: String, val name: String, val bytes: Long, val dir: File)

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

    fun sizeOf(dir: File?): Long {
        if (dir == null || !dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun cacheUsage(context: Context): List<TextureCacheUsage> {
        val containers = try {
            ContainerManager(context).containers.toList()
        } catch (e: Exception) {
            Timber.w(e, "could not list containers for the texture cache")
            emptyList()
        }
        return containers.mapNotNull { container ->
            val dir = cacheDir(container)
            val bytes = sizeOf(dir)
            if (bytes <= 0L) return@mapNotNull null
            val name = try {
                ContainerUtils.resolveGameName(container.id).takeUnless { it.isBlank() || it == "Unknown" }
            } catch (e: Exception) {
                null
            } ?: container.id
            TextureCacheUsage(container.id, name, bytes, dir)
        }.sortedByDescending { it.bytes }
    }

    fun delete(dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        return dir.deleteRecursively()
    }
}
