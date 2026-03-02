package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

object CustomGameScanner {

    // Default root path for Custom Games. Always use the app's external storage sandbox
    // (Android/data/<package>/CustomGames) when available; fall back to internal only if external is unavailable.
    // This ensures the folder is visible via MTP/file managers.
    val defaultRootPath: String
        get() {
            // External app sandbox (e.g., /storage/emulated/0/Android/data/<pkg>)
            val externalBase = DownloadService.baseExternalAppDirPath
            val externalDir = if (externalBase.isNotEmpty()) File(externalBase, "CustomGames") else null
            val internalDir = File(DownloadService.baseDataDirPath, "CustomGames")

            // Always prefer external location (visible via MTP/file managers) when available
            // Only fall back to internal if external is truly not available
            val target = when {
                externalDir != null -> {
                    // Always use external if available (it's visible to users via file managers)
                    // Create parent directory if needed
                    externalDir.parentFile?.mkdirs()
                    externalDir
                }

                else -> {
                    Timber.tag("CustomGameScanner").w("External storage not available, falling back to internal: ${internalDir.path}")
                    internalDir
                }
            }
            if (!target.exists()) {
                val created = target.mkdirs()
                if (created) {
                    Timber.tag("CustomGameScanner").d("Created default CustomGames folder: ${target.path}")
                } else {
                    Timber.tag("CustomGameScanner").w("Failed to create default CustomGames folder: ${target.path}")
                }
            }
            Timber.tag("CustomGameScanner").d("Using default CustomGames path: ${target.path}")
            return target.path
        }

