package app.gamenative.service.epic

import android.content.Context
import app.gamenative.data.EpicGame
import app.gamenative.service.epic.manifest.EpicManifest
import app.gamenative.utils.Net
import java.io.File
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Manages Epic Cloud Saves - downloading and uploading save files
 *
 * Epic uses a manifest-based chunked format (similar to game downloads):
 * - Manifest files contain metadata and chunk references
 * - Save files are split into compressed chunks
 * - Chunks are deduplicated via GUID/hash
 */
object EpicCloudSavesManager {

    // Synchronization to prevent duplicate concurrent syncs
    private val syncMutex = Mutex()
    private val activeSyncs = mutableSetOf<Int>()

    // Data classes for API responses
    data class CloudSaveFiles(
        val files: Map<String, CloudFileInfo>,
    )

    private val baseCloudSyncUrl = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com"

    private val httpClient = Net.http

    data class CloudFileInfo(
        val hash: String,
        val lastModified: String,
        val readLink: String?,
        val writeLink: String?,
    )

    enum class SyncAction {
        UPLOAD,
        DOWNLOAD,
        CONFLICT,
        NONE,
    }

    /**
     * Sync cloud saves for a game (bidirectional sync with conflict detection)
     * preferredAction = download -> Force downloads all files and overwrites current files
     * preferredAction = upload -> Force uploads all files
     * preferredAction = "auto" -> Timestamp check and uploads/downloads the files pending on the timestamp resolution
     * @param preferredAction "download", "upload", or "auto" (default)
     */
    suspend fun syncCloudSaves(
        context: Context,
        appId: Int,
        preferredAction: String = "auto",
    ): Boolean = withContext(Dispatchers.IO) {
        // Check if sync is already in progress for this appId
        syncMutex.withLock {
            if (activeSyncs.contains(appId)) {
                Timber.tag("Epic").w("[Cloud Saves] Sync already in progress for $appId, skipping duplicate request")
                return@withContext false
            }
            activeSyncs.add(appId)
        }

        try {
            Timber.tag("Epic").i("[Cloud Saves] Starting sync for $appId (action: $preferredAction)")

            // Get game info to retrieve appName
            val game = EpicService.getEpicGameOf(appId)
            if (game == null) {
                Timber.tag("Epic").e("[Cloud Saves] Game not found: $appId")
                return@withContext false
            }

            // Check if game supports cloud saves
            if (!game.cloudSaveEnabled) {
                Timber.tag("Epic").w("[Cloud Saves] Game does not support cloud saves: ${game.title}")
                return@withContext false
            }

            // Get credentials and validate.
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Not logged in to Epic: ${credentials.exceptionOrNull()?.message}")
                return@withContext false
            }

            val creds = credentials.getOrNull()!!
            Timber.tag("Epic").d("[Cloud Saves] Using account: ${creds.accountId} (${creds.displayName})")

            //  Determine sync action - Upload,Download, Conflict or none
            val action = determineSyncAction(context, creds.accountId, game, preferredAction)

            Timber.tag("Epic").i("[Cloud Saves] Sync action determined: $action")

            // Execute the action
            val result = when (action) {
                SyncAction.DOWNLOAD -> downloadSaves(context, appId, creds.accountId)

                SyncAction.UPLOAD -> uploadSaves(context, creds.accountId, game)

                SyncAction.CONFLICT -> {
                    Timber.tag("Epic").w("[Cloud Saves] Conflict detected - resolving via timestamp comparison")
                    resolveConflict(context, creds.accountId, game)
                }

                SyncAction.NONE -> {
                    Timber.tag("Epic").i("[Cloud Saves] No sync needed")
                    true
                }
            }

            if (result) {
                Timber.tag("Epic").i("[Cloud Saves] Sync completed successfully")
            }

            result
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Sync failed")
            false
        } finally {
            // Always remove from active syncs when done
            syncMutex.withLock {
                activeSyncs.remove(appId)
            }
        }
    }

    /**
     * Determine what sync action to take based on local and cloud state
     */
    private suspend fun determineSyncAction(
        context: Context,
        accountId: String,
        game: app.gamenative.data.EpicGame,
        preferredAction: String,
    ): SyncAction = withContext(Dispatchers.IO) {
        try {
            // Force action if requested
            if (preferredAction == "download") return@withContext SyncAction.DOWNLOAD
            if (preferredAction == "upload") return@withContext SyncAction.UPLOAD

            // Check local save directory
            val saveDir = resolveSaveDirectory(context, game, accountId)
            val hasLocalFiles = saveDir?.exists() == true && (saveDir.listFiles()?.isNotEmpty() == true)

            // Check cloud saves
            val cloudSavesResult = listCloudSaves(game.appName, context)
            if (cloudSavesResult.isFailure) {
                Timber.tag("Epic").w("[Cloud Saves] Failed to list cloud saves, will try upload if local files exist")
                return@withContext if (hasLocalFiles) SyncAction.UPLOAD else SyncAction.NONE
            }

            val cloudSaves = cloudSavesResult.getOrNull()!!
            val hasCloudFiles = cloudSaves.files.isNotEmpty()

            // Simple cases
            when {
                hasLocalFiles && !hasCloudFiles -> return@withContext SyncAction.UPLOAD
                !hasLocalFiles && hasCloudFiles -> return@withContext SyncAction.DOWNLOAD
                !hasLocalFiles && !hasCloudFiles -> return@withContext SyncAction.NONE
            }

            // Both local and cloud have files - compare timestamps
            val (_, manifestInfo) = findLatestManifest(cloudSaves.files) ?: run {
                Timber.tag("Epic").w("[Cloud Saves] No manifest in cloud, will upload")
                return@withContext SyncAction.UPLOAD
            }

            val lastSync = getSyncTimestamp(context, game.id)
            val cloudTimestamp = manifestInfo.lastModified

            // Get local newest file timestamp
            val localNewestTimestamp = saveDir?.let { dir ->
                dir.walkTopDown()
                    .filter { it.isFile }
                    .maxOfOrNull { it.lastModified() }
            }

            Timber.tag("Epic").d("[Cloud Saves] Cloud timestamp: $cloudTimestamp, Last sync: $lastSync")
            Timber.tag("Epic").d("[Cloud Saves] Local newest file: $localNewestTimestamp")

            // If we have a last sync timestamp, use it for conflict detection
            if (lastSync != null) {
                val cloudNewer = cloudTimestamp > lastSync
                val localNewer = localNewestTimestamp != null && localNewestTimestamp > parseTimestamp(lastSync)

                when {
                    cloudNewer && !localNewer -> return@withContext SyncAction.DOWNLOAD
                    localNewer && !cloudNewer -> return@withContext SyncAction.UPLOAD
                    cloudNewer && localNewer -> return@withContext SyncAction.CONFLICT
                    else -> return@withContext SyncAction.NONE
                }
            }

            // No sync timestamp - just compare cloud vs local
            if (cloudTimestamp >= (lastSync ?: "")) {
                SyncAction.DOWNLOAD
            } else {
                SyncAction.NONE
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Error determining sync action")
            SyncAction.NONE
        }
    }

    /**
     * Parse Epic timestamp string to milliseconds
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            val instant = Instant.parse(timestamp)
            instant.toEpochMilli()
        } catch (e: Exception) {
            Timber.tag("Epic").w(e, "[Cloud Saves] Failed to parse timestamp: $timestamp")
            0L
        }
    }

    // List available cloud saves
    private suspend fun listCloudSaves(appName: String, context: Context): Result<CloudSaveFiles> = withContext(Dispatchers.IO) {
        try {
            // Get global Epic credentials (will auto-refresh if expired)
            val credentialsResult = EpicAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                return@withContext Result.failure(Exception("Not logged in to Epic"))
            }

            val credentials = credentialsResult.getOrNull()!!
            val accountId = credentials.accountId
            val accessToken = credentials.accessToken

            Timber.tag("Epic").d("[Cloud Saves] Listing saves for $appName (account: $accountId)")

            val request = Request.Builder()
                .url("$baseCloudSyncUrl/api/v1/access/egstore/savesync/$accountId/$appName/")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to list cloud saves: ${response.code}"))
                }

                val json = org.json.JSONObject(response.body?.string() ?: "{}")
                val filesJson = json.optJSONObject("files") ?: org.json.JSONObject()

                val files = mutableMapOf<String, CloudFileInfo>()
                filesJson.keys().forEach { key ->
                    val fileJson = filesJson.getJSONObject(key)
                    files[key] = CloudFileInfo(
                        hash = fileJson.optString("hash", ""),
                        lastModified = fileJson.optString("lastModified", ""),
                        readLink = fileJson.optString("readLink"),
                        writeLink = fileJson.optString("writeLink"),
                    )
                }

                Result.success(CloudSaveFiles(files))
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to list cloud saves")
            Result.failure(e)
        }
    }

    // Download a single file
    private suspend fun downloadFile(readLink: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(readLink)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: ${response.code}"))
                }

                val data = response.body?.bytes() ?: return@withContext Result.failure(Exception("Empty response"))

                if (data.isEmpty()) {
                    Timber.tag("Epic").w("[Cloud Saves] Downloaded file is empty (0 bytes)")
                }

                Result.success(data)
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "Failed to download file")
            Result.failure(e)
        }
    }

    // Find the latest manifest
    private fun findLatestManifest(files: Map<String, CloudFileInfo>): Pair<String, CloudFileInfo>? {
        return files.entries
            .filter { it.key.endsWith(".manifest") }
            .maxByOrNull { it.value.lastModified }
            ?.toPair()
    }

    /**
     * Resolve conflict by comparing timestamps and selectively uploading/downloading
     */
    private suspend fun resolveConflict(
        context: Context,
        accountId: String,
        game: EpicGame,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("[Cloud Saves] Starting conflict resolution for ${game.id}")

            // 1. Get local save directory and files
            val saveDir = resolveSaveDirectory(context, game, accountId) ?: run {
                Timber.tag("Epic").e("[Cloud Saves] Failed to resolve save directory")
                return@withContext false
            }

            val localFiles = mutableMapOf<String, Long>()
            if (saveDir.exists()) {
                saveDir.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        val relativePath = file.relativeTo(saveDir).path.replace("\\", "/")
                        localFiles[relativePath] = file.lastModified()
                    }
            }

            Timber.tag("Epic").i("[Cloud Saves] Found ${localFiles.size} local files")

            // 2. Get cloud files and their timestamps
            val cloudSavesResult = listCloudSaves(game.appName, context)
            if (cloudSavesResult.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to list cloud saves")
                return@withContext false
            }

            val cloudSaves = cloudSavesResult.getOrNull()!!
            val (manifestPath, manifestInfo) = findLatestManifest(cloudSaves.files) ?: run {
                Timber.tag("Epic").w("[Cloud Saves] No manifest in cloud, uploading all local files")
                return@withContext uploadSaves(context, accountId, game)
            }

            // 3. Download and parse manifest to get cloud file list with timestamps
            val manifestData = downloadFile(manifestInfo.readLink ?: return@withContext false)
            if (manifestData.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to download manifest")
                return@withContext false
            }

            val manifestBytes = manifestData.getOrNull()!!

            // Validate manifest is not empty
            if (manifestBytes.isEmpty()) {
                Timber.tag("Epic").w("[Cloud Saves] Cloud manifest is empty, uploading all local files")
                return@withContext uploadSaves(context, accountId, game)
            }

            val manifest = try {
                EpicManifest.readAll(manifestBytes)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[Cloud Saves] Failed to parse manifest (size: ${manifestBytes.size} bytes)")
                // If manifest is corrupt, upload our local version
                Timber.tag("Epic").w("[Cloud Saves] Manifest parse failed, uploading local files")
                return@withContext uploadSaves(context, accountId, game)
            }

            // Build map of cloud files with their modification times
            val cloudFiles = mutableMapOf<String, Long>()
            manifest.fileManifestList?.elements?.forEach { fileManifest ->
                // Use the file's symlink modified time if available, otherwise use manifest timestamp
                val timestamp = parseTimestamp(manifestInfo.lastModified)
                cloudFiles[fileManifest.filename] = timestamp
            }

            Timber.tag("Epic").i("[Cloud Saves] Found ${cloudFiles.size} cloud files")

            // 4. Compare timestamps and decide what to upload/download
            val toUpload = mutableListOf<String>()
            val toDownload = mutableListOf<String>()

            // Check files that exist in both locations
            val commonPaths = localFiles.keys.intersect(cloudFiles.keys)
            commonPaths.forEach { path ->
                val localTime = localFiles[path]!!
                val cloudTime = cloudFiles[path]!!

                when {
                    localTime > cloudTime -> {
                        Timber.tag("Epic").i("[Cloud Saves] Local file is newer: $path (local: $localTime > cloud: $cloudTime)")
                        toUpload.add(path)
                    }

                    cloudTime > localTime -> {
                        Timber.tag("Epic").i("[Cloud Saves] Cloud file is newer: $path (cloud: $cloudTime > local: $localTime)")
                        toDownload.add(path)
                    }

                    else -> {
                        Timber.tag("Epic").d("[Cloud Saves] Files have same timestamp, skipping: $path")
                    }
                }
            }

            // Files that only exist locally should be uploaded
            (localFiles.keys - commonPaths).forEach { path ->
                Timber.tag("Epic").i("[Cloud Saves] File only exists locally: $path")
                toUpload.add(path)
            }

            // Files that only exist in cloud should be downloaded
            (cloudFiles.keys - commonPaths).forEach { path ->
                Timber.tag("Epic").i("[Cloud Saves] File only exists in cloud: $path")
                toDownload.add(path)
            }

            // 5. Execute downloads first (so we have all cloud files locally before uploading)
            var downloadSuccess = true
            if (toDownload.isNotEmpty()) {
                Timber.tag("Epic").i("[Cloud Saves] Downloading ${toDownload.size} files based on timestamp comparison")

                // Download the required chunks and reconstruct files
                val chunks = mutableMapOf<String, ByteArray>()
                val pathPrefix = manifestPath.split("/", limit = 4).take(3).joinToString("/")

                Timber.tag("Epic").d("[Cloud Saves] Manifest path: $manifestPath")
                Timber.tag("Epic").d("[Cloud Saves] Path prefix: $pathPrefix")
                Timber.tag("Epic").d("[Cloud Saves] Available cloud files: ${cloudSaves.files.keys.take(10)}")

                manifest.chunkDataList?.elements?.forEach { chunkInfo ->
                    try {
                        val chunkPath = "$pathPrefix/${chunkInfo.getPath()}"
                        Timber.tag("Epic").d("[Cloud Saves] Looking for chunk at: $chunkPath")
                        val chunkFile = cloudSaves.files[chunkPath]

                        if (chunkFile?.readLink == null) {
                            Timber.tag("Epic").w("[Cloud Saves] Chunk not found in cloud: $chunkPath")
                            downloadSuccess = false
                            return@forEach
                        }

                        Timber.tag("Epic").d("[Cloud Saves] Downloading chunk: ${chunkInfo.getPath()}")
                        val chunkData = downloadFile(chunkFile.readLink)
                        if (chunkData.isSuccess) {
                            val chunkBytes = chunkData.getOrNull()!!
                            val decompressedData = decompressChunk(chunkBytes)
                            chunks[chunkInfo.guidStr] = decompressedData
                        }
                    } catch (e: Exception) {
                        Timber.tag("Epic").e(e, "[Cloud Saves] Error processing chunk: ${chunkInfo.getPath()}")
                    }
                }

                // Reconstruct only the files we need to download
                manifest.fileManifestList?.elements?.forEach { fileManifest ->
                    if (toDownload.contains(fileManifest.filename)) {
                        try {
                            val outputFile = File(saveDir, fileManifest.filename)
                            outputFile.parentFile?.mkdirs()

                            Timber.tag("Epic").d("[Cloud Saves] Reconstructing file: ${fileManifest.filename}")

                            outputFile.outputStream().use { output ->
                                fileManifest.chunkParts.forEach { chunkPart ->
                                    val chunkData = chunks[chunkPart.guidStr]
                                    if (chunkData == null) {
                                        Timber.tag("Epic").e("[Cloud Saves] Chunk missing for ${fileManifest.filename}: ${chunkPart.guidStr}")
                                        downloadSuccess = false
                                    } else {
                                        val partData = chunkData.copyOfRange(
                                            chunkPart.offset.toInt(),
                                            (chunkPart.offset + chunkPart.size).toInt(),
                                        )
                                        output.write(partData)
                                    }
                                }
                            }

                            Timber.tag("Epic").i("[Cloud Saves] Downloaded: ${fileManifest.filename}")
                        } catch (e: Exception) {
                            Timber.tag("Epic").e(e, "[Cloud Saves] Failed to reconstruct file: ${fileManifest.filename}")
                            downloadSuccess = false
                        }
                    }
                }
            }

            // 6. Execute uploads
            var uploadSuccess = true
            if (toUpload.isNotEmpty()) {
                Timber.tag("Epic").i("[Cloud Saves] Uploading ${toUpload.size} files based on timestamp comparison")
                // ! Upload ALL local files, to ensure the manifest is correct with save-state
                uploadSuccess = uploadSaves(context, accountId, game)
            }

            // 7. Update sync timestamp if both operations succeeded
            if (downloadSuccess && uploadSuccess) {
                val timestamp = java.time.Instant.now().toString()
                setSyncTimestamp(context, game.id, timestamp)
                Timber.tag("Epic").i("[Cloud Saves] Conflict resolution complete")
                return@withContext true
            }

            false
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Conflict resolution failed")
            false
        }
    }

    // Download saves flow
    private suspend fun downloadSaves(context: Context, appId: Int, accountId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("[Cloud Saves] Starting download for $appId")

            // 1. Get game info
            val game = EpicService.getEpicGameOf(appId)
            if (game?.cloudSaveEnabled != true) {
                Timber.tag("Epic").w("[Cloud Saves] Game does not support cloud saves")
                return@withContext false
            }

            // 2. List cloud saves
            val cloudSavesResult = listCloudSaves(game.appName, context)
            if (cloudSavesResult.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to list saves: ${cloudSavesResult.exceptionOrNull()?.message}")
                return@withContext false
            }

            val cloudSaves = cloudSavesResult.getOrNull()!!
            if (cloudSaves.files.isEmpty()) {
                Timber.tag("Epic").i("[Cloud Saves] No cloud saves found")
                return@withContext false
            }

            // 3. Find latest manifest
            val (manifestPath, manifestInfo) = findLatestManifest(cloudSaves.files) ?: run {
                Timber.tag("Epic").w("[Cloud Saves] No manifest found in cloud saves")
                return@withContext false
            }

            Timber.tag("Epic").i("[Cloud Saves] Found manifest: $manifestPath (${manifestInfo.lastModified})")

            // 4. Check if we need to download
            val lastSync = getSyncTimestamp(context, appId)
            if (lastSync != null && lastSync >= manifestInfo.lastModified) {
                Timber.tag("Epic").i("[Cloud Saves] Local saves are up to date")
                return@withContext true
            }

            // 5. Download manifest
            val manifestData = downloadFile(manifestInfo.readLink ?: return@withContext false)
            if (manifestData.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to download manifest")
                return@withContext false
            }

            // 6. Parse manifest
            val manifestBytes = manifestData.getOrNull()!!

            // Validate manifest is not empty
            if (manifestBytes.isEmpty()) {
                Timber.tag("Epic").e("[Cloud Saves] Downloaded manifest is empty")
                return@withContext false
            }

            val manifest = try {
                EpicManifest.readAll(manifestBytes)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[Cloud Saves] Failed to parse manifest (size: ${manifestBytes.size} bytes)")
                return@withContext false
            }

            Timber.tag("Epic").i("[Cloud Saves] Manifest parsed: ${manifest.fileManifestList?.elements?.size ?: 0} files")

            // 7. Download chunks referenced in manifest
            val chunks = mutableMapOf<String, ByteArray>()
            val pathPrefix = manifestPath.split("/", limit = 4).take(3).joinToString("/")

            manifest.chunkDataList?.elements?.forEach { chunkInfo ->
                try {
                    // Get chunk path using ChunkInfo's getPath method
                    val chunkPath = "$pathPrefix/${chunkInfo.getPath()}"
                    val chunkFile = cloudSaves.files[chunkPath]

                    if (chunkFile?.readLink == null) {
                        Timber.tag("Epic").w("[Cloud Saves] Chunk not found in cloud: $chunkPath")
                        return@forEach
                    }

                    Timber.tag("Epic").d("[Cloud Saves] Downloading chunk: ${chunkInfo.getPath()}")
                    val chunkData = downloadFile(chunkFile.readLink)
                    if (chunkData.isSuccess) {
                        // Decompress and extract chunk data
                        val chunkBytes = chunkData.getOrNull()!!
                        val decompressedData = decompressChunk(chunkBytes)
                        chunks[chunkInfo.guidStr] = decompressedData
                        Timber.tag("Epic").d("[Cloud Saves] Chunk downloaded: ${chunkInfo.guidStr} (${decompressedData.size} bytes)")
                    } else {
                        Timber.tag("Epic").e("[Cloud Saves] Failed to download chunk: ${chunkInfo.getPath()}")
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "[Cloud Saves] Error processing chunk: ${chunkInfo.getPath()}")
                }
            }

            if (chunks.isEmpty()) {
                Timber.tag("Epic").e("[Cloud Saves] No chunks were downloaded, aborting")
                return@withContext false
            }

            // 8. Reconstruct files from chunks
            val saveDir = resolveSaveDirectory(context, game, accountId) ?: run {
                Timber.tag("Epic").e("[Cloud Saves] Failed to resolve save directory")
                return@withContext false
            }

            saveDir.mkdirs()

            var downloadedFiles = 0

            manifest.fileManifestList?.elements?.forEach { fileManifest ->
                try {
                    val outputFile = File(saveDir, fileManifest.filename)
                    outputFile.parentFile?.mkdirs()

                    Timber.tag("Epic").d("[Cloud Saves] Reconstructing file: ${fileManifest.filename}")

                    outputFile.outputStream().use { output ->
                        fileManifest.chunkParts.forEach { chunkPart ->
                            val chunkData = chunks[chunkPart.guidStr]
                            if (chunkData == null) {
                                Timber.tag("Epic").e("[Cloud Saves] Chunk missing for ${fileManifest.filename}: ${chunkPart.guidStr}")
                            } else {
                                // Extract the specific part of the chunk for this file
                                val partData = chunkData.copyOfRange(
                                    chunkPart.offset.toInt(),
                                    (chunkPart.offset + chunkPart.size).toInt(),
                                )
                                output.write(partData)
                            }
                        }
                    }

                    downloadedFiles++
                    Timber.tag("Epic").i("[Cloud Saves] Reconstructed: ${fileManifest.filename} (${outputFile.length()} bytes)")
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "[Cloud Saves] Failed to reconstruct file: ${fileManifest.filename}")
                }
            }

            // 9. Update sync timestamp
            setSyncTimestamp(context, appId, manifestInfo.lastModified)

            Timber.tag("Epic").i("[Cloud Saves] Download complete: $downloadedFiles files reconstructed")
            downloadedFiles > 0
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Download failed")
            false
        }
    }

    // Upload saves flow
    private suspend fun uploadSaves(
        context: Context,
        accountId: String,
        game: EpicGame,
        fileList: List<String>? = null, // Optional: only upload specific files
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("[Cloud Saves] Starting upload for ${game.id}")

            // 1. Get local save directory
            val saveDir = resolveSaveDirectory(context, game, accountId) ?: run {
                Timber.tag("Epic").e("[Cloud Saves] Failed to resolve save directory")
                return@withContext false
            }

            if (!saveDir.exists() || saveDir.listFiles()?.isEmpty() == true) {
                Timber.tag("Epic").w("[Cloud Saves] No local saves to upload")
                return@withContext false
            }

            // 2. Package save files into chunks and manifest
            if (fileList != null) {
                Timber.tag("Epic").i("[Cloud Saves] Packaging ${fileList.size} specific files from: ${saveDir.absolutePath}")
            } else {
                Timber.tag("Epic").i("[Cloud Saves] Packaging all save files from: ${saveDir.absolutePath}")
            }
            val packagedFiles = packageSaveFiles(saveDir, game, accountId, fileList)
            if (packagedFiles.isEmpty()) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to package save files")
                return@withContext false
            }

            // Filter out empty files (just log them, don't fail the upload)
            val emptyFiles = packagedFiles.filter { it.value.isEmpty() }
            if (emptyFiles.isNotEmpty()) {
                Timber.tag("Epic").w("[Cloud Saves] Skipping ${emptyFiles.size} empty packaged files: ${emptyFiles.keys.joinToString()}")
            }

            // Only upload non-empty files
            val nonEmptyFiles = packagedFiles.filterValues { it.isNotEmpty() }
            if (nonEmptyFiles.isEmpty()) {
                Timber.tag("Epic").e("[Cloud Saves] No valid files to upload after filtering empty files")
                return@withContext false
            }

            // 3. Request write links for all files
            val fileNames = nonEmptyFiles.keys.toList()
            val writeLinks = requestWriteLinks(context, game.appName, fileNames)
            if (writeLinks.isEmpty()) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to get write links")
                return@withContext false
            }

            // 4. Upload chunks
            var uploadedChunks = 0
            nonEmptyFiles.forEach { (fileName, fileData) ->
                if (!fileName.endsWith(".manifest")) {
                    val writeLink = writeLinks[fileName]
                    if (writeLink != null) {
                        Timber.tag("Epic").d("[Cloud Saves] Uploading chunk: $fileName (${fileData.size} bytes)")
                        val result = uploadFile(writeLink, fileData)
                        if (result.isSuccess) {
                            uploadedChunks++
                            Timber.tag("Epic").i("[Cloud Saves] Uploaded chunk: $fileName (${fileData.size} bytes)")
                        } else {
                            Timber.tag("Epic").e("[Cloud Saves] Failed to upload chunk: $fileName - ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
            }

            // 5. Upload manifest last
            val manifestEntry = nonEmptyFiles.entries.find { it.key.endsWith(".manifest") }
            if (manifestEntry != null) {
                val writeLink = writeLinks[manifestEntry.key]
                if (writeLink != null) {
                    Timber.tag("Epic").d("[Cloud Saves] Uploading manifest: ${manifestEntry.key} (${manifestEntry.value.size} bytes)")
                    val result = uploadFile(writeLink, manifestEntry.value)
                    if (result.isSuccess) {
                        Timber.tag("Epic").i("[Cloud Saves] Uploaded manifest: ${manifestEntry.key} (${manifestEntry.value.size} bytes)")

                        // Update sync timestamp
                        val timestamp = java.time.Instant.now().toString()
                        setSyncTimestamp(context, game.id, timestamp)

                        Timber.tag("Epic").i("[Cloud Saves] Upload complete: $uploadedChunks chunks uploaded")
                        return@withContext true
                    } else {
                        Timber.tag("Epic").e("[Cloud Saves] Failed to upload manifest: ${result.exceptionOrNull()?.message}")
                    }
                }
            }

            false
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Upload failed")
            false
        }
    }

    // Request write links for files
    private suspend fun requestWriteLinks(
        context: Context,
        appName: String,
        fileNames: List<String>,
    ): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val credentialsResult = EpicAuthManager.getStoredCredentials(context)
            if (credentialsResult.isFailure) {
                return@withContext emptyMap()
            }

            val credentials = credentialsResult.getOrNull()!!
            val accountId = credentials.accountId
            val accessToken = credentials.accessToken

            Timber.tag("Epic").d("[Cloud Saves] Requesting write links for ${fileNames.size} files")

            // Log the file names being requested
            fileNames.forEach { name ->
                Timber.tag("Epic").d("[Cloud Saves] Requesting write link for: $name")
            }

            // Request write links for all files at once
            // API expects a BulkLinkRequest object with a "files" array property
            val requestJson = JSONObject().apply {
                put("files", JSONArray(fileNames))
            }
            val requestBody = requestJson.toString()
            Timber.tag("Epic").d("[Cloud Saves] Request body: $requestBody")

            val request = Request.Builder()
                .url("$baseCloudSyncUrl/api/v1/access/egstore/savesync/$accountId/$appName/")
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            Timber.tag("Epic").d("[Cloud Saves] Response code: ${response.code}")

            val responseBody = try {
                response.body?.string() ?: ""
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[Cloud Saves] Failed to read response body")
                ""
            }

            response.close()

            if (!response.isSuccessful) {
                Timber.tag("Epic").e("[Cloud Saves] Failed to request write links: ${response.code}")
                Timber.tag("Epic").e("[Cloud Saves] Response body: $responseBody")
                return@withContext emptyMap()
            }

            try {
                val json = JSONObject(responseBody.ifEmpty { "{}" })
                val filesJson = json.optJSONObject("files") ?: JSONObject()

                val writeLinks = mutableMapOf<String, String>()
                filesJson.keys().forEach { key ->
                    val fileJson = filesJson.getJSONObject(key)
                    val writeLink = fileJson.optString("writeLink")
                    if (writeLink.isNotEmpty()) {
                        writeLinks[key] = writeLink
                    }
                }

                Timber.tag("Epic").i("[Cloud Saves] Received ${writeLinks.size} write links")
                writeLinks
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[Cloud Saves] Failed to parse write links response")
                Timber.tag("Epic").e("[Cloud Saves] Response was: $responseBody")
                emptyMap()
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Failed to request write links")
            emptyMap()
        }
    }

    // Upload a single file
    private suspend fun uploadFile(writeLink: String, data: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(writeLink)
                .header("Content-Type", "application/octet-stream")
                .put(data.toRequestBody("application/octet-stream".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Upload failed: ${response.code}"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Failed to upload file")
            Result.failure(e)
        }
    }

    // Package save files into chunks and manifest
    private fun packageSaveFiles(
        saveDir: File,
        game: EpicGame,
        accountId: String,
        fileList: List<String>? = null, // Optional: only package specific files
    ): Map<String, ByteArray> {
        try {
            Timber.tag("Epic").i("[Cloud Saves] Packaging files from: ${saveDir.absolutePath}")

            val allFiles = saveDir.walkTopDown()
                .filter { it.isFile }
                .toList()

            // Filter to only requested files if fileList is provided
            val files = if (fileList != null) {
                allFiles.filter { file ->
                    val relativePath = file.relativeTo(saveDir).path.replace("\\", "/")
                    val included = fileList.contains(relativePath)
                    if (included) {
                        Timber.tag("Epic").d("[Cloud Saves] Including file: $relativePath")
                    }
                    included
                }
            } else {
                allFiles
            }.sortedBy { it.name.lowercase() }

            if (files.isEmpty()) {
                Timber.tag("Epic").w("[Cloud Saves] No files found to package")
                return emptyMap()
            }

            Timber.tag("Epic").i("[Cloud Saves] Found ${files.size} files to package")

            val packagedFiles = mutableMapOf<String, ByteArray>()
            val chunks = mutableListOf<app.gamenative.service.epic.manifest.ChunkInfo>()
            val fileManifests = mutableListOf<app.gamenative.service.epic.manifest.FileManifest>()

            var chunkNum = 0
            var currentChunkData = mutableListOf<Byte>()
            // currentChunkGuid tracks the single GUID shared by the current in-flight chunk's
            // ChunkParts AND the ChunkInfo/chunk-file header — all three must be identical.
            var currentChunkGuid = generateGuid()
            val chunkSize = 1024 * 1024 // 1 MB chunks

            // Process each file
            files.forEach { file ->
                try {
                    val relativePath = file.relativeTo(saveDir).path.replace("\\", "/")

                    // Skip empty files
                    if (file.length() == 0L) {
                        Timber.tag("Epic").w("[Cloud Saves] Skipping empty file: $relativePath")
                        return@forEach
                    }

                    Timber.tag("Epic").d("[Cloud Saves] Processing file: $relativePath (${file.length()} bytes)")

                    val fileManifest = app.gamenative.service.epic.manifest.FileManifest()
                    fileManifest.filename = relativePath
                    fileManifest.fileSize = file.length()

                    val fileData = file.readBytes()
                    val fileHash = java.security.MessageDigest.getInstance("SHA-1").digest(fileData)
                    fileManifest.hash = fileHash

                    var fileOffset = 0L

                    // Split file into chunk parts
                    while (fileOffset < fileData.size) {
                        // Check if we need to finalize current chunk
                        if (currentChunkData.size >= chunkSize) {
                            val chunk = finalizeChunk(currentChunkData.toByteArray(), currentChunkGuid, chunkNum++, packagedFiles)
                            chunks.add(chunk)
                            currentChunkData.clear()
                            // Fresh GUID for the next chunk
                            currentChunkGuid = generateGuid()
                        }

                        val offset = currentChunkData.size
                        val partFileOffset = fileOffset

                        val remainingInChunk = (chunkSize - currentChunkData.size).coerceAtMost((fileData.size - fileOffset).toInt())
                        val size = remainingInChunk

                        // Add data to current chunk
                        currentChunkData.addAll(fileData.sliceArray(fileOffset.toInt() until (fileOffset + remainingInChunk).toInt()).toList())

                        // Create chunk part — uses the same GUID as the ChunkInfo that will be
                        // created by finalizeChunk() for this chunk buffer.
                        val chunkPart = app.gamenative.service.epic.manifest.ChunkPart(
                            guid = currentChunkGuid,
                            offset = offset,
                            size = size,
                            fileOffset = partFileOffset,
                        )

                        fileManifest.chunkParts.add(chunkPart)
                        fileOffset += remainingInChunk
                    }

                    fileManifests.add(fileManifest)
                } catch (e: Exception) {
                    Timber.tag("Epic").e(e, "[Cloud Saves] Failed to process file: ${file.name}")
                }
            }

            // Finalize last chunk if it has data
            if (currentChunkData.isNotEmpty()) {
                val chunk = finalizeChunk(currentChunkData.toByteArray(), currentChunkGuid, chunkNum++, packagedFiles)
                chunks.add(chunk)
            }

            // Create manifest
            val manifest = createManifest(game, accountId, chunks, fileManifests)
            val manifestData = manifest.serialize()

            val timestamp = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
            val manifestName = "manifests/${timestamp.format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm.ss"))}.manifest"
            packagedFiles[manifestName] = manifestData

            Timber.tag("Epic").i("[Cloud Saves] Packaged ${fileManifests.size} files into ${chunks.size} chunks")
            return packagedFiles
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Failed to package save files")
            return emptyMap()
        }
    }

    // Finalize a chunk (compress and store)
    // guid must be the same IntArray already assigned to all ChunkParts that reference this chunk,
    // so that ChunkPart.guidStr == ChunkInfo.guidStr == the GUID in the chunk file header.
    private fun finalizeChunk(
        data: ByteArray,
        guid: IntArray,
        chunkNum: Int,
        packagedFiles: MutableMap<String, ByteArray>,
    ): app.gamenative.service.epic.manifest.ChunkInfo {
        // Pad to 1 MB if needed
        val paddedData = if (data.size < 1024 * 1024) {
            data + ByteArray(1024 * 1024 - data.size)
        } else {
            data
        }

        // Calculate hashes on the unpadded/padded data
        val shaHash = java.security.MessageDigest.getInstance("SHA-1").digest(paddedData)
        val rollingHash = calculateRollingHash(paddedData)

        // Compute groupNum exactly as Legendary does:
        // group_num = crc32(struct.pack('<IIII', *guid)) & 0xffffffff) % 100
        val guidBytes = ByteArray(16)
        val guidBuf = java.nio.ByteBuffer.wrap(guidBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        guid.forEach { guidBuf.putInt(it) }
        val crc32 = java.util.zip.CRC32()
        crc32.update(guidBytes)
        val groupNum = (crc32.value % 100).toInt()

        // Create chunk info with the caller-supplied GUID (same as the ChunkParts)
        val chunkInfo = app.gamenative.service.epic.manifest.ChunkInfo()
        chunkInfo.guid = guid
        chunkInfo.hash = rollingHash
        chunkInfo.shaHash = shaHash
        chunkInfo.groupNum = groupNum
        chunkInfo.windowSize = paddedData.size

        // Compress chunk — pass guid so the header GUID matches the CDL entry
        val compressedData = compressChunk(paddedData, guid, rollingHash, shaHash)
        chunkInfo.fileSize = compressedData.size.toLong()

        // Store chunk data under its canonical path
        val chunkPath = chunkInfo.getPath()
        packagedFiles[chunkPath] = compressedData

        Timber.tag("Epic").d("[Cloud Saves] Finalized chunk #$chunkNum: ${chunkInfo.guidStr} groupNum=$groupNum (${compressedData.size} bytes)")

        return chunkInfo
    }

    // Compress chunk data with the Epic binary chunk header.
    // 66-byte header:
    //   magic(4) + version(4) + headerSize(4) + compressedSize(4)
    //   + guid(16) + hash(8) + storedAs(1)
    //   + shaHash(20) + hashType(1) + uncompressedSize(4)   ← header version 2+3 fields
    //   = 66 bytes
    // guid/rollingHash/shaHash must already be computed by the caller (finalizeChunk) so that
    // the values written into the header are identical to what is stored in the CDL entry.
    internal fun compressChunk(data: ByteArray, guid: IntArray, rollingHash: ULong, shaHash: ByteArray): ByteArray {
        // Compress payload
        val compressed = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { it.write(data) }
        val compressedData = compressed.toByteArray()

        // hardcode headersize to 66 as required
        val headerSize = 66
        val buffer = java.nio.ByteBuffer.allocate(headerSize + compressedData.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        buffer.putInt(0xB1FE3AA2.toInt())   // magic
        buffer.putInt(3)                     // header_version (we always write v3)
        buffer.putInt(headerSize)            // header_size = 66 (hardcoded, not back-filled)
        buffer.putInt(compressedData.size)   // compressed_size

        // GUID — must match ChunkInfo.guid and every ChunkPart.guid referencing this chunk
        guid.forEach { buffer.putInt(it) }

        // Rolling hash — must match ChunkInfo.hash
        buffer.putLong(rollingHash.toLong())

        buffer.put(0x1.toByte())             // stored_as = compressed
        // Header version 2: sha_hash + hash_type
        buffer.put(shaHash)                  // SHA-1 of uncompressed data (20 bytes)
        buffer.put(0x3.toByte())             // hash_type = 0x3 (both rolling + sha)
        // Header version 3: uncompressed_size
        buffer.putInt(data.size)             // uncompressed_size (1 MiB for full chunks)

        buffer.put(compressedData)

        return buffer.array().copyOf(buffer.position())
    }

    // Generate a random GUID (4 integers)
    private fun generateGuid(): IntArray {
        val random = java.security.SecureRandom()
        return IntArray(4) { random.nextInt() }
    }

    /**
     * CRC-64-ECMA variant lookup table
     * Polynomial: 0xC96C5795D7870F42
     * Table built identically to Legendary's _init():
     *   for i in 0..255:
     *     for _ in 0..7: if i&1 -> i = (i>>1) ^ poly  else i >>= 1
     */
    private val ROLLING_HASH_TABLE: LongArray = run {
        val poly = 0xC96C5795D7870F42uL
        LongArray(256) { seed ->
            var v = seed.toULong()
            repeat(8) {
                v = if ((v and 1uL) != 0uL) (v shr 1) xor poly else v shr 1
            }
            v.toLong()
        }
    }

    /**
     * Epic Games rolling hash — exact port of Legendary's get_hash() in rolling_hash.py:
     *   h = 0
     *   for each byte i: h = ((h << 1 | h >> 63) ^ table[data[i]]) & 0xffffffffffffffff
     */
    internal fun calculateRollingHash(data: ByteArray): ULong {
        var h = 0uL
        for (byte in data) {
            val tableVal = ROLLING_HASH_TABLE[byte.toInt() and 0xFF].toULong()
            h = ((h shl 1) or (h shr 63)) xor tableVal
        }
        return h
    }

    // Create manifest
    private fun createManifest(
        game: EpicGame,
        accountId: String,
        chunks: List<app.gamenative.service.epic.manifest.ChunkInfo>,
        fileManifests: List<app.gamenative.service.epic.manifest.FileManifest>,
    ): app.gamenative.service.epic.manifest.EpicManifest {
        val manifest = app.gamenative.service.epic.manifest.BinaryManifest()

        // Meta
        manifest.meta = app.gamenative.service.epic.manifest.ManifestMeta()
        manifest.meta!!.appName = "${game.appName}$accountId"
        val timestamp = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        manifest.meta!!.buildVersion = timestamp.format(java.time.format.DateTimeFormatter.ofPattern("yyyy.MM.dd-HH.mm.ss"))

        // Custom fields
        manifest.customFields = app.gamenative.service.epic.manifest.CustomFields()
        manifest.customFields!!["CloudSaveFolder"] = game.saveFolder

        // Chunks
        manifest.chunkDataList = app.gamenative.service.epic.manifest.ChunkDataList()
        manifest.chunkDataList!!.elements.addAll(chunks)

        // Files
        manifest.fileManifestList = app.gamenative.service.epic.manifest.FileManifestList()
        manifest.fileManifestList!!.elements.addAll(fileManifests)

        return manifest
    }

    // Resolve save directory path
    private fun resolveSaveDirectory(context: Context, game: EpicGame, accountId: String): File? {
        val cloudSaveFolder = game.saveFolder.ifEmpty { return null }

        // Get the container's Wine prefix path (similar to GOG)
        val appId = "EPIC_${game.id}"
        val container = app.gamenative.utils.ContainerUtils.getOrCreateContainer(context, appId)
        val winePrefix = File(container.rootDir, ".wine").absolutePath
        val user = "xuser"

        Timber.tag("Epic").d("[Cloud Saves] Using Wine prefix: $winePrefix")

        // Resolve path variables used by Epic Games (case-insensitive)
        val pathVars = mutableMapOf<String, String>(
            "{epicid}" to accountId,
            "{installdir}" to (game.installPath.ifEmpty { EpicConstants.getGameInstallPath(context, game.appName) }),
            "{appname}" to game.appName,
        )

        // Map to Wine prefix paths (like GOG does)
        // Check for both proper casing (AppData) and legacy lowercase (appdata)
        val usersPath = File(winePrefix, "drive_c/users/$user")
        val appDataDir = when {
            File(usersPath, "AppData").exists() -> "AppData"
            File(usersPath, "appdata").exists() -> "appdata"
            File(usersPath, "appData").exists() -> "appData"
            else -> "AppData" // Default to proper Windows casing
        }

        Timber.tag("Epic").d("[Cloud Saves] Using AppData directory name: $appDataDir")

        val appDataPath = File(winePrefix, "drive_c/users/$user/$appDataDir/Local").absolutePath
        val appDataRoamingPath = File(winePrefix, "drive_c/users/$user/$appDataDir/Roaming").absolutePath
        val documentsPath = File(winePrefix, "drive_c/users/$user/Documents").absolutePath
        val savedGamesPath = File(winePrefix, "drive_c/users/$user/Saved Games").absolutePath

        pathVars["{appdata}"] = appDataPath
        pathVars["{localappdata}"] = appDataPath       // Windows %LocalAppData% — same as AppData/Local
        pathVars["{roamingappdata}"] = appDataRoamingPath // Windows %AppData% (Roaming)
        pathVars["{userdir}"] = documentsPath
        pathVars["{usersavedgames}"] = savedGamesPath
        pathVars["{userprofile}"] = File(winePrefix, "drive_c/users/$user").absolutePath

        // Normalize path separators first
        var resolvedPath = cloudSaveFolder.replace("\\", "/")

        Timber.tag("Epic").d("[Cloud Saves] Before variable replacement: $resolvedPath")

        // Replace variables (case-insensitive)
        pathVars.forEach { (key, value) ->
            val before = resolvedPath
            resolvedPath = resolvedPath.replace(key, value, ignoreCase = true)
            if (before != resolvedPath) {
                Timber.tag("Epic").d("[Cloud Saves] Replaced $key with $value")
            }
        }

        Timber.tag("Epic").d("[Cloud Saves] After variable replacement: $resolvedPath")

        // Manually resolve ../ and ./ in the path (don't use canonicalPath as it can fail/change paths)
        val pathParts = resolvedPath.split("/").toMutableList()
        val normalizedParts = mutableListOf<String>()

        for (part in pathParts) {
            when {
                part == ".." && normalizedParts.isNotEmpty() && normalizedParts.last() != ".." -> {
                    // Go up one directory
                    normalizedParts.removeAt(normalizedParts.lastIndex)
                }

                part != "." && part.isNotEmpty() -> {
                    // Add non-empty, non-current-dir parts
                    normalizedParts.add(part)
                }
                // Skip "." and empty parts
            }
        }

        val finalPath = File(normalizedParts.joinToString("/"))

        // Check subdirectories for save files
        // Some games store saves in user-specific subdirectories (e.g., "0/", "1/", etc.)
        // even if there are other files in the parent directory
        Timber.tag("Epic").d("[Cloud Saves] Scanning path: ${finalPath.absolutePath}")
        Timber.tag("Epic").d("[Cloud Saves] Path exists: ${finalPath.exists()}")

        val actualPath = if (finalPath.exists()) {
            // Log all contents
            val allContents = finalPath.listFiles() ?: emptyArray()
            Timber.tag("Epic").d("[Cloud Saves] Total items in path: ${allContents.size}")

            allContents.forEach { item ->
                if (item.isDirectory) {
                    val filesInSubdir = item.listFiles()?.filter { it.isFile } ?: emptyList()
                    Timber.tag("Epic").d("[Cloud Saves]   DIR: ${item.name}/ (${filesInSubdir.size} files)")
                    filesInSubdir.take(5).forEach { file ->
                        Timber.tag("Epic").d("[Cloud Saves]     - ${file.name} (${file.length()} bytes)")
                    }
                } else {
                    Timber.tag("Epic").d("[Cloud Saves]   FILE: ${item.name} (${item.length()} bytes)")
                }
            }

            // Always check for subdirectories with files
            val subDirs = finalPath.listFiles { it -> it.isDirectory } ?: emptyArray()
            val dirWithFiles = subDirs.firstOrNull { subDir ->
                subDir.listFiles()?.any { it.isFile } == true
            }
            if (dirWithFiles != null) {
                Timber.tag("Epic").d("[Cloud Saves] Found saves in subdirectory: ${dirWithFiles.name}")
                dirWithFiles
            } else {
                finalPath
            }
        } else {
            Timber.tag("Epic").w("[Cloud Saves] Path does not exist!")
            finalPath
        }

        Timber.tag("Epic").d("[Cloud Saves] Path resolution:")
        Timber.tag("Epic").d("[Cloud Saves]   Original: $cloudSaveFolder")
        Timber.tag("Epic").d("[Cloud Saves]   Resolved: ${actualPath.absolutePath}")

        return actualPath
    }

    private fun getSyncTimestamp(context: Context, appId: Int): String? {
        val prefs = context.getSharedPreferences("epic_cloud_saves", Context.MODE_PRIVATE)
        return prefs.getString("sync_timestamp_$appId", null)
    }

    private fun setSyncTimestamp(context: Context, appId: Int, timestamp: String) {
        val prefs = context.getSharedPreferences("epic_cloud_saves", Context.MODE_PRIVATE)
        prefs.edit().putString("sync_timestamp_$appId", timestamp).apply()
    }

    /**
     * Decompress data if it's GZIP compressed, otherwise return as-is
     */
    private fun decompressIfNeeded(data: ByteArray): ByteArray {
        return try {
            // Check for GZIP magic bytes (0x1f 0x8b)
            if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                java.io.ByteArrayInputStream(data).use { inputStream ->
                    GZIPInputStream(inputStream).use { gzipStream ->
                        gzipStream.readBytes()
                    }
                }
            } else {
                data
            }
        } catch (e: Exception) {
            Timber.tag("Epic").w(e, "[Cloud Saves] Failed to decompress, using raw data")
            data
        }
    }

    /**
     * Decompress a binary chunk file — matches Legendary's Chunk.read() + Chunk.data property.
     *
     * Header layout (little-endian):
     *   magic(4) + headerVersion(4) + headerSize(4) + compressedSize(4)
     *   + guid(16) + hash(8) + storedAs(1)
     *   [v2+] + shaHash(20) + hashType(1)
     *   [v3+] + uncompressedSize(4)
     *   Total for v3 = 66 bytes
     *
     * The payload starts at offset headerSize (not computed — read from the header).
     */
    internal fun decompressChunk(chunkBytes: ByteArray): ByteArray {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(chunkBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            val magic = buffer.int
            if (magic != 0xB1FE3AA2.toInt()) {
                Timber.tag("Epic").w("[Cloud Saves] Invalid chunk magic: ${"%08X".format(magic)}, trying direct decompress")
                return decompressIfNeeded(chunkBytes)
            }

            val headerVersion = buffer.int
            val headerSize = buffer.int       // payload starts at this offset from file start
            val compressedSize = buffer.int

            // guid(16) + hash(8)
            buffer.position(buffer.position() + 24)

            val storedAs = buffer.get().toInt()
            val isCompressed = (storedAs and 0x1) != 0

            // v2: shaHash(20) + hashType(1) = 21 bytes
            if (headerVersion >= 2) buffer.position(buffer.position() + 21)
            // v3: uncompressedSize(4)
            if (headerVersion >= 3) buffer.position(buffer.position() + 4)

            // Seek to headerSize regardless, in case of unknown future header fields
            buffer.position(headerSize)

            val data = ByteArray(chunkBytes.size - headerSize)
            buffer.get(data)

            if (isCompressed) {
                try {
                    java.io.ByteArrayInputStream(data).use { inputStream ->
                        java.util.zip.InflaterInputStream(inputStream).use { it.readBytes() }
                    }
                } catch (e: Exception) {
                    Timber.tag("Epic").w(e, "[Cloud Saves] Failed to inflate chunk, returning raw payload")
                    data
                }
            } else {
                data
            }
        } catch (e: Exception) {
            Timber.tag("Epic").e(e, "[Cloud Saves] Failed to parse chunk header, trying direct decompress")
            decompressIfNeeded(chunkBytes)
        }
    }

}
