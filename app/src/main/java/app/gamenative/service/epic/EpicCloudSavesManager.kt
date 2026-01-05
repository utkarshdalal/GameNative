package app.gamenative.service.epic

import android.content.Context
import app.gamenative.service.epic.manifest.EpicManifest
import app.gamenative.utils.Net
import app.gamenative.data.EpicGame
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.collections.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * The Cloud Saves for Epic is much simpler than GOG:
 * Authenticate
 * use the account_id and access_token from the JSON credentials file
 * use the appName in the function
 * Go to: https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com/api/v1/access/egstore/savesync/{{ _.accountId }}/{{ _.appName }}
 * If downloading and size is 0, just log out there are no cloud saves.
 * use the readLink for the Downloading if size is > 0
 * use the writeLink if we want to upload files.
 * Writing will be the awkward part, no idea how we do binary stuff.
*/

/**
 * Manages Epic Cloud Saves - downloading and uploading save files
 *
 * Epic uses a manifest-based chunked format (similar to game downloads):
 * - Manifest files contain metadata and chunk references
 * - Save files are split into compressed chunks
 * - Chunks are deduplicated via GUID/hash
 */
object EpicCloudSavesManager {

    // Data classes for API responses
    data class CloudSaveFiles(
        val files: Map<String, CloudFileInfo>,
    )

    private val baseCloudSyncUrl = "https://datastorage-public-service-liveegs.live.use1a.on.epicgames.com"