    /**
     * Ensures the default CustomGames folder exists by creating it if it doesn't.
     * This should be called when the library screen loads to guarantee the folder exists
     * even if there are no custom games yet.
     *
     * This function explicitly creates the folder using the same logic as defaultRootPath
     * to ensure it exists regardless of whether scanning happens.
     */
    fun ensureDefaultFolderExists() {
        Timber.tag("CustomGameScanner").d("Ensuring default CustomGames folder exists")

        try {
            // Use the same logic as defaultRootPath to ensure consistency
            val defaultPath = defaultRootPath
            val folder = File(defaultPath)

            if (!folder.exists()) {
                val created = folder.mkdirs()
                if (created) {
                    Timber.tag("CustomGameScanner").d("Created default CustomGames folder: $defaultPath")
                } else {
                    Timber.tag("CustomGameScanner").w("Failed to create default CustomGames folder: $defaultPath")
                }
            } else {
                Timber.tag("CustomGameScanner").d("Default CustomGames folder already exists: $defaultPath")
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").e(e, "Error ensuring default CustomGames folder exists")
        }
    }

    /**
     * Attempts to locate a suitable icon file for a Custom Game.
     * Strategy (in priority order):
     * 1) Check for SteamGridDB logo files (steamgriddb_logo.png/jpg/webp)
     * 2) If we can uniquely identify an exe, try extracting embedded icon(s)
     * 3) Otherwise, prefer an .ico whose filename contains "icon".
     * 4) Otherwise, if there is exactly one .ico across the folder root or its immediate
     *    subfolders, use that.
     * Returns the absolute file path to the icon when found; otherwise null.
     */
    fun findIconFileForCustomGame(appId: String): String? {
        val folderPath = getFolderPathFromAppId(appId) ?: return null
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) return null

        val steamGridLogo = folder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (
                    file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true)
                    )
        }?.firstOrNull()
        if (steamGridLogo != null) {
            Timber.tag("CustomGameScanner").d("Found SteamGridDB logo: ${steamGridLogo.absolutePath}")
            return steamGridLogo.absolutePath
        }

        // 2) If we can uniquely identify an exe, try extracting embedded icon(s)
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

        val steamGridLogo = folder.listFiles { file ->
            file.isFile && file.name.startsWith("steamgriddb_logo", ignoreCase = true) &&
                (
                    file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true)
                    )
        }?.firstOrNull()
        if (steamGridLogo != null) {
            Timber.tag("CustomGameScanner").d("Found SteamGridDB logo: ${steamGridLogo.absolutePath}")
            return steamGridLogo.absolutePath
        }

        // 2) Try extracting from the selected container executable
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

        // 3) If selected exe path failed or absent, try unique exe extraction
        val fromUnique = findIconFileForCustomGame(appId)
        if (!fromUnique.isNullOrEmpty()) {
            Timber.tag("CustomGameScanner").d("Found icon from unique executable: $fromUnique")
            return fromUnique
        }

        // 4) As last resort, image heuristic
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

    /**
     * Resolves the effective launch executable for a Custom Game (container config or auto-detected from A: drive).
     * Returns empty string if no executable can be found.
     * When container has a configured path, verifies the file exists to avoid launching stale/missing paths.
     */
    fun getLaunchExecutable(container: Container): String {
        val gameFolderPath = ContainerUtils.getADrivePath(container.drives) ?: return ""
        val exe = container.executablePath
        if (exe.isNotEmpty()) {
            val fullPath = File(gameFolderPath, exe.replace('\\', File.separatorChar))
            if (fullPath.exists() && fullPath.isFile) return exe
            // Stale or missing path — fall through to auto-detect
        }
        return findUniqueExeRelativeToFolder(gameFolderPath) ?: ""
    }

    fun findUniqueExeRelativeToFolder(folder: File): String? {
        if (!folder.exists() || !folder.isDirectory) return null

        fun File.isValidExe(): Boolean = this.isFile && this.name.endsWith(".exe", ignoreCase = true) &&
            !this.name.startsWith("unins", ignoreCase = true)

        val candidates = mutableListOf<String>()

        folder.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                !f.name.startsWith("unins", ignoreCase = true)
        }?.forEach { f ->
            candidates.add(f.name)
        }

        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                    !f.name.startsWith("unins", ignoreCase = true)
            }?.forEach { f ->
                val rel = sd.name + "/" + f.name
                candidates.add(rel)
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

        folder.listFiles { f ->
            f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                !f.name.startsWith("unins", ignoreCase = true)
        }?.forEach { f ->
            candidates.add(f.name)
        }

        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { f ->
                f.isFile && f.name.endsWith(".exe", ignoreCase = true) &&
                    !f.name.startsWith("unins", ignoreCase = true)
            }?.forEach { f ->
                val rel = sd.name + "/" + f.name
                candidates.add(rel)
            }
        }

        return candidates.distinct()
    }

    /**
     * Checks if we have permission to access a given path.
     * On Android 11+ (API 30+), this checks for MANAGE_EXTERNAL_STORAGE permission.
     * On older versions, checks for READ_EXTERNAL_STORAGE.
     */
    fun hasStoragePermission(context: Context, path: String): Boolean {
        // Check if path is outside app sandbox
        val isOutsideSandbox = !path.contains("/Android/data/${context.packageName}") &&
            !path.contains(context.dataDir.path)

        if (!isOutsideSandbox) {
            // Path is in app sandbox, no special permission needed
            return true
        }

        // For paths outside sandbox, check permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires MANAGE_EXTERNAL_STORAGE for broad access
            return Environment.isExternalStorageManager()
        } else {
            // Android 10 and below use standard storage permissions
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Opens the Android settings page to grant MANAGE_EXTERNAL_STORAGE permission.
     * This is required for Android 11+ to access paths outside the app sandbox.
     * Returns true if the intent was launched, false otherwise.
     */
    fun requestManageExternalStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Timber.tag("CustomGameScanner").e(e, "Failed to open settings for MANAGE_EXTERNAL_STORAGE")
                // Fallback: try generic app settings
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                    return true
                } catch (e2: Exception) {
                    Timber.tag("CustomGameScanner").e(e2, "Failed to open app settings")
                    return false
                }
            }
        }
        return false
    }

    /**
     * All manually added folders are included regardless of content.
     * Optionally filter by [query] contained in folder name (case-insensitive).
     */
    fun scanAsLibraryItems(
        query: String = "",
        indexOffsetStart: Int = 0,
        includeWhenInstalledFilterActive: Boolean = true,
    ): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        var indexCounter = indexOffsetStart
        val q = query.trim()

        val manualFolders = PrefManager.customGameManualFolders
        if (manualFolders.isNotEmpty()) {
            val existingAppIds = mutableSetOf<String>()
            for (manualPath in manualFolders) {
                // Filter by query if provided
                if (q.isNotEmpty()) {
                    val folderName = File(manualPath).name
                    if (!folderName.contains(q, ignoreCase = true)) continue
                }

                val manualItem = createLibraryItemFromFolder(manualPath)
                if (manualItem != null && existingAppIds.add(manualItem.appId)) {
                    items.add(manualItem.copy(index = indexCounter++))
                }
            }
        }

        return items
    }

    private fun handleCustomGameDetection(folder: File, appId: String, idPart: Int) {
        CustomGameCache.addEntry(idPart, folder.absolutePath)

        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val hasExtractedIcon = folder.listFiles { file ->
                    file.isFile && file.name.endsWith(".extracted.ico", ignoreCase = true)
                }?.isNotEmpty() == true

                if (!hasExtractedIcon) {
                    val uniqueExeRel = findUniqueExeRelativeToFolder(folder)
                    if (!uniqueExeRel.isNullOrEmpty()) {
                        val exeFile = File(folder, uniqueExeRel.replace('/', File.separatorChar))
                        if (exeFile.exists()) {
                            val outIco = File(exeFile.parentFile, exeFile.nameWithoutExtension + ".extracted.ico")
                            if (!outIco.exists() || outIco.lastModified() < exeFile.lastModified()) {
                                if (ExeIconExtractor.tryExtractMainIcon(exeFile, outIco)) {
                                    Timber.tag("CustomGameScanner").d("Extracted icon for ${folder.name} from ${exeFile.name}")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CustomGameScanner").d(e, "Icon extraction failed for ${folder.name}")
            }
        }
    }

    fun createLibraryItemFromFolder(folderPath: String): LibraryItem? {
        val folder = File(folderPath)
        if (!folder.exists() || !folder.isDirectory) {
            Timber.tag("CustomGameScanner").w("Folder does not exist or is not a directory: $folderPath")
            return null
        }

        val idPart = getOrGenerateGameId(folder)
        val appId = "${GameSource.CUSTOM_GAME.name}_$idPart"

        handleCustomGameDetection(folder, appId, idPart)

        return LibraryItem(
            index = 0,
            appId = appId,
            name = folder.name,
            iconHash = "",
            isShared = false,
            gameSource = GameSource.CUSTOM_GAME,
        )
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
     * Cache is invalidated when Custom Game manual folders change.
     */
    private fun getOrRebuildCache(): Map<Int, String> {
        return CustomGameCache.getOrRebuildCache(
            getManualFolders = { PrefManager.customGameManualFolders },
            readGameIdFromFile = { folder -> readGameIdFromFile(folder) },
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
     * Finds a custom game by its numeric ID (regardless of appId format).
     * Returns the folder path if found, null otherwise.
     */
    fun findCustomGameById(gameId: Int): String? {
        val cache = getOrRebuildCache()
        val folderPath = cache[gameId]

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
                return getOrRebuildCache()[gameId]
            }
        }

        return null
    }

    // Helper function to check if game is installed to match pattern of GOG & Steam Service
    fun isGameInstalled(appId: Int): Boolean {
        val isInstalled = findCustomGameById(appId) != null

        return isInstalled
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

        return findCustomGameById(expectedId)
    }
}
