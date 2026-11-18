package app.gamenative.mods

import android.content.Context
import com.winlator.xenvironment.ImageFs
import java.io.File

object ModContainerResolver {
    fun getWinePrefix(context: Context, appId: String): String {
        val imageFs = ImageFs.find(context)
        val homeDir = File(imageFs.rootDir, "home")
        val homeCanonical = homeDir.canonicalFile
        val containerDir = File(homeCanonical, "${ImageFs.USER}-$appId").canonicalFile
        require(containerDir.isInsideOrEqual(homeCanonical)) { "Invalid app id for Wine prefix" }
        return File(containerDir, ".wine").absolutePath
    }

    fun getWineUserHome(winePrefix: String): File {
        val usersDir = File(winePrefix, "drive_c/users")
        val steamUser = File(usersDir, "steamuser")
        if (steamUser.isDirectory) return steamUser
        usersDir.listFiles()
            ?.firstOrNull { it.isDirectory && !it.name.equals("Public", ignoreCase = true) }
            ?.let { return it }
        if (steamUser.mkdirs() || steamUser.isDirectory) return steamUser
        throw IllegalStateException("Unable to create Wine user home: ${steamUser.absolutePath}")
    }

    private fun File.isInsideOrEqual(root: File): Boolean =
        this == root || path.startsWith(root.path.trimEnd(File.separatorChar) + File.separator)
}
