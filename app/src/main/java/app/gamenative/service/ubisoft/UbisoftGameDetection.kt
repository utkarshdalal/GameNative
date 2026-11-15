package app.gamenative.service.ubisoft

import java.io.File

/** A game needs the stand-in when it ships one of the launcher's loader DLLs. Files only, never app ids. */
object UbisoftGameDetection {
    private val loaders = setOf(
        "upc_r1_loader.dll", "upc_r1_loader64.dll",
        "uplay_r1_loader.dll", "uplay_r1_loader64.dll",
        "upc_r2_loader.dll", "upc_r2_loader64.dll",
        "ubiorbitapi_r2_loader.dll",
    )

    fun needsStandIn(installDir: File): Boolean = candidates(installDir).any(::holdsLoader)

    /** The folder that holds the loader, or null when none does. */
    fun loaderDir(installDir: File): File? = candidates(installDir).firstOrNull(::holdsLoader)

    private fun candidates(installDir: File): List<File> {
        if (!installDir.isDirectory) return emptyList()
        val subdirs = installDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != UbisoftHelperDeployment.DIRECTORY }
            .sortedWith(compareBy<File> { !it.name.equals("bin", ignoreCase = true) }.thenBy { it.name.lowercase() })
        return listOf(installDir) + subdirs
    }

    private fun holdsLoader(dir: File): Boolean =
        dir.listFiles().orEmpty().any { it.isFile && it.name.lowercase() in loaders }
}