    private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

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
        NONE
    }

    /**
     * Sync cloud saves for a game (bidirectional sync with conflict detection)
     *
     * @param preferredAction "download", "upload", or "auto" (default)
     */
    suspend fun syncCloudSaves(
        context: Context,
        appId: String,
        preferredAction: String = "auto"
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Timber.tag("Epic").i("[Cloud Saves] Starting sync for $appId (action: $preferredAction)")

            // Get game info to retrieve appName
            val game = EpicService.getEpicGameOf(appId)
            if (game == null) {
                Timber.tag("Epic").e("[Cloud Saves] Game not found: $appId")
                return@withContext false
            }

            val appName = game.appName

            // Check if game supports cloud saves
            if (!game.cloudSaveEnabled) {
                Timber.tag("Epic").w("[Cloud Saves] Game does not support cloud saves: ${game.title}")
                return@withContext false
            }

            // 1. Validate and refresh access token if needed (global credentials)
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                Timber.tag("Epic").e("[Cloud Saves] Not logged in to Epic: ${credentials.exceptionOrNull()?.message}")
                return@withContext false
            }

            val creds = credentials.getOrNull()!!
            Timber.tag("Epic").d("[Cloud Saves] Using account: ${creds.accountId} (${creds.displayName})")

            // 2. Determine sync action
            val action = determineSyncAction(context, appId, creds.accountId, game, preferredAction)

            Timber.tag("Epic").i("[Cloud Saves] Sync action determined: $action")

            // 3. Execute sync action
            val result = when (action) {
                SyncAction.DOWNLOAD -> downloadSaves(context, appId, creds.accountId)
                SyncAction.UPLOAD -> {
                    Timber.tag("Epic").w("[Cloud Saves] Upload not yet implemented")
                    false
                }
                SyncAction.CONFLICT -> {
                    Timber.tag("Epic").w("[Cloud Saves] Conflict detected - preferring download for now") // TODO: We should have proper conflict resolution.
                    downloadSaves(context, appId, creds.accountId)
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
        }
    }

    /**
     * Determine what sync action to take based on local and cloud state
     */
    private suspend fun determineSyncAction(
        context: Context,
        appId: String,
        accountId: String,
        game: app.gamenative.data.EpicGame,
        preferredAction: String
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

            val lastSync = getSyncTimestamp(context, appId)
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

    // Download saves flow
    private suspend fun downloadSaves(context: Context, appId: String, accountId: String): Boolean = withContext(Dispatchers.IO) {
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
            val manifest = try {
                EpicManifest.readAll(manifestBytes)
            } catch (e: Exception) {
                Timber.tag("Epic").e(e, "[Cloud Saves] Failed to parse manifest")
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
                                    (chunkPart.offset + chunkPart.size).toInt()
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

    // Resolve save directory path
    private fun resolveSaveDirectory(context: Context, game: EpicGame, accountId: String): File? {
        val cloudSaveFolder = game.saveFolder.ifEmpty { return null }

        // Resolve path variables like Legendary does
        // Reference: legendary/core.py get_save_path()
        val pathVars = mutableMapOf<String, String>(
            "{epicid}" to accountId,
            "{installdir}" to (game.installPath.ifEmpty { "/data/data/${context.packageName}/files/games/${game.appName}" }),
            "{appname}" to game.appName,
        )

        // On Android, we use app-specific storage paths
        // These map to Wine-like paths for Windows games
        val appDataPath = File(context.filesDir, "appdata/local").absolutePath
        val appDataRoamingPath = File(context.filesDir, "appdata/roaming").absolutePath
        val localLowPath = File(context.filesDir, "appdata/locallow").absolutePath
        val documentsPath = File(context.filesDir, "documents").absolutePath
        val savedGamesPath = File(context.filesDir, "saved_games").absolutePath

        pathVars["{appdata}"] = appDataPath
        pathVars["{userdir}"] = documentsPath
        pathVars["{usersavedgames}"] = savedGamesPath
        pathVars["{userprofile}"] = context.filesDir.absolutePath

        // Handle paths with ../ for going up directories (like "../LocalLow")
        var resolvedPath = cloudSaveFolder
            .replace("\\", "/") // normalize path separators

        // Replace variables (case-insensitive)
        pathVars.forEach { (key, value) ->
            resolvedPath = resolvedPath.replace(key, value, ignoreCase = true)
        }

        // Handle special cases like LocalLow, Roaming that appear in paths
        resolvedPath = resolvedPath
            .replace("../LocalLow/", localLowPath + "/", ignoreCase = true)
            .replace("../Roaming/", appDataRoamingPath + "/", ignoreCase = true)
            .replace("/LocalLow/", localLowPath + "/", ignoreCase = true)
            .replace("/Roaming/", appDataRoamingPath + "/", ignoreCase = true)

        // Resolve the path
        val finalPath = if (resolvedPath.startsWith("/")) {
            File(resolvedPath)
        } else {
            File(context.filesDir, "epic_saves/$resolvedPath")
        }

        Timber.tag("Epic").d("[Cloud Saves] Path resolution:")
        Timber.tag("Epic").d("[Cloud Saves]   Original: $cloudSaveFolder")
        Timber.tag("Epic").d("[Cloud Saves]   Resolved: ${finalPath.absolutePath}")

        return finalPath
    }

        private fun getSyncTimestamp(context: Context, appId: String): String? {
            val prefs = context.getSharedPreferences("epic_cloud_saves", Context.MODE_PRIVATE)
            return prefs.getString("sync_timestamp_$appId", null)
        }

        private fun setSyncTimestamp(context: Context, appId: String, timestamp: String) {
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
     * Decompress chunk data similar to Legendary's Chunk.read_buffer
     * Chunk format: magic (4 bytes) + header + compressed data
     */
    private fun decompressChunk(chunkBytes: ByteArray): ByteArray {
        return try {
            val buffer = java.nio.ByteBuffer.wrap(chunkBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

            // Read chunk header
            val magic = buffer.int
            if (magic != 0xB1FE3AA2.toInt()) {
                Timber.tag("Epic").w("[Cloud Saves] Invalid chunk magic: ${"%08X".format(magic)}, trying direct decompress")
                return decompressIfNeeded(chunkBytes)
            }

            val headerVersion = buffer.int
            val headerSize = buffer.int
            val compressedSize = buffer.int

            // Skip GUID (16 bytes) and hash (8 bytes)
            buffer.position(buffer.position() + 24)

            // Read stored_as flag to determine if compressed
            val storedAs = buffer.get().toInt()
            val isCompressed = (storedAs and 0x1) != 0

            // Skip hash type and SHA hash (21 bytes total for the flag + hash)
            buffer.position(buffer.position() + 20)

            // Get remaining data
            val dataStart = buffer.position()
            val dataSize = chunkBytes.size - dataStart
            val data = ByteArray(dataSize)
            buffer.get(data)

            // Decompress if needed
            if (isCompressed) {
                java.io.ByteArrayInputStream(data).use { inputStream ->
                    java.util.zip.InflaterInputStream(inputStream).use { inflater ->
                        inflater.readBytes()
                    }
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
