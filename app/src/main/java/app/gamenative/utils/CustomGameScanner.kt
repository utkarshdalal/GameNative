package app.gamenative.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.events.AndroidEvent
import app.gamenative.service.DownloadService
import com.winlator.container.ContainerManager
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.launch
import timber.log.Timber
import org.json.JSONObject

object CustomGameScanner {

    var extractedGameIconFileName = "gameicon.extracted.ico"

    /**
     * Scan a game folder and return the executable relative path if and only if
     * there is exactly ONE candidate .exe within the folder root or exactly one
     * across all immediate subfolders. Executables whose filenames start with
     * "unins" and "unitycrashhandler" (case-insensitive) are ignored.
     *
     * Examples of returned values:
     * - "game.exe"
     * - "Binaries/Win64/Game-Win64-Shipping.exe"
     */
    fun findUniqueExeRelativeToFolder(folderPath: String): String? = findUniqueExeRelativeToFolder(File(folderPath))

    fun findUniqueExeRelativeToFolder(folder: File): String? {
        if (!folder.exists() || !folder.isDirectory) return null

        fun File.isValidExe(): Boolean = this.isFile && this.name.endsWith(".exe", ignoreCase = true) &&
                !this.name.startsWith("unins", ignoreCase = true) &&
                !this.name.startsWith("unitycrashhandler", ignoreCase = true)

        val candidates = mutableListOf<String>()

        folder.listFiles { it.isValidExe() }?.forEach { f ->
            candidates.add(f.name)
        }

        val subDirs = folder.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sd in subDirs) {
            sd.listFiles { it.isValidExe() }?.forEach { f ->
                val rel = sd.name + "/" + f.name
                candidates.add(rel)
            }
        }

        // Keep only unique items
        val unique = candidates.distinct()
        return if (unique.size == 1) unique.first() else null
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
                android.Manifest.permission.READ_EXTERNAL_STORAGE
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
    fun scanAsLibraryItems(query: String = "", indexOffsetStart: Int = 0, includeWhenInstalledFilterActive: Boolean = true): List<LibraryItem> {
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
        // Note: Icon extraction is now only done when images are fetched from SteamGridDB,
        // not during regular scanning. See CustomGameAppScreen.getGameDisplayInfo()
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
        return GameMetadataManager.getAppId(folder)
    }

    /**
     * Writes the game ID to the .gamenative file in the given folder.
     * Preserves other metadata fields (steamgriddbFetched, releaseDate) if they exist.
     */
    private fun writeGameIdToFile(folder: File, gameId: Int) {
        // Read existing metadata to preserve other fields
        val existing = GameMetadataManager.read(folder)
        val metadata = if (existing != null) {
            // Preserve existing metadata fields, only update appId
            existing.copy(appId = gameId)
        } else {
            // Create new metadata with just the appId
            GameMetadata(appId = gameId)
        }
        GameMetadataManager.write(folder, metadata)
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

    /**
     * Gets the folder path for a Custom Game from its appId using the cache.
     * The appId format is "CUSTOM_GAME_<id>" where id is stored in .gamenative file or derived from folder name.
     * Returns null if the folder cannot be found.
     */
    fun getFolderPathFromAppId(appId: String): String? {
        // Extract the ID from appId (format: "CUSTOM_GAME_<id>")
        if (!appId.startsWith("${GameSource.CUSTOM_GAME.name}_")) {
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

    /**
     * Extracts the icon from the executable file used for game launch.
     * Uses existing logic to find the exe: first checks container's executablePath (if user selected one),
     * otherwise tries to find a unique exe using findUniqueExeRelativeToFolder.
     *
     * First checks for extracted game icon in the game folder.
     * If it doesn't exist, extracts the icon from the exe and creates extracted game icon.
     *
     * @param context The Android context
     * @param appId The app ID of the custom game
     * @return true if icon was extracted or already exists, false otherwise
     */
    fun extractIconFromExecutable(context: Context, appId: String): Boolean {
        try {
            val gameFolderPath = getFolderPathFromAppId(appId)
            if (gameFolderPath == null) {
                Timber.tag("CustomGameScanner").w("Could not find game folder for appId: $appId")
                return false
            }

            val gameFolder = File(gameFolderPath)
            if (!gameFolder.exists() || !gameFolder.isDirectory) {
                Timber.tag("CustomGameScanner").w("Game folder does not exist: $gameFolderPath")
                return false
            }

            // Check if icon already exists
            val iconFile = File(gameFolder, extractedGameIconFileName)
            if (iconFile.exists()) {
                Timber.tag("CustomGameScanner").d("Icon already exists: ${iconFile.absolutePath}")
                return true
            }

            // Get the executable that will be used for game launch using existing container logic
            val container = ContainerUtils.getOrCreateContainer(context, appId)
            var exeRelPath = container.executablePath

            // If container doesn't have an executable path, try finding a unique executable
            if (exeRelPath.isEmpty()) {
                exeRelPath = findUniqueExeRelativeToFolder(gameFolder) ?: run {
                    Timber.tag("CustomGameScanner").w("Could not find executable for game launch: $appId")
                    return false
                }
            }

            val exeFile = File(gameFolder, exeRelPath.replace('/', File.separatorChar))
            if (!exeFile.exists()) {
                Timber.tag("CustomGameScanner").w("Executable file does not exist: ${exeFile.absolutePath}")
                return false
            }

            // Extract icon to gameicon.extracted.ico in the game folder
            Timber.tag("CustomGameScanner").d("Extracting icon from: ${exeFile.absolutePath} to: ${iconFile.absolutePath}")
            val extracted = ExeIconExtractor.tryExtractMainIcon(exeFile, iconFile)

            if (extracted) {
                Timber.tag("CustomGameScanner").d("Successfully extracted icon from: ${exeFile.name}")
                return true
            } else {
                Timber.tag("CustomGameScanner").w("Failed to extract icon from: ${exeFile.name}")
                return false
            }
        } catch (e: Exception) {
            Timber.tag("CustomGameScanner").e(e, "Failed to extract icon from executable for appId: $appId")
            return false
        }
    }

    /**
     * Finds the icon file for a custom game.
     * Looks for extracted game icon in the game folder.
     *
     * @param appId The app ID of the custom game (can be called without context)
     * @return The absolute path to the icon file, or null if not found
     */
    fun findIconFileForCustomGame(appId: String): String? {
        val gameFolderPath = getFolderPathFromAppId(appId) ?: return null
        val gameFolder = File(gameFolderPath)
        if (!gameFolder.exists() || !gameFolder.isDirectory) return null

        val iconFile = File(gameFolder, extractedGameIconFileName)
        return if (iconFile.exists()) iconFile.absolutePath else null
    }

    /**
     * Finds the icon file for a custom game (with context parameter for compatibility).
     * Looks for extracted game icon in the game folder.
     *
     * @param context The Android context (not used, kept for compatibility)
     * @param appId The app ID of the custom game
     * @return The absolute path to the icon file, or null if not found
     */
    fun findIconFileForCustomGame(context: Context, appId: String): String? {
        return findIconFileForCustomGame(appId)
    }
}
