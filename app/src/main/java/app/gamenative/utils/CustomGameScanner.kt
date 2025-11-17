package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.service.DownloadService
import com.winlator.container.ContainerManager
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch
import timber.log.Timber
import org.json.JSONObject

object CustomGameScanner {

    // Default root path for Custom Games. Prefer the app's external storage sandbox
    // (Android/data/<package>/CustomGames) when available; otherwise fall back to
    // internal data directory. Always auto-create the directory.
    val defaultRootPath: String
        get() {
            // External app sandbox (e.g., /storage/emulated/0/Android/data/<pkg>)
            val externalBase = DownloadService.baseExternalAppDirPath
            val externalDir = if (externalBase.isNotEmpty()) File(externalBase, "CustomGames") else null
            val internalDir = File(DownloadService.baseDataDirPath, "CustomGames")

            // Choose external when the preference is set OR when it already exists (user created it)
            val target = when {
                externalDir != null && (PrefManager.useExternalStorage || externalDir.exists()) -> externalDir
                else -> internalDir
            }
            if (!target.exists()) target.mkdirs()
            return target.path
        }

    /**
     * Attempts to locate a suitable icon file for a Custom Game.
     * Strategy (in priority order):
     * 1) If we can uniquely identify an exe, prefer an .ico that matches the exe's base name
     *    in the same directory as the exe or in the game folder root.
     * 2) Otherwise, prefer an .ico whose filename contains "icon".
     * 3) Otherwise, if there is exactly one .ico across the folder root or its immediate
     *    subfolders, use that.
     * Returns the absolute file path to the .ico when found; otherwise null.
     */
    fun findIconFileForCustomGame(appId: String): String? {
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
    fun findIconFileForCustomGame(context: Context, appId: String): String? {
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
                        if (useCached) {
                            Timber.tag("CustomGameScanner").d("Found cached icon at ${outIco.absolutePath}")
                            return outIco.absolutePath
                        }
                        try {
                            if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                Timber.tag("CustomGameScanner").d("Extracted icon to ${outIco.absolutePath}")
                                return outIco.absolutePath
                            }
                        } catch (e: Exception) {
                            Timber.tag("CustomGameScanner").d(e, "Failed to extract icon from ${exeFile.name}")
                        }
                    } else {
                        Timber.tag("CustomGameScanner").d("Executable file does not exist: ${exeFile.absolutePath}")
                    }
                } else {
                    Timber.tag("CustomGameScanner").d("Container executable path is empty")
                }
            } else {
                Timber.tag("CustomGameScanner").d("No container found for $appId")
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").d(e, "Error checking container for $appId")
        }

        // If selected exe path failed or absent, try unique exe extraction
        val fromUnique = findIconFileForCustomGame(appId)
        if (!fromUnique.isNullOrEmpty()) {
            Timber.tag("CustomGameScanner").d("Found icon from unique executable: $fromUnique")
            return fromUnique
        }

        // As last resort, image heuristic
        val fromHeuristic = findNearbyImageIcon(folder, null)
        if (fromHeuristic != null) {
            Timber.tag("CustomGameScanner").d("Found icon from heuristic: $fromHeuristic")
        } else {
            Timber.tag("CustomGameScanner").d("No icon found for $appId")
        }
        return fromHeuristic
    }

    // Shared helper for .ico/.png heuristic
    private fun findNearbyImageIcon(folder: File, uniqueExeRel: String?): String? {
        fun File.icoFiles(): List<File> = this.listFiles { f ->
            f.isFile && (f.name.endsWith(".ico", ignoreCase = true) || f.name.endsWith(".png", ignoreCase = true))
        }?.toList() ?: emptyList()

        val rootIcons = folder.icoFiles()
        val subdirIcons = folder.listFiles { f -> f.isDirectory }?.flatMap { it.icoFiles() } ?: emptyList()
        val allIcons = (rootIcons + subdirIcons)
        if (allIcons.isEmpty()) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - No icon files found in $folder")
            return null
        }

        Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Found ${allIcons.size} icon file(s): ${allIcons.map { it.name }}")

        // First priority: prefer .extracted.ico files (these are extracted from executables)
        val extractedIcons = allIcons.filter { it.name.endsWith(".extracted.ico", ignoreCase = true) }
        if (extractedIcons.isNotEmpty()) {
            // If there's exactly one extracted icon, use it
            if (extractedIcons.size == 1) {
                Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using single extracted icon: ${extractedIcons.first().absolutePath}")
                return extractedIcons.first().absolutePath
            }
            // If multiple extracted icons, prefer one matching exe name if available
            val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
            if (!exeBase.isNullOrEmpty()) {
                val matchingExtracted = extractedIcons.firstOrNull {
                    it.nameWithoutExtension.replace(".extracted", "").equals(exeBase, ignoreCase = true)
                }
                if (matchingExtracted != null) {
                    Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using extracted icon matching exe: ${matchingExtracted.absolutePath}")
                    return matchingExtracted.absolutePath
                }
            }
            // Otherwise, use the first extracted icon
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using first extracted icon: ${extractedIcons.first().absolutePath}")
            return extractedIcons.first().absolutePath
        }

        val exeBase = uniqueExeRel?.substringAfterLast('/')?.substringBeforeLast('.')
        if (!exeBase.isNullOrEmpty()) {
            val preferredByName = allIcons.firstOrNull { it.nameWithoutExtension.equals(exeBase, ignoreCase = true) }
            if (preferredByName != null) {
                Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using icon matching exe name: ${preferredByName.absolutePath}")
                return preferredByName.absolutePath
            }
        }
        val containsIcon = allIcons.firstOrNull { it.name.contains("icon", ignoreCase = true) }
        if (containsIcon != null) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using icon with 'icon' in name: ${containsIcon.absolutePath}")
            return containsIcon.absolutePath
        }
        val distinct = allIcons.distinctBy { it.absolutePath }
        if (distinct.size == 1) {
            Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Using single icon: ${distinct.first().absolutePath}")
            return distinct.first().absolutePath
        }
        Timber.tag("CustomGameScanner").d("findNearbyImageIcon - Multiple icons found (${distinct.size}), cannot choose")
        return null
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
        result.addAll(PrefManager.customGamePaths)
        return result
    }

    /**
     * Count folders per root path (immediate subdirectories).
     * Note: This is used by Settings to quickly indicate how many entries are present
     * under each Custom Game path. It intentionally does NOT validate that the
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

                // Get or generate game ID (checks .gamenative file first, then generates and stores)
                val idPart = getOrGenerateGameId(folder)
                val appId = "${GameSource.CUSTOM_GAME.name}_$idPart"
                
                // Update cache with this new entry
                CustomGameCache.addEntry(idPart, folder.absolutePath)

                items.add(
                    LibraryItem(
                        index = indexCounter++,
                        appId = appId,
                        name = folder.name,
                        iconHash = "",
                        isShared = false,
                        gameSource = GameSource.CUSTOM_GAME,
                    )
                )

                // Fetch SteamGridDB images on first detection (if enabled)
                // This runs asynchronously and won't block the scan
                if (PrefManager.fetchSteamGridDBImages) {
                    // Capture idPart for use in coroutine
                    val capturedIdPart = idPart
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            // Check if we've already fetched images for this game
                            if (!app.gamenative.utils.GameMetadataManager.isSteamGridDBFetched(folder)) {
                                app.gamenative.utils.SteamGridDB.fetchGameImages(folder.name, folder.absolutePath)
                                // Mark as fetched in metadata (pass appId to ensure file exists)
                                app.gamenative.utils.GameMetadataManager.update(folder, appId = capturedIdPart, steamgriddbFetched = true)
                            }
                        } catch (e: Exception) {
                            // Silently fail - this is a background operation
                            Timber.tag("CustomGameScanner").d(e, "SteamGridDB: Background fetch failed for ${folder.name}")
                        }
                    }
                }

                // Proactively extract icon from executable on first detection
                // This runs asynchronously and won't block the scan
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        // Check if icon already exists
                        val hasExtractedIcon = folder.listFiles()?.any { file ->
                            file.name.endsWith(".extracted.ico", ignoreCase = true)
                        } == true

                        if (!hasExtractedIcon) {
                            // Try to find unique executable and extract icon
                            val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
                            if (!uniqueExeRel.isNullOrEmpty()) {
                                val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
                                if (exeFile.exists()) {
                                    val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                                    // Only extract if file doesn't exist or is outdated
                                    if (!outIco.exists() || outIco.lastModified() < exeFile.lastModified()) {
                                        if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                            Timber.tag("CustomGameScanner").d("Extracted icon for ${folder.name} from ${exeFile.name}")
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silently fail - this is a background operation
                        Timber.tag("CustomGameScanner").d(e, "Icon extraction failed for ${folder.name}")
                    }
                }
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
     * Reads the game ID from the .gamenative file in the given folder.
     * Returns null if the file doesn't exist or doesn't contain a valid ID.
     */
    private fun readGameIdFromFile(folder: File): Int? {
        return app.gamenative.utils.GameMetadataManager.getAppId(folder)
    }

    /**
     * Writes the game ID to the .gamenative file in the given folder.
     * Preserves other metadata fields (steamgriddbFetched, releaseDate) if they exist.
     */
    private fun writeGameIdToFile(folder: File, gameId: Int) {
        // Read existing metadata to preserve other fields
        val existing = app.gamenative.utils.GameMetadataManager.read(folder)
        val metadata = if (existing != null) {
            // Preserve existing metadata fields, only update appId
            existing.copy(appId = gameId)
        } else {
            // Create new metadata with just the appId
            app.gamenative.utils.GameMetadata(appId = gameId)
        }
        app.gamenative.utils.GameMetadataManager.write(folder, metadata)
    }

    /**
     * Invalidates the appId cache, forcing a rebuild on next access.
     * Call this when Custom Game paths change, after deletion, or after manual refresh.
     */
    fun invalidateCache() {
        CustomGameCache.invalidate()
    }

    /**
     * Gets or rebuilds the appId cache if needed.
     * Cache is invalidated when Custom Game root paths change.
     */
    private fun getOrRebuildCache(): Map<Int, String> {
        return CustomGameCache.getOrRebuildCache(
            getAllRoots = { getAllRoots() },
            looksLikeGameFolder = { folder -> looksLikeGameFolder(folder) },
            readGameIdFromFile = { folder -> readGameIdFromFile(folder) }
        )
    }

    /**
     * Gets all existing Custom Game IDs by using the cache.
     * Returns a set of IDs that are already in use.
     */
    private fun getAllExistingGameIds(excludeFolder: File? = null): Set<Int> {
        val cache = getOrRebuildCache()
        
        // If excluding a folder, remove its ID from the set
        if (excludeFolder != null) {
            val excludeId = readGameIdFromFile(excludeFolder) 
                ?: abs(excludeFolder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
            return cache.keys.filter { it != excludeId }.toSet()
        }
        
        return cache.keys.toSet()
    }

    /**
     * Gets or generates the game ID for a folder.
     * First checks for .gamenative file, then generates from folder name if not found.
     * Ensures the generated ID is unique across all Custom Games.
     * If generated, stores it in the file for future use.
     */
    private fun getOrGenerateGameId(folder: File): Int {
        // First, try to read from .gamenative file
        val storedId = readGameIdFromFile(folder)
        if (storedId != null) {
            return storedId
        }

        // If not found, generate from folder name (same logic as before)
        var candidateId = abs(folder.absolutePath.hashCode()).let { if (it == 0) 1 else it }
        
        // Check for collisions and make it unique if needed
        val existingIds = getAllExistingGameIds(excludeFolder = folder)
        if (candidateId in existingIds) {
            // ID collision detected, find a unique ID by incrementing
            Timber.tag("CustomGameScanner").d("ID collision detected for ${folder.absolutePath}: $candidateId, finding unique ID")
            var counter = 1
            while (candidateId + counter in existingIds) {
                counter++
            }
            candidateId = candidateId + counter
            Timber.tag("CustomGameScanner").d("Generated unique ID: $candidateId (base was ${candidateId - counter})")
        }
        
        // Store it in the file for future use
        writeGameIdToFile(folder, candidateId)
        
        return candidateId
    }

    /**
     * Gets the folder path for a Custom Game from its appId using the cache.
     * The appId format is "CUSTOM_GAME_<id>" where id is stored in .gamenative file or derived from folder name.
     * Returns null if the folder cannot be found.
     */
    fun getFolderPathFromAppId(appId: String): String? {
        // Extract the ID from appId (format: "CUSTOM_GAME_<id>")
        if (!appId.startsWith("${GameSource.CUSTOM_GAME.name}_")) {
            Timber.tag("CustomGameScanner").d("appId doesn't start with CUSTOM_GAME_: $appId")
            return null
        }

        val idStr = appId.removePrefix("${GameSource.CUSTOM_GAME.name}_")
        val expectedId = try {
            idStr.toInt()
        } catch (e: NumberFormatException) {
            Timber.tag("CustomGameScanner").d("Failed to parse ID from appId: $appId")
            return null
        }

        // Use cache for fast lookup
        val cache = getOrRebuildCache()
        val folderPath = cache[expectedId]
        
        if (folderPath != null) {
            // Verify the folder still exists
            val folder = File(folderPath)
            if (folder.exists() && folder.isDirectory) {
                return folderPath
            } else {
                // Folder was deleted, remove from cache and try again
                Timber.tag("CustomGameScanner").w("Cached folder no longer exists: $folderPath, invalidating cache")
                invalidateCache()
                // Try one more time with fresh cache
                return getOrRebuildCache()[expectedId]
            }
        }

        Timber.tag("CustomGameScanner").w("Could not find folder for appId: $appId (expected ID: $expectedId)")
        return null
    }
}
