package app.gamenative.service.gog

import android.content.Context
import app.gamenative.utils.FileUtils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.json.JSONArray
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
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
        // Bumped 2026-05-09 from 2.0.13.27 → 2.0.18.181 to match the version Galaxy
        // desktop itself currently uses (observed on container metadata header
        // `x-container-meta-user-agent`). verified on-device since the bump.
        // If Galaxy ever starts rejecting writes from this UA, fall back to Heroic's 2.0.13.27.
        private const val USER_AGENT = "GOGGalaxyCommunicationService/2.0.18.181 (Windows_32bit) dont_sync_marker/true installation_source/gog"
        private const val DELETION_MD5 = "aadd86936a80ee8a369579c3926f1b3c"

        // bidirectional sync exclusions for HTML5 NW.js titles. delegates to the shared
        // SyncFileFilter (Crashpad + BrowserMetrics + ShaderCache + GPUCache + *.dmp + *.pma
        // + ...). same denylist applies across GOG / Steam UFS / Epic recursive walks.
        // historical context: Crashpad alone exhausted the GOG quota in a single session --
        // desktop NW.js generates dumps, Galaxy uploads them, every device pulls + re-uploads
        // forever. broader denylist closes the same loop for sibling chromium internals.
        private fun isExcludedFromSync(relativePath: String): Boolean =
            app.gamenative.html5.savesync.SyncFileFilter.isChromiumInternal(relativePath)

        // Deterministic gzip (mtime=0) so the md5 of the gzipped payload is stable across
        // runs. Heroic's `heroic-gogdl` uses this exact shape (compression level 6, mtime 0)
        // when uploading to cloudstorage.gog.com. The Etag we send must equal md5(gzipped),
        // and Galaxy uses that hash as the manifest version -- different mtime header bytes
        // → different md5 → Galaxy thinks "file changed since last sync" → conflict icon
        // even before any user action. Using Java's stock GZIPOutputStream embeds the
        // current time, defeating cache validity.
        internal fun gzipDeterministic(input: ByteArray): ByteArray {
            val params = GzipParameters().apply {
                compressionLevel = 6
                modificationTime = 0L
            }
            val out = ByteArrayOutputStream()
            GzipCompressorOutputStream(out, params).use { it.write(input) }
            return out.toByteArray()
        }

        internal fun md5Hex(bytes: ByteArray): String {
            return MessageDigest.getInstance("MD5").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
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

                // Format: ISO-8601 second-precision UTC with explicit `+00:00` offset
                // (NOT `Z`). Heroic uses Python's `datetime.isoformat(timespec="seconds")`
                // on a UTC-aware datetime, which renders as `2026-05-09T16:53:11+00:00`.
                // Java's `ISO_INSTANT` formatter renders UTC as `Z` -- Galaxy parser may
                // reject that and treat the file's LocalLastModified metadata as missing
                // → flagged as "modified by another client" → conflict icon.
                val timestamp = file.lastModified()
                val instant = Instant.ofEpochMilli(timestamp)
                val odt = java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                updateTime = odt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
                updateTimestamp = timestamp / 1000 // Convert to seconds

                // MD5 of DETERMINISTIC gzipped content (mtime=0). This must match the
                // Etag we send on upload and the hash GOG returns in cloud-file listings,
                // otherwise Galaxy desktop sees the file as "modified since last known
                // state" and flags a sync conflict. Stock java.util.zip.GZIPOutputStream
                // bakes the current wall-clock time into the gzip header, so md5 changes
                // across runs even for identical content -- broken for our purposes.
                val raw = file.readBytes()
                val gzipped = gzipDeterministic(raw)
                md5Hash = md5Hex(gzipped)

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
        preferredAction: String = "none",
        // mirror-delete of cloud-only files is OPT-IN. defaults false so the wine GOG path stays
        // accretive (never deletes cloud) -- only the html5 outbound passes true, matching
        // SteamAutoCloud's propagateDeletions gate. keeps destructive cloud behavior html5-scoped.
        propagateDeletions: Boolean = false,
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
            val rawCloudFiles = getCloudFiles(credentials.userId, clientId, dirname, credentials.accessToken) ?: run {
                Timber.tag("GOG-CloudSaves").e("Failed to fetch cloud files, aborting sync")
                return@withContext 0L
            }
            Timber.tag("GOG").d("[Cloud Saves] Retrieved ${rawCloudFiles.size} total cloud files")
            // Filter Crashpad before classifier sees it -- otherwise updatedCloud/notExistingLocally
            // would still try to download those entries and feed them into conflict resolution.
            val cloudFiles = rawCloudFiles.filterNot { isExcludedFromSync(it.relativePath) }
            val excludedCloudCount = rawCloudFiles.size - cloudFiles.size
            if (excludedCloudCount > 0) {
                Timber.tag("GOG-CloudSaves").i("Skipped $excludedCloudCount cloud Crashpad-tree file(s) — excluded from download")
            }
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

            // Explicit "keep local" choice from the conflict dialog: force-upload every local file so
            // local wins, bypassing the conflict guard on the plain "upload" path below.
            if (preferredAction == "forceupload" && localFiles.isNotEmpty()) {
                Timber.tag("GOG-CloudSaves").i("Forcing upload of ${localFiles.size} file(s) (user requested)")
                localFiles.forEach { file ->
                    uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                }
                // Mirror semantic: after uploading our local set, DELETE any cloud file
                // that we don't have locally. Heroic does this in `saves.py` -- without
                // it, cloud accumulates stale entries from prior versions / other clients
                // and Galaxy desktop sees a manifest that diverges from what its own
                // local cache expects → permanent conflict icon. Skip tombstones (md5 ==
                // DELETION_MD5, already deleted server-side) and Crashpad (filtered both
                // ways -- preserving local-cloud parity for that subtree).
                val localPathSet = localFiles.map { it.relativePath }.toSet()
                val toDelete = if (propagateDeletions) {
                    cloudFiles.filter { cf ->
                        !cf.isDeleted &&
                            !isExcludedFromSync(cf.relativePath) &&
                            cf.relativePath !in localPathSet
                    }
                } else {
                    emptyList()
                }
                if (toDelete.isNotEmpty()) {
                    Timber.tag("GOG-CloudSaves").i(
                        "Deleting ${toDelete.size} cloud file(s) missing from local (mirror semantic)",
                    )
                    toDelete.forEach { file ->
                        deleteFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                    }
                }
                return@withContext currentTimestamp()
            }

            if (preferredAction == "upload" && localFiles.isNotEmpty()) {
                // Use classifier to intelligently determine which files need uploading
                val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTimestamp)

                // Don't clobber the cloud on an automatic exit upload. If the cloud is newer
                // (DOWNLOAD) or both sides changed (CONFLICT), skip and leave the timestamp
                // untouched — the next launch detects the conflict and prompts the user to resolve it.
                val action = classifier.determineAction()
                if (action == SyncAction.DOWNLOAD || action == SyncAction.CONFLICT) {
                    Timber.tag("GOG-CloudSaves").w("Skipping upload: cloud changed since last sync (action=$action), deferring to launch conflict prompt")
                    return@withContext lastSyncTimestamp
                }

                val filesToUpload = mutableListOf<SyncFile>()

                // Upload files that were updated locally since last sync
                filesToUpload.addAll(classifier.updatedLocal)

                // Upload files that don't exist remotely
                filesToUpload.addAll(classifier.notExistingRemotely)

                // Deduplicate by relativePath (new files can appear in both lists)
                val uniqueFilesToUpload = filesToUpload.distinctBy { it.relativePath }

                if (uniqueFilesToUpload.isNotEmpty()) {
                    Timber.tag("GOG-CloudSaves").i("Smart upload: ${uniqueFilesToUpload.size} file(s) changed since last sync (out of ${localFiles.size} total)")
                    uniqueFilesToUpload.forEach { file ->
                        uploadFile(credentials.userId, clientId, dirname, file, credentials.accessToken)
                    }
                } else {
                    Timber.tag("GOG-CloudSaves").i("Smart upload: No files changed since last sync, skipping upload")
                    return@withContext lastSyncTimestamp
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
     * Conflict timestamps for a save location, in milliseconds (for display).
     */
    data class ConflictInfo(
        val localTimestamp: Long,
        val remoteTimestamp: Long
    )

    /**
     * Detect whether a save location is in conflict (both local and cloud changed since the last
     * sync) WITHOUT uploading or downloading anything. Returns null when there is no conflict, or
     * on any error (fail open so the regular sync still runs).
     */
    suspend fun detectConflict(
        localPath: String,
        dirname: String,
        clientId: String,
        clientSecret: String,
        lastSyncTimestamp: Long = 0
    ): ConflictInfo? = withContext(Dispatchers.IO) {
        try {
            val syncDir = File(localPath)
            if (!syncDir.exists()) return@withContext null

            val localFiles = scanLocalFiles(syncDir)
            if (localFiles.isEmpty()) return@withContext null // nothing local => no conflict

            val credentials = GOGAuthManager.getGameCredentials(context, clientId, clientSecret).getOrNull()
                ?: return@withContext null
            val cloudFiles = getCloudFiles(credentials.userId, clientId, dirname, credentials.accessToken)
                ?: return@withContext null
            if (cloudFiles.none { !it.isDeleted }) return@withContext null // nothing in cloud => no conflict

            val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTimestamp)
            if (classifier.determineAction() != SyncAction.CONFLICT) return@withContext null

            // updateTimestamp is stored in seconds; convert to millis for display.
            val localTs = localFiles.mapNotNull { it.updateTimestamp }.maxOrNull() ?: 0L
            val remoteTs = cloudFiles.filter { !it.isDeleted }.mapNotNull { it.updateTimestamp }.maxOrNull() ?: 0L
            Timber.tag("GOG-CloudSaves").i("Conflict detected for '$dirname' (local: $localTs, remote: $remoteTs)")
            ConflictInfo(localTimestamp = localTs * 1000, remoteTimestamp = remoteTs * 1000)
        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Conflict detection failed for '$dirname': ${e.message}")
            null
        }
    }

    /**
     * Scan local directory for save files
     */
    private suspend fun scanLocalFiles(directory: File): List<SyncFile> = withContext(Dispatchers.IO) {
        val files = mutableListOf<SyncFile>()

        var skipped = 0
        fun scanRecursive(dir: File, basePath: String) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val relativePath = file.absolutePath.removePrefix(basePath)
                        .removePrefix("/")
                        .replace("\\", "/")
                    if (isExcludedFromSync(relativePath)) {
                        skipped++
                        return@forEach
                    }
                    files.add(SyncFile(relativePath, file.absolutePath))
                } else if (file.isDirectory) {
                    scanRecursive(file, basePath)
                }
            }
        }

        scanRecursive(directory, directory.absolutePath)
        if (skipped > 0) {
            Timber.tag("GOG-CloudSaves").i("Skipped $skipped local Crashpad-tree file(s) — excluded from upload")
        }

        // Calculate metadata for all files
        files.forEach { it.calculateMetadata() }

        files
    }

    /**
     * Returns the list of cloud files for this dirname, or null if the request failed
     * (network error, HTTP error, parse error). A successful but empty response returns
     * an empty list — callers must distinguish null (unknown) from empty (no cloud files).
     */
    private suspend fun getCloudFiles(
        userId: String,
        clientId: String,
        dirname: String,
        authToken: String
    ): List<CloudFile>? = withContext(Dispatchers.IO) {
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
                // Dump every response header -- Galaxy may key off a manifest-version /
                // x-account-* / etag header we're not echoing back on subsequent calls.
                val headerSummary = response.headers.toMultimap().entries
                    .joinToString("\n  ") { (k, v) -> "$k: ${v.joinToString(", ")}" }
                Timber.tag("GOG").i("[Cloud Saves] LIST response code=${response.code}\n  headers:\n  $headerSummary")

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No response body"
                    Timber.tag("GOG").e("[Cloud Saves] Failed to fetch cloud files: HTTP ${response.code}")
                    Timber.tag("GOG").e("[Cloud Saves] Response body: $errorBody")
                    return@withContext null
                }

                val responseBody = response.body?.string() ?: ""
                if (responseBody.isEmpty()) {
                    Timber.tag("GOG").d("[Cloud Saves] Empty response body from cloud storage API")
                    return@withContext emptyList()
                }

                val files = parseCloudFilesResponse(responseBody, dirname) ?: run {
                    Timber.tag("GOG").e("[Cloud Saves] Response was: $responseBody")
                    return@withContext null
                }

                Timber.tag("GOG").i("[Cloud Saves] Retrieved ${files.size} cloud files for dirname '$dirname'")
                files
            }

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to get cloud files")
            null
        }
    }

    internal fun parseCloudFilesResponse(responseBody: String, dirname: String): List<CloudFile>? {
        val items = try {
            JSONArray(responseBody)
        } catch (e: Exception) {
            Timber.tag("GOG").e(e, "[Cloud Saves] Failed to parse JSON array response")
            return null
        }

        Timber.tag("GOG").d("[Cloud Saves] Found ${items.length()} total items in cloud storage")

        val files = mutableListOf<CloudFile>()
        for (i in 0 until items.length()) {
            val fileObj = items.getJSONObject(i)
            val name = fileObj.optString("name", "")
            val hash = fileObj.optString("hash", "")
            val lastModified = fileObj.optString("last_modified")

            Timber.tag("GOG").d("[Cloud Saves]   Examining item $i: name='$name', dirname='$dirname'")

            // Empty dirname (Galaxy SDK fallback) => no namespace prefix; every object is ours.
            val matchesDir = dirname.isEmpty() || name.startsWith("$dirname/")
            if (name.isNotEmpty() && hash.isNotEmpty() && matchesDir) {
                val relativePath = if (dirname.isEmpty()) name else name.removePrefix("$dirname/")
                files.add(
                    CloudFile(
                        relativePath = relativePath,
                        md5Hash = hash,
                        updateTime = lastModified,
                        updateTimestamp = parseCloudTimestamp(lastModified),
                    ),
                )
                Timber.tag("GOG").d("[Cloud Saves]     ✓ Matched: relativePath='$relativePath'")
            } else {
                Timber.tag("GOG").d("[Cloud Saves]     ✗ Skipped (doesn't match dirname or missing data)")
            }
        }

        return files
    }

    internal fun parseCloudTimestamp(lastModified: String): Long? =
        try {
            // GOG returns timestamps like "2026-04-02T20:34:00.123456+00:00".
            OffsetDateTime.parse(lastModified).toInstant().epochSecond
        } catch (_: DateTimeParseException) {
            null
        }

    /**
     * Upload file to GOG cloud storage.
     *
     * Heroic-shape upload protocol (matches Galaxy's expected behavior):
     *   - Body: gzip-compressed file bytes (level 6, mtime=0 for deterministic md5)
     *   - Content-Encoding: gzip
     *   - Etag: <md5 hex of gzipped bytes>
     *   - X-Object-Meta-LocalLastModified: ISO-8601 of file mtime
     *   - URL path segments URL-encoded
     *
     * Pre-fix shape (raw bytes, no Content-Encoding, no Etag) caused Galaxy to
     * compute its own gzip-md5 expectation locally then see server-stored
     * raw-md5 in listings -- every file flagged as "modified since last sync"
     * → conflict icon before any user action. Reference: heroic-gogdl saves.py.
     */
    // per-file cloud URL with each path segment URL-encoded -- relativePath may contain
    // spaces/parens/etc. that break naive concatenation.
    private fun cloudFileUrl(userId: String, clientId: String, dirname: String, relativePath: String): HttpUrl {
        val b = HttpUrl.Builder()
            .scheme("https")
            .host("cloudstorage.gog.com")
            .addPathSegment("v1")
            .addPathSegment(userId)
            .addPathSegment(clientId)
        // empty dirname = Galaxy SDK fallback (no namespace prefix); an empty segment would
        // put a stray slash in the object path.
        if (dirname.isNotEmpty()) b.addPathSegment(dirname)
        relativePath.replace('\\', '/').split('/').forEach { segment ->
            if (segment.isNotEmpty()) b.addPathSegment(segment)
        }
        return b.build()
    }

    private suspend fun uploadFile(
        userId: String,
        clientId: String,
        dirname: String,
        file: SyncFile,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        try {
            val localFile = File(file.absolutePath)
            val rawBytes = localFile.readBytes()
            val gzippedBytes = gzipDeterministic(rawBytes)
            val gzippedMd5 = md5Hex(gzippedBytes)

            Timber.tag("GOG-CloudSaves").i(
                "Uploading: ${file.relativePath} (raw=${rawBytes.size} gzip=${gzippedBytes.size} md5=$gzippedMd5)",
            )

            val url = cloudFileUrl(userId, clientId, dirname, file.relativePath)

            // GOG stores saves gzip-compressed. Match the Galaxy/gogdl protocol: send the
            // DETERMINISTICALLY gzipped bytes (stable mtime -> stable Etag, required for Heroic/
            // GOG validation) with Content-Encoding: gzip and an Etag of the compressed MD5.
            val requestBody = gzippedBytes.toRequestBody(null, 0, gzippedBytes.size)

            val requestBuilder = Request.Builder()
                .url(url)
                .put(requestBody)
                .header("Authorization", "Bearer $authToken")
                .header("User-Agent", USER_AGENT)
                .header("X-Object-Meta-User-Agent", USER_AGENT)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Encoding", "gzip")
                .header("Etag", gzippedMd5)

            // Add last modified timestamp header if available
            file.updateTime?.let { timestamp ->
                requestBuilder.header("X-Object-Meta-LocalLastModified", timestamp)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use {
                if (response.isSuccessful) {
                    val serverEtag = response.header("Etag") ?: "<none>"
                    val serverDate = response.header("Date") ?: "<none>"
                    val xTimestamp = response.header("X-Timestamp") ?: "<none>"
                    val lastModified = response.header("Last-Modified") ?: "<none>"
                    Timber.tag("GOG-CloudSaves").i(
                        "Successfully uploaded: ${file.relativePath} | server.Etag=$serverEtag | server.X-Timestamp=$xTimestamp | server.Last-Modified=$lastModified | server.Date=$serverDate",
                    )
                } else {
                    val errorBody = response.body?.string() ?: "No response body"
                    val headerDump = response.headers.toMultimap().entries
                        .joinToString("\n  ") { (k, v) -> "$k: ${v.joinToString(", ")}" }
                    Timber.tag("GOG-CloudSaves").e("Failed to upload ${file.relativePath}: HTTP ${response.code}\n  response headers:\n  $headerDump\n  body: $errorBody")
                }
            }

        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to upload ${file.relativePath}")
        }
    }

    /**
     * Delete a file from GOG cloud storage. Used in mirror sync to remove cloud
     * objects that no longer exist locally -- Galaxy desktop's sync compares cloud
     * manifest against its expected file set; stale cloud objects force a conflict
     * state. Heroic's `saves.py` does the equivalent (HTTP DELETE per cloud-only file).
     */
    private suspend fun deleteFile(
        userId: String,
        clientId: String,
        dirname: String,
        file: CloudFile,
        authToken: String
    ) = withContext(Dispatchers.IO) {
        try {
            Timber.tag("GOG-CloudSaves").i("Deleting from cloud: ${file.relativePath}")

            val url = cloudFileUrl(userId, clientId, dirname, file.relativePath)

            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", "Bearer $authToken")
                .header("User-Agent", USER_AGENT)
                .header("X-Object-Meta-User-Agent", USER_AGENT)
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                if (response.isSuccessful) {
                    Timber.tag("GOG-CloudSaves").i("Successfully deleted: ${file.relativePath}")
                } else {
                    val errorBody = response.body?.string() ?: "No response body"
                    Timber.tag("GOG-CloudSaves").e(
                        "Failed to delete ${file.relativePath}: HTTP ${response.code}\n  body: $errorBody",
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag("GOG-CloudSaves").e(e, "Failed to delete ${file.relativePath}")
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

            val url = cloudFileUrl(userId, clientId, dirname, file.relativePath)

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

                val rawBytes = response.body?.bytes() ?: return@withContext
                // belt-and-suspenders: uploadFile sends Content-Encoding: gzip, and OkHttp normally
                // gunzips transparently on download (it added Accept-Encoding itself). but that
                // relies on the object store echoing Content-Encoding back on GET -- if it ever
                // doesn't, we'd write the still-gzipped bytes verbatim and corrupt the save. so if
                // the body still carries the gzip magic, decompress it ourselves; fall back to raw
                // on any failure (a genuine save coincidentally starting 1f 8b won't gunzip).
                val bytes = if (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte()) {
                    runCatching {
                        java.util.zip.GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
                    }.getOrElse {
                        Timber.tag("GOG-CloudSaves").w(it, "gzip magic but inflate failed for ${file.relativePath}; using raw")
                        rawBytes
                    }
                } else {
                    rawBytes
                }
                Timber.tag("GOG-CloudSaves").d("Downloaded ${bytes.size} bytes for ${file.relativePath}")

                // resolve against on-disk casing to avoid creating duplicate dirs
                val localFile = FileUtils.resolveCaseInsensitive(syncDir, file.relativePath)
                localFile.parentFile?.mkdirs()

                // Write file content
                FileOutputStream(localFile).use { fos ->
                    fos.write(bytes)
                }

                // Preserve cloud timestamp (must be done after closing the stream)
                file.updateTimestamp?.let { cloudTimestamp ->
                    val cloudMillis = cloudTimestamp * 1000
                    val success = localFile.setLastModified(cloudMillis)
                    if (success) {
                        val actualMillis = localFile.lastModified()
                        if (actualMillis == cloudMillis) {
                            Timber.tag("GOG-CloudSaves").d("Preserved cloud timestamp for ${file.relativePath}: $cloudTimestamp seconds")
                        } else {
                            Timber.tag("GOG-CloudSaves").w("Timestamp mismatch for ${file.relativePath}: set $cloudMillis but got $actualMillis")
                        }
                    } else {
                        Timber.tag("GOG-CloudSaves").w("Failed to set timestamp for ${file.relativePath}")
                    }
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

    /**
     * Gzip [data] for cloud upload. Uses a fixed mtime (GZIPOutputStream writes 0) so the output
     * is deterministic, matching gogdl's gzip.compress(data, 6, mtime=0).
     */
    private fun gzip(data: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(data) }
        return buffer.toByteArray()
    }
}
