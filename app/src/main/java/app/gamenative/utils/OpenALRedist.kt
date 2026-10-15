package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import timber.log.Timber

/**
 * Games that ship the OpenAL redistributable get the bundled OpenAL Soft DLLs instead of
 * running oalinst.exe under Wine.
 */
object OpenALRedist {
    const val DLL_OVERRIDE = "openal32=native,builtin"

    private val SEARCH_DIRS = listOf("_CommonRedist/OpenAL", "redist", "Redist")

    internal fun isInstaller(file: File): Boolean =
        file.isFile &&
            file.name.endsWith(".exe", ignoreCase = true) &&
            (file.name.equals("oalinst.exe", ignoreCase = true) || file.name.startsWith("OpenAL", ignoreCase = true))

    fun hasInstaller(gameDir: File): Boolean =
        SEARCH_DIRS
            .map { File(gameDir, it) }
            .filter { it.isDirectory }
            .any { dir -> dir.walkTopDown().any(::isInstaller) }

    fun mergeDllOverride(dllOverrides: String): String {
        if (dllOverrides.contains("openal32") || dllOverrides.contains("soft_oal")) return dllOverrides
        return if (dllOverrides.isBlank()) DLL_OVERRIDE else "$dllOverrides;$DLL_OVERRIDE"
    }

    fun applyForLaunch(container: Container) {
        try {
            val gameDir = PreInstallSteps.getGameDir(container) ?: return
            if (!hasInstaller(gameDir)) return
            val envVars = EnvVars(container.envVars)
            val current = envVars.get("WINEDLLOVERRIDES")
            val merged = mergeDllOverride(current)
            if (merged == current) return
            Timber.i("OpenAL redistributable found in ${gameDir.absolutePath}, enabling $DLL_OVERRIDE")
            envVars.put("WINEDLLOVERRIDES", merged)
            container.envVars = envVars.toString()
            container.saveData()
        } catch (e: Exception) {
            Timber.e(e, "Failed to enable OpenAL for ${container.id}")
        }
    }
}
