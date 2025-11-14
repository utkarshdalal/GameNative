package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import com.winlator.container.ContainerManager
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
     * Attempts to locate a suitable icon file for an Open Container title.
     * Strategy (in priority order):
     * 1) If we can uniquely identify an exe, prefer an .ico that matches the exe's base name
     *    in the same directory as the exe or in the game folder root.
     * 2) Otherwise, prefer an .ico whose filename contains "icon".
     * 3) Otherwise, if there is exactly one .ico across the folder root or its immediate
     *    subfolders, use that.
     * Returns the absolute file path to the .ico when found; otherwise null.
     */
    fun findIconFileForOpenContainer(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        // 1) If we can uniquely identify an exe, try extracting embedded icon(s)
        val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
        if (!uniqueExeRel.isNullOrEmpty()) {
            val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
            if (exeFile.exists()) {
                val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                // Use cache if up to date, else (re)extract
                val useCached = outIco.exists() && outIco.lastModified() >= exeFile.lastModified()
                if (useCached) return outIco.absolutePath
                try {
                    if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                        return outIco.absolutePath
                    }
                } catch (e: Exception) {
                    // swallow and fall back
                }
            }
        }

        // Fallback to nearby images if extraction was not possible
        return findNearbyImageIcon(folder, uniqueExeRel)
    }

    // New: Context-aware variant that prefers the selected container executable's icon
    fun findIconFileForOpenContainer(context: Context, appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        try {
            val cm = ContainerManager(context)
            if (cm.hasContainer(appId)) {
                val container = cm.getContainerById(appId)
                val relExe = container.executablePath
                if (!relExe.isNullOrEmpty()) {
                    val exeFile = File(folder, relExe.replace('/', File.separatorChar))
                    if (exeFile.exists()) {
                        val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                        val useCached = outIco.exists() && outIco.lastModified() >= exeFile.lastModified()
                        if (useCached) return outIco.absolutePath
                        try {
                            if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                return outIco.absolutePath
                            }
                        } catch (_: Exception) { /* fallthrough */ }
                    }
                }
            }
        } catch (_: Exception) { /* ignore container read issues */ }

        // If selected exe path failed or absent, try unique exe extraction
        val fromUnique = findIconFileForOpenContainer(appId)
        if (!fromUnique.isNullOrEmpty()) return fromUnique

        // As last resort, image heuristic
        return findNearbyImageIcon(folder, null)
    }

    // Shared helper for .ico/.png heuristic
    private fun findNearbyImageIcon(folder: File, uniqueExeRel: String?): String? {
        fun File.icoFiles(): List<File> = this.listFiles { f ->
            f.isFile && (f.name.endsWith(".ico", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.toList() ?: emptyList()

        val rootIcons = folder.icoFiles()
        val subdirIcons = folder.listFiles { f -> f.isDirectory }?.flatMap { it.icoFiles() } ?: emptyList()
        val allIcons = (rootIcons + subdirIcons)
        if (allIcons.isEmpty()) return null

        val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
        if (!exeBase.isNullOrEmpty()) {
            val preferredByName = allIcons.firstOrNull { it.nameWithoutExtension.equals(exeBase, ignoreCase = true) }
            if (preferredByName != null) return preferredByName.absolutePath
        }
        val containsIcon = allIcons.firstOrNull { it.name.contains("icon", ignoreCase = true) }
        if (containsIcon != null) return containsIcon.absolutePath
        val distinct = allIcons.distinctBy { it.absolutePath }
        return if (distinct.size == 1) distinct.first().absolutePath else null
    }

    /**
     * Scan a game folder and return the executable relative path if and only if
     * there is exactly ONE candidate .exe within the folder root or exactly one
     * across all immediate subfolders. Executables whose filenames start with
     * "unins" (case-insensitive) are ignored.
     *
     * Examples of returned values:
     * - "game.exe"
     * - "Binaries/Win64/Game-Win64-Shipping.exe"
     */
    fun findUniqueExeRelativeToFolder(folderPath: String): String? = findUniqueExeRelativeToFolder(File(folderPath))

    fun findUniqueExeRelativeToFolder(folder: File): String? {
        if (!folder.exists() || !folder.isDirectory) return null

        fun File.isValidExe(): Boolean = this.isFile && this.name.endsWith(".exe", ignoreCase = true) &&
                !this.name.startsWith("unins", ignoreCase = true)

        val candidates = mutableListOf<String>()

        // Root-level .exe files
        folder.listFiles()?.forEach { f ->
            if (f.isValidExe()) candidates.add(f.name)
        }

        // If none or more than one at root, also check one level down and collect all
        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles()?.forEach { f ->
                if (f.isValidExe()) {
                    val rel = sd.name + "/" + f.name
                    candidates.add(rel)
                }
            }
        }

        // Keep only unique items
        val unique = candidates.distinct()
        return if (unique.size == 1) unique.first() else null
    }

    /**
     * Find all valid executable files in a game folder.
     * Returns a list of relative paths to all valid .exe files (excluding uninstallers).
     * 
     * @param folderPath The path to the game folder
     * @return List of relative executable paths, or empty list if folder doesn't exist
     */
    fun findAllValidExeFiles(folderPath: String): List<String> = findAllValidExeFiles(File(folderPath))

    fun findAllValidExeFiles(folder: File): List<String> {
        if (!folder.exists() || !folder.isDirectory) return emptyList()

        fun File.isValidExe(): Boolean = this.isFile && this.name.endsWith(".exe", ignoreCase = true) &&
                !this.name.startsWith("unins", ignoreCase = true)

        val candidates = mutableListOf<String>()

        // Root-level .exe files
        folder.listFiles()?.forEach { f ->
            if (f.isValidExe()) candidates.add(f.name)
        }

        // Check one level down
        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles()?.forEach { f ->
                if (f.isValidExe()) {
                    val rel = sd.name + "/" + f.name
                    candidates.add(rel)
                }
            }
        }

        return candidates.distinct()
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

    /**
     * Gets the folder path for an Open Container game from its appId.
     * The appId format is "OPEN_CONTAINER_<hashCode>" where hashCode is derived from the folder's absolute path.
     * Returns null if the folder cannot be found.
     */
    fun getFolderPathFromAppId(appId: String): String? {
        // Extract the hash from appId (format: "OPEN_CONTAINER_<hash>")
        if (!appId.startsWith("${GameSource.OPEN_CONTAINER.name}_")) {
            return null
        }

        val hashStr = appId.removePrefix("${GameSource.OPEN_CONTAINER.name}_")
        val expectedHash = try {
            hashStr.toInt()
        } catch (e: NumberFormatException) {
            return null
        }

        // Scan all roots to find the folder with matching hash
        val roots = getAllRoots()
        for (root in roots) {
            val rootFile = File(root)
            if (!rootFile.exists() || !rootFile.isDirectory) continue

            val children = rootFile.listFiles { f -> f.isDirectory } ?: continue
            for (folder in children) {
                if (!looksLikeGameFolder(folder)) continue

                // Calculate hash the same way as in scanAsLibraryItems
                val folderHash = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
                if (folderHash == expectedHash) {
                    return folder.absolutePath
                }
            }
        }

        return null
    }
}
