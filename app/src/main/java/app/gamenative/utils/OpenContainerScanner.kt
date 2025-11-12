package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import java.io.File
import kotlin.math.abs

object OpenContainerScanner {

    // Default root path for Open Containers. Prefer the app's external storage sandbox
    // (Android/data/<package>/OpenContainers) when available; otherwise fall back to
    // internal data directory. Always auto-create the directory.
    val defaultRootPath: String
        get() {
            // External app sandbox (e.g., /storage/emulated/0/Android/data/<pkg>)
            val externalBase = DownloadService.baseExternalAppDirPath
            val externalDir = if (externalBase.isNotEmpty()) File(externalBase, "OpenContainers") else null
            val internalDir = File(DownloadService.baseDataDirPath, "OpenContainers")

            // Choose external when the preference is set OR when it already exists (user created it)
            val target = when {
                externalDir != null && (PrefManager.useExternalStorage || externalDir.exists()) -> externalDir
                else -> internalDir
            }
            if (!target.exists()) target.mkdirs()
            return target.path
        }

    /**
     * Returns a combined set of root paths: the default path (always included)
     * plus any user-defined additional paths from preferences.
     */
    fun getAllRoots(): Set<String> {
        val result = mutableSetOf<String>()
        result.add(defaultRootPath)
        result.addAll(PrefManager.openContainerPaths)
        return result
    }

    /**
     * Count folders per root path (immediate subdirectories).
     * Note: This is used by Settings to quickly indicate how many entries are present
     * under each Open Container path. It intentionally does NOT validate that the
     * folders contain executables. Library visibility still requires an .exe via
     * scanAsLibraryItems().
     */
    fun countGamesByRoot(query: String = ""): Map<String, Int> {
        val q = query.trim()
        val result = mutableMapOf<String, Int>()
        for (root in getAllRoots()) {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) {
                result[root] = 0
                continue
            }
            val children = rootFile.listFiles { f -> f.isDirectory } ?: emptyArray()
            val count = children.count { folder ->
                (q.isEmpty() || folder.name.contains(q, ignoreCase = true))
            }
            result[root] = count
        }
        return result
    }

    /**
     * Scan all roots for subfolders that look like custom games.
     * A folder qualifies if it contains at least one .exe file (case-insensitive)
     * at depth <= 2 (folder itself or one level below).
     * Optionally filter by [query] contained in folder name (case-insensitive).
     */
    fun scanAsLibraryItems(query: String = "", indexOffsetStart: Int = 0, includeWhenInstalledFilterActive: Boolean = true): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        var indexCounter = indexOffsetStart
        val q = query.trim()
        val roots = getAllRoots()
        for (root in roots) {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) continue
            val children = rootFile.listFiles { f -> f.isDirectory } ?: continue
            for (folder in children) {
                if (q.isNotEmpty() && !folder.name.contains(q, ignoreCase = true)) continue
                if (!looksLikeGameFolder(folder)) continue

                // Positive, stable int ID derived from absolute path
                val idPart = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
                val appId = "${GameSource.OPEN_CONTAINER.name}_$idPart"

                items.add(
                    LibraryItem(
                        index = indexCounter++,
                        appId = appId,
                        name = folder.name,
                        iconHash = "", // Placeholder; icons handled elsewhere (another branch)
                        isShared = false,
                        gameSource = GameSource.OPEN_CONTAINER,
                    )
                )
            }
        }
        return items
    }

    private fun looksLikeGameFolder(dir: File): Boolean {
        // Check for .exe in dir or one level below
        val inRoot = dir.listFiles()?.any { it.isFile && it.name.endsWith(".exe", ignoreCase = true) } == true
        if (inRoot) return true
        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return false
        for (sd in subDirs) {
            val hasExe = sd.listFiles()?.any { it.isFile && it.name.endsWith(".exe", ignoreCase = true) } == true
            if (hasExe) return true
        }
        return false
    }
}
