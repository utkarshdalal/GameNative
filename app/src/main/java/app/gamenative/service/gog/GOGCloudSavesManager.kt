package app.gamenative.service.gog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.concurrent.TimeUnit


class GOGCloudSavesManager(
    private val context: Context
) {

    private val httpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()

    companion object {
        private const val CLOUD_STORAGE_BASE_URL = "https://cloudstorage.gog.com"
        private const val USER_AGENT = "GOGGalaxyCommunicationService/2.0.13.27 (Windows_32bit) dont_sync_marker/true installation_source/gog"
        private const val DELETION_MD5 = "aadd86936a80ee8a369579c3926f1b3c"
    }

    enum class SyncAction {
        UPLOAD,
        DOWNLOAD,
        CONFLICT,
        NONE
    }

    /**
     * Represents a local save file
     */
    data class SyncFile(
        val relativePath: String,
        val absolutePath: String,
        var md5Hash: String? = null,
        var updateTime: String? = null,
        var updateTimestamp: Long? = null
    ) {
        /**
         * Calculate MD5 hash and metadata for this file
         */
        suspend fun calculateMetadata() = withContext(Dispatchers.IO) {
            try {
                val file = File(absolutePath)
                if (!file.exists() || !file.isFile) {
                    Timber.w("File does not exist: $absolutePath")
                    return@withContext
                }

                // Get file modification timestamp
                val timestamp = file.lastModified()
                val instant = Instant.ofEpochMilli(timestamp)
                updateTime = DateTimeFormatter.ISO_INSTANT.format(instant)
                updateTimestamp = timestamp / 1000 // Convert to seconds

                // Calculate MD5 of gzipped content (matching Python implementation)
                FileInputStream(file).use { fis ->
                    val digest = MessageDigest.getInstance("MD5")
                    val buffer = java.io.ByteArrayOutputStream()

                    GZIPOutputStream(buffer).use { gzipOut ->
                        val fileBuffer = ByteArray(8192)
                        var bytesRead: Int
                        while (fis.read(fileBuffer).also { bytesRead = it } != -1) {
                            gzipOut.write(fileBuffer, 0, bytesRead)
                        }
                    }

                    md5Hash = digest.digest(buffer.toByteArray())
                        .joinToString("") { "%02x".format(it) }
                }

                Timber.d("Calculated metadata for $relativePath: md5=$md5Hash, timestamp=$updateTimestamp")
            } catch (e: Exception) {
                Timber.e(e, "Failed to calculate metadata for $absolutePath")
            }
        }
    }

    /**
     * Represents a cloud save file
     */
    data class CloudFile(
        val relativePath: String,
        val md5Hash: String,
        val updateTime: String?,
        val updateTimestamp: Long?
    ) {
        val isDeleted: Boolean
            get() = md5Hash == DELETION_MD5
    }

    /**
     * Classifies sync actions based on file differences
     */
    data class SyncClassifier(
        val updatedLocal: List<SyncFile> = emptyList(),
        val updatedCloud: List<CloudFile> = emptyList(),
        val notExistingLocally: List<CloudFile> = emptyList(),
        val notExistingRemotely: List<SyncFile> = emptyList()
    ) {
        fun determineAction(): SyncAction {
            return when {
                updatedLocal.isEmpty() && updatedCloud.isNotEmpty() -> SyncAction.DOWNLOAD
                updatedLocal.isNotEmpty() && updatedCloud.isEmpty() -> SyncAction.UPLOAD
                updatedLocal.isEmpty() && updatedCloud.isEmpty() -> SyncAction.NONE
                else -> SyncAction.CONFLICT
            }
        }
    }

    /**
     * Synchronize save files for a game - We grab the directories for ALL games, then download the exact ones we want.
     * @param localPath Path to local save directory
     * @param dirname Cloud save directory name
     * @param clientId Game's client ID (from remote config)
     * @param clientSecret Game's client secret (from build metadata)
     * @param lastSyncTimestamp Timestamp of last sync (0 for initial sync)
     * @param preferredAction User's preferred action (download, upload, or none)
     * @return New sync timestamp, or 0 on failure
     */
    suspend fun syncSaves(
        localPath: String,
        dirname: String,
        clientId: String,
        clientSecret: String,
        lastSyncTimestamp: Long = 0,
        preferredAction: String = "none"
    ): Long = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG-CloudSaves").i("Starting sync for path: $localPath")
            Timber.tag("GOG-CloudSaves").i("Cloud dirname: $dirname")
            Timber.tag("GOG-CloudSaves").i("Cloud client ID: $clientId")
            Timber.tag("GOG-CloudSaves").i("Last sync timestamp: $lastSyncTimestamp")
            Timber.tag("GOG-CloudSaves").i("Preferred action: $preferredAction")

            // Ensure directory exists
            val syncDir = File(localPath)
            if (!syncDir.exists()) {
                Timber.tag("GOG-CloudSaves").i("Creating sync directory: $localPath")
                syncDir.mkdirs()
            }

            // Get local files
            val localFiles = scanLocalFiles(syncDir)
            Timber.tag("GOG-CloudSaves").i("Found ${localFiles.size} local file(s)")

            // Get game-specific authentication credentials
            // This exchanges the Galaxy refresh token for a game-specific access token
            val credentials = GOGAuthManager.getGameCredentials(context, clientId, clientSecret).getOrNull() ?: run {
                Timber.tag("GOG-CloudSaves").e("Failed to get game-specific credentials")
                return@withContext 0L
            }
            Timber.tag("GOG-CloudSaves").d("Using game-specific credentials for userId: ${credentials.userId}, clientId: $clientId")

            // Get cloud files using game-specific clientId in URL path
            Timber.tag("GOG").d("[Cloud Saves] Fetching cloud file list for dirname: $dirname")
            val cloudFiles = getCloudFiles(credentials.userId, clientId, dirname, credentials.accessToken)
            Timber.tag("GOG").d("[Cloud Saves] Retrieved ${cloudFiles.size} total cloud files")
            val downloadableCloud = cloudFiles.filter { !it.isDeleted }
            Timber.tag("GOG").i("[Cloud Saves] Found ${downloadableCloud.size} downloadable cloud file(s) (excluding deleted)")
            if (downloadableCloud.isNotEmpty()) {
                downloadableCloud.forEach { file ->
                    Timber.tag("GOG").d("[Cloud Saves]   - Cloud file: ${file.relativePath} (md5: ${file.md5Hash}, modified: ${file.updateTime})")
                }
            }

            // Handle simple cases first
            when {
                localFiles.isNotEmpty() && cloudFiles.isEmpty() -> {
                    Timber.tag("GOG-CloudSaves").i("No files in cloud, uploading ${localFiles.size} file(s)")
                    localFiles.forEach { file ->
                        uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                    }
                    return@withContext currentTimestamp()
                }

                localFiles.isEmpty() && downloadableCloud.isNotEmpty() -> {
                    Timber.tag("GOG-CloudSaves").i("No files locally, downloading ${downloadableCloud.size} file(s)")
                    downloadableCloud.forEach { file ->
                        downloadFile(credentials.userId, clientId, dirname, file, syncDir, credentials.accessToken)
                    }
                    return@withContext currentTimestamp()
                }

                localFiles.isEmpty() && cloudFiles.isEmpty() -> {
                    Timber.tag("GOG-CloudSaves").i("No files locally or in cloud, nothing to sync")
                    return@withContext currentTimestamp()
                }
            }

            // Handle preferred action
            if (preferredAction == "download" && downloadableCloud.isNotEmpty()) {
                Timber.tag("GOG-CloudSaves").i("Forcing download of ${downloadableCloud.size} file(s) (user requested)")
                downloadableCloud.forEach { file ->
                    downloadFile(credentials.userId, clientId, dirname, file, syncDir, credentials.accessToken)
                }
                return@withContext currentTimestamp()
            }

            if (preferredAction == "upload" && localFiles.isNotEmpty()) {
                Timber.tag("GOG-CloudSaves").i("Forcing upload of ${localFiles.size} file(s) (user requested)")
                localFiles.forEach { file ->
                    uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                }
                return@withContext currentTimestamp()
            }

            // Complex sync scenario - use classifier
            val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTimestamp)
            when (classifier.determineAction()) {
                SyncAction.DOWNLOAD -> {
                    Timber.tag("GOG-CloudSaves").i("Downloading ${classifier.updatedCloud.size} updated cloud file(s)")
                    classifier.updatedCloud.forEach { file ->
                        downloadFile(credentials.userId, clientId, dirname, file, syncDir, credentials.accessToken)
                    }
                    classifier.notExistingLocally.forEach { file ->
                        if (!file.isDeleted) {
                            downloadFile(credentials.userId, clientId, dirname, file, syncDir, credentials.accessToken)
                        }
                    }
                }

                SyncAction.UPLOAD -> {
                    Timber.tag("GOG-CloudSaves").i("Uploading ${classifier.updatedLocal.size} updated local file(s)")
                    classifier.updatedLocal.forEach { file ->
                        uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                    }
                    classifier.notExistingRemotely.forEach { file ->
                        uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                    }
                }

                SyncAction.CONFLICT -> {
                    Timber.tag("GOG-CloudSaves").w("Sync conflict detected - comparing timestamps")

                    // Compare timestamps for matching files
                    val localMap = classifier.updatedLocal.associateBy { it.relativePath }
                    val cloudMap = classifier.updatedCloud.associateBy { it.relativePath }

                    val toUpload = mutableListOf<SyncFile>()
                    val toDownload = mutableListOf<CloudFile>()

                    // Check files that exist in both and were both updated
                    val commonPaths = localMap.keys.intersect(cloudMap.keys)
                    commonPaths.forEach { path ->
                        val localFile = localMap[path]!!
                        val cloudFile = cloudMap[path]!!

                        val localTime = localFile.updateTimestamp ?: 0L
                        val cloudTime = cloudFile.updateTimestamp ?: 0L

                        when {
                            localTime > cloudTime -> {
                                Timber.tag("GOG-CloudSaves").i("Local file is newer: $path (local: $localTime > cloud: $cloudTime)")
                                toUpload.add(localFile)
                            }
                            cloudTime > localTime -> {
                                Timber.tag("GOG-CloudSaves").i("Cloud file is newer: $path (cloud: $cloudTime > local: $localTime)")
                                toDownload.add(cloudFile)
                            }
                            else -> {
                                Timber.tag("GOG-CloudSaves").w("Files have same timestamp, skipping: $path")
                            }
                        }
                    }

                    // Upload files that only exist locally or are newer locally
                    (localMap.keys - commonPaths).forEach { path ->
                        toUpload.add(localMap[path]!!)
                    }

                    // Download files that only exist in cloud or are newer in cloud
                    (cloudMap.keys - commonPaths).forEach { path ->
                        toDownload.add(cloudMap[path]!!)
                    }

                    // Handle files not existing in either location
                    toUpload.addAll(classifier.notExistingRemotely)
                    toDownload.addAll(classifier.notExistingLocally.filter { !it.isDeleted })

                    // Execute uploads
                    if (toUpload.isNotEmpty()) {
                        Timber.tag("GOG-CloudSaves").i("Uploading ${toUpload.size} file(s) based on timestamp comparison")
                        toUpload.forEach { file ->
                            uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                        }
                    }

                    // Execute downloads
                    if (toDownload.isNotEmpty()) {
                        Timber.tag("GOG-CloudSaves").i("Downloading ${toDownload.size} file(s) based on timestamp comparison")
                        toDownload.forEach { file ->
                            downloadFile(credentials.userId, clientId, dirname, file, syncDir, credentials.accessToken)
                        }
                    }
                }
                SyncAction.NONE -> {
                    Timber.tag("GOG-CloudSaves").i("No sync needed - files are up to date")
                }
            }

            Timber.tag("GOG-CloudSaves").i("Sync completed successfully")
            return@withContext currentTimestamp()

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Sync failed: ${e.message}")
            return@withContext 0L
        }
    }

    /**
     * Scan local directory for save files
     */
    private suspend fun scanLocalFiles(directory: File): List<SyncFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<SyncFile>()

        fun scanRecursive(dir: File, basePath: String) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val relativePath = file.absolutePath.removePrefix(basePath)
                        .removePrefix("/")
                        .replace("\\", "/")
                    files.add(SyncFile(relativePath, file.absolutePath))
                } else if (file.isDirectory) {
                    scanRecursive(file, basePath)
                }
            }
        }

        scanRecursive(directory, directory.absolutePath)

        // Calculate metadata for all files
        files.forEach { it.calculateMetadata() }

        files
    }

    /**
     * Get cloud files list from GOG API
     */
    private suspend fun getCloudFiles(
        userId: String,
        clientId: String,
        dirname: String,
        authToken: String
    ): List<CloudFile> = withContext(Dispatchers.IO) {
        try {
            // List all files (don't include dirname in URL - it's used as a prefix filter)
            val url = "$CLOUD_STORAGE_BASE_URL/v1/$userId/$clientId"
            Timber.tag("GOG").d("[Cloud Saves] API Request: GET $url (dirname filter: $dirname)")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("X-Object-Meta-User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    Timber.tag("GOG").e("[Cloud Saves] Failed to fetch cloud files: HTTP ${response.code}")
                    Timber.tag("GOG").e("[Cloud Saves] Response body: $errorBody")
                    return@withContext emptyList()
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isEmpty()) {
                    Timber.tag("GOG").d("[Cloud Saves] Empty response body from cloud storage API")
                    return@withContext emptyList()
                }

                val items = try {
                    JSONArray(responseBody)
                } catch (e: Exception) {
                    Timber.tag("GOG").e(e, "[Cloud Saves] Failed to parse JSON array response")
                    Timber.tag("GOG").e("[Cloud Saves] Response was: $responseBody")
                    return@withContext emptyList()
                }

                Timber.tag("GOG").d("[Cloud Saves] Found ${items.length()} total items in cloud storage")

                val files = mutableListOf<CloudFile>()
                for (i in 0 until items.length()) {
                    val fileObj = items.getJSONObject(i)
                    val name = fileObj.optString("name", "")
                    val hash = fileObj.optString("hash", "")
                    val lastModified = fileObj.optString("last_modified")

                    Timber.tag("GOG").d("[Cloud Saves]   Examining item $i: name='$name', dirname='$dirname'")

                    // Filter files that belong to this save location (name starts with dirname/)
                    if (name.isNotEmpty() && hash.isNotEmpty() && name.startsWith("$dirname/")) {
                        val timestamp = try {
                            Instant.parse(lastModified).epochSecond
                        } catch (e: Exception) {
                            null
                        }

                        // Remove the dirname prefix to get relative path
                        val relativePath = name.removePrefix("$dirname/")
                        files.add(CloudFile(relativePath, hash, lastModified, timestamp))
                        Timber.tag("GOG").d("[Cloud Saves]     ✓ Matched: relativePath='$relativePath'")
                    } else {
                        Timber.tag("GOG").d("[Cloud Saves]     ✗ Skipped (doesn't match dirname or missing data)")
                    }
                }

                Timber.tag("GOG").i("[Cloud Saves] Retrieved ${files.size} cloud files for dirname '$dirname'")
                files
            }

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to get cloud files")
            emptyList()
        }
    }

    /**
     * Upload file to GOG cloud storage
     */
    private suspend fun uploadFile(
        userId: String,
        clientId: String,
        dirname: String,
        file: SyncFile,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        try {
            val localFile = File(file.absolutePath)
            val fileSize = localFile.length()

            Timber.tag("GOG-CloudSaves").i("Uploading: ${file.relativePath} (${fileSize} bytes)")

            val url = "$CLOUD_STORAGE_BASE_URL/v1/$userId/$clientId/$dirname/${file.relativePath}"

            val requestBody = localFile.readBytes().toRequestBody("application/octet-stream".toMediaType())

            val requestBuilder = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", "Bearer $authToken")
                .header("User-Agent", USER_AGENT)
                .header("X-Object-Meta-User-Agent", USER_AGENT)
                .header("Content-Type", "application/octet-stream")

            // Add last modified timestamp header if available
            file.updateTime?.let { timestamp ->
                requestBuilder.header("X-Object-Meta-LocalLastModified", timestamp)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use {
                if (response.isSuccessful) {
                    Timber.tag("GOG-CloudSaves").i("Successfully uploaded: ${file.relativePath}")
                } else {
                    val errorBody = response.body?.string() ?: "No response body"
                    Timber.tag("GOG-CloudSaves").e("Failed to upload ${file.relativePath}: HTTP ${response.code}")
                    Timber.tag("GOG-CloudSaves").e("Upload error body: $errorBody")
                }
            }

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to upload ${file.relativePath}")
        }
    }

    /**
     * Download file from GOG cloud storage
     */
    private suspend fun downloadFile(
        userId: String,
        clientId: String,
        dirname: String,
        file: CloudFile,
        syncDir: File,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG-CloudSaves").i("Downloading: ${file.relativePath}")

            val url = "$CLOUD_STORAGE_BASE_URL/v1/$userId/$clientId/$dirname/${file.relativePath}"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $authToken")
                .header("User-Agent", USER_AGENT)
                .header("X-Object-Meta-User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    Timber.tag("GOG-CloudSaves").e("Failed to download ${file.relativePath}: HTTP ${response.code}")
                    Timber.tag("GOG-CloudSaves").e("Download error body: $errorBody")
                    return@withContext
                }

                val bytes = response.body?.bytes() ?: return@withContext
                Timber.tag("GOG-CloudSaves").d("Downloaded ${bytes.size} bytes for ${file.relativePath}")

                // Save to local file
                val localFile = File(syncDir, file.relativePath)
                localFile.parentFile?.mkdirs()

                FileOutputStream(localFile).use { fos ->
                    fos.write(bytes)
                }

                // Preserve timestamp if available
                file.updateTimestamp?.let { timestamp ->
                    localFile.setLastModified(timestamp * 1000)
                }

                Timber.tag("GOG-CloudSaves").i("Successfully downloaded: ${file.relativePath}")
            }

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to download ${file.relativePath}")
        }
    }

    /**
     * Classify files for sync decision
     */
    private fun classifyFiles(
        localFiles: List<SyncFile>,
        cloudFiles: List<CloudFile>,
        timestamp: Long
    ): SyncClassifier {
        val updatedLocal = mutableListOf<SyncFile>()
        val updatedCloud = mutableListOf<CloudFile>()
        val notExistingLocally = mutableListOf<CloudFile>()
        val notExistingRemotely = mutableListOf<SyncFile>()

        val localPaths = localFiles.map { it.relativePath }.toSet()
        val cloudPaths = cloudFiles.map { it.relativePath }.toSet()

        // Check local files
        localFiles.forEach { file ->
            if (file.relativePath !in cloudPaths) {
                notExistingRemotely.add(file)
            }
            val fileTimestamp = file.updateTimestamp
            if (fileTimestamp != null && fileTimestamp > timestamp) {
                updatedLocal.add(file)
            }
        }

        // Check cloud files
        cloudFiles.forEach { file ->
            if (file.isDeleted) return@forEach

            if (file.relativePath !in localPaths) {
                notExistingLocally.add(file)
            }
            val fileTimestamp = file.updateTimestamp
            if (fileTimestamp != null && fileTimestamp > timestamp) {
                updatedCloud.add(file)
            }
        }

        return SyncClassifier(updatedLocal, updatedCloud, notExistingLocally, notExistingRemotely)
    }

    /**
     * Get current timestamp in seconds
     */
    private fun currentTimestamp(): Long {
        return System.currentTimeMillis() / 1000
    }
}
