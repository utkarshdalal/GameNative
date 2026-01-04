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
            Timber.tag("EPIC").i("[Cloud Saves] Starting sync for $appId (action: $preferredAction)")

            // Get game info to retrieve appName
            val game = EpicService.getEpicGameOf(appId)
            if (game == null) {
                Timber.tag("EPIC").e("[Cloud Saves] Game not found: $appId")
                return@withContext false
            }

            val appName = game.appName

            // Check if game supports cloud saves
            if (!game.cloudSaveEnabled) {
                Timber.tag("EPIC").w("[Cloud Saves] Game does not support cloud saves: ${game.title}")
                return@withContext false
            }

            // 1. Validate and refresh access token if needed (global credentials)
            val credentials = EpicAuthManager.getStoredCredentials(context)
            if (credentials.isFailure) {
                Timber.tag("EPIC").e("[Cloud Saves] Not logged in to Epic: ${credentials.exceptionOrNull()?.message}")
                return@withContext false
            }

            val creds = credentials.getOrNull()!!
            Timber.tag("EPIC").d("[Cloud Saves] Using account: ${creds.accountId} (${creds.displayName})")

            // 2. Determine sync action
            val action = determineSyncAction(context, appId, creds.accountId, game, preferredAction)

            Timber.tag("EPIC").i("[Cloud Saves] Sync action determined: $action")

            // 3. Execute sync action
            val result = when (action) {
                SyncAction.DOWNLOAD -> downloadSaves(context, appId, creds.accountId)
                SyncAction.UPLOAD -> {
                    Timber.tag("EPIC").w("[Cloud Saves] Upload not yet implemented")
                    false
                }
                SyncAction.CONFLICT -> {
                    Timber.tag("EPIC").w("[Cloud Saves] Conflict detected - preferring download for now") // TODO: We should have proper conflict resolution.
                    downloadSaves(context, appId, creds.accountId)
                }
                SyncAction.NONE -> {
                    Timber.tag("EPIC").i("[Cloud Saves] No sync needed")
                    true
                }
            }

            if (result) {
                Timber.tag("EPIC").i("[Cloud Saves] Sync completed successfully")
            }

            result
        } catch (e: Exception) {
            Timber.tag("EPIC").e(e, "[Cloud Saves] Sync failed")
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
                Timber.tag("EPIC").w("[Cloud Saves] Failed to list cloud saves, will try upload if local files exist")
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
                Timber.tag("EPIC").w("[Cloud Saves] No manifest in cloud, will upload")
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

            Timber.tag("EPIC").d("[Cloud Saves] Cloud timestamp: $cloudTimestamp, Last sync: $lastSync")
            Timber.tag("EPIC").d("[Cloud Saves] Local newest file: $localNewestTimestamp")

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
            Timber.tag("EPIC").e(e, "[Cloud Saves] Error determining sync action")
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

            Timber.tag("EPIC").d("[Cloud Saves] Listing saves for $appName (account: $accountId)")

            val request = Request.Builder()
                .url("$/api/v1/access/egstore/savesync/$accountId/$appName/")
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
            Timber.tag("EPIC").e(e, "Failed to list cloud saves")
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
            Timber.tag("EPIC").e(e, "Failed to download file")
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
            Timber.tag("EPIC").i("[Cloud Saves] Starting download for $appId")

            // 1. Get game info
            val game = EpicService.getEpicGameOf(appId)
            if (game?.cloudSaveEnabled != true) {
                Timber.tag("EPIC").w("[Cloud Saves] Game does not support cloud saves")
                return@withContext false
            }

            // 2. List cloud saves
            val cloudSavesResult = listCloudSaves(game.appName, context)
            if (cloudSavesResult.isFailure) {
                Timber.tag("EPIC").e("[Cloud Saves] Failed to list saves: ${cloudSavesResult.exceptionOrNull()?.message}")
                return@withContext false
            }

            val cloudSaves = cloudSavesResult.getOrNull()!!
            if (cloudSaves.files.isEmpty()) {
                Timber.tag("EPIC").i("[Cloud Saves] No cloud saves found")
                return@withContext false
            }

            // 3. Find latest manifest
            val (manifestPath, manifestInfo) = findLatestManifest(cloudSaves.files) ?: run {
                Timber.tag("EPIC").w("[Cloud Saves] No manifest found in cloud saves")
                return@withContext false
            }

            Timber.tag("EPIC").i("[Cloud Saves] Found manifest: $manifestPath (${manifestInfo.lastModified})")

            // 4. Check if we need to download
            val lastSync = getSyncTimestamp(context, appId)
            if (lastSync != null && lastSync >= manifestInfo.lastModified) {
                Timber.tag("EPIC").i("[Cloud Saves] Local saves are up to date")
                return@withContext true
            }

            // 5. Download manifest
            val manifestData = downloadFile(manifestInfo.readLink ?: return@withContext false)
            if (manifestData.isFailure) {
                Timber.tag("EPIC").e("[Cloud Saves] Failed to download manifest")
                return@withContext false
            }

            // 6. Parse manifest
            val manifest = try {
                EpicManifest.readAll(manifestData.getOrNull()!!)
            } catch (e: Exception) {
                Timber.tag("EPIC").e(e, "[Cloud Saves] Failed to parse manifest")
                return@withContext false
            }

            Timber.tag("EPIC").i("[Cloud Saves] Manifest contains ${manifest.fileManifestList?.elements?.size ?: 0} files")

            // 7. Download and extract chunks
            val saveDir = resolveSaveDirectory(context, game, accountId) ?: run {
                Timber.tag("EPIC").e("[Cloud Saves] Failed to resolve save directory")
                return@withContext false
            }

            saveDir.mkdirs()

            var downloadedFiles = 0
            manifest.fileManifestList?.elements?.forEach { fileManifest ->
                try {
                    val outputFile = File(saveDir, fileManifest.filename)
                    outputFile.parentFile?.mkdirs()

                    // Download chunks for this file
                    val fileData = mutableListOf<ByteArray>()
                    fileManifest.chunkParts.forEach { chunkPart ->
                        val chunkInfo = manifest.chunkDataList?.elements?.find { it.guid.contentEquals(chunkPart.guid) }
                        if (chunkInfo == null) {
                            Timber.tag("EPIC").w("[Cloud Saves] Chunk ${chunkPart.guidStr} not found in manifest")
                            return@forEach
                        }

                        // Find chunk file in cloud saves
                        val chunkPath = cloudSaves.files.keys.find { it.contains(chunkPart.guidStr) }
                        val chunkFile = cloudSaves.files[chunkPath]
                        if (chunkFile?.readLink == null) {
                            Timber.tag("EPIC").w("[Cloud Saves] Chunk file not found: ${chunkPart.guidStr}")
                            return@forEach
                        }

                        // Download chunk
                        val chunkResult = downloadFile(chunkFile.readLink)
                        if (chunkResult.isSuccess) {
                            fileData.add(chunkResult.getOrNull()!!)
                        }
                    }

                    // Write combined file data
                    if (fileData.isNotEmpty()) {
                        outputFile.outputStream().use { output ->
                            fileData.forEach { output.write(it) }
                        }
                        downloadedFiles++
                        Timber.tag("EPIC").d("[Cloud Saves] Downloaded: ${fileManifest.filename}")
                    }
                } catch (e: Exception) {
                    Timber.tag("EPIC").e(e, "[Cloud Saves] Failed to download ${fileManifest.filename}")
                }
            }

            // 8. Update sync timestamp
            setSyncTimestamp(context, appId, manifestInfo.lastModified)

            Timber.tag("EPIC").i("[Cloud Saves] Download complete: $downloadedFiles files")
            true
        } catch (e: Exception) {
            Timber.tag("EPIC").e(e, "[Cloud Saves] Download failed")
            false
        }
    }

    // Resolve save directory path
    private fun resolveSaveDirectory(context: Context, game: EpicGame, accountId: String): File? {
        val cloudSaveFolder = game.saveFolder.ifEmpty { return null }

        // TODO: Implement full path variable resolution
        // For now, use a simple implementation
        val resolvedPath = cloudSaveFolder
            .replace("{EpicID}", accountId)
            .replace("{AppName}", game.appName)

        // Return path relative to app files directory
        return File(context.filesDir, "epic_saves/$resolvedPath")
    }

        private fun getSyncTimestamp(context: Context, appId: String): String? {
            val prefs = context.getSharedPreferences("epic_cloud_saves", Context.MODE_PRIVATE)
            return prefs.getString("sync_timestamp_$appId", null)
        }

        private fun setSyncTimestamp(context: Context, appId: String, timestamp: String) {
            val prefs = context.getSharedPreferences("epic_cloud_saves", Context.MODE_PRIVATE)
            prefs.edit().putString("sync_timestamp_$appId", timestamp).apply()
        }
}
