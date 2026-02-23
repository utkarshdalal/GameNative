package app.gamenative.service

import androidx.room.withTransaction
import app.gamenative.data.PostSyncInfo
import app.gamenative.data.SaveFilePattern
import app.gamenative.data.SteamApp
import app.gamenative.data.UserFileInfo
import app.gamenative.data.UserFilesDownloadResult
import app.gamenative.data.UserFilesUploadResult
import app.gamenative.enums.PathType
import app.gamenative.enums.SaveLocation
import app.gamenative.enums.SyncResult
import app.gamenative.service.SteamService.Companion.FileChanges
import app.gamenative.service.SteamService.Companion.getAppDirPath
import app.gamenative.utils.FileUtils
import app.gamenative.utils.SteamUtils
import `in`.dragonbra.javasteam.enums.EResult
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileChangeList
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.AppFileInfo
import `in`.dragonbra.javasteam.steam.handlers.steamcloud.SteamCloud
import `in`.dragonbra.javasteam.util.crypto.CryptoHelper
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Date
import java.util.stream.Collectors
import java.util.zip.ZipInputStream
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.OutputStream
import java.net.SocketTimeoutException

/**
 * [Steam Auto Cloud](https://partner.steamgames.com/doc/features/cloud#steam_auto-cloud)
 */
object SteamAutoCloud {

    private const val MAX_USER_FILE_RETRIES = 3

    private fun findPlaceholderWithin(aString: String): Sequence<MatchResult> =
        Regex("%\\w+%").findAll(aString)

    private inline fun InputStream.copyTo(
        out: OutputStream,
        bufferSize: Int = 8 * 1024,
        progress: (Long) -> Unit,
    ) {
        val buf = ByteArray(bufferSize)
        var bytesRead: Int
        var total = 0L
        while (read(buf).also { bytesRead = it } >= 0) {
            if (bytesRead == 0) continue
            out.write(buf, 0, bytesRead)
            total += bytesRead
            progress(total)
        }
    }

    fun syncUserFiles(
        appInfo: SteamApp,
        clientId: Long,
        steamInstance: SteamService,
        steamCloud: SteamCloud,
        preferredSave: SaveLocation = SaveLocation.None,
        parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        prefixToPath: (String) -> String,
        overrideLocalChangeNumber: Long? = null,
        onProgress: ((message: String, progress: Float) -> Unit)? = null,
    ): Deferred<PostSyncInfo?> = parentScope.async {
        val postSyncInfo: PostSyncInfo?

        Timber.i("Retrieving save files of ${appInfo.name}")

        val getPathTypePairs: (AppFileChangeList) -> List<Pair<String, String>> = { fileList ->
            fileList.pathPrefixes
                .map {
                    var matchResults = findPlaceholderWithin(it).map { it.value }.toList()
                    val bare = if (it.startsWith("ROOT_MOD")) listOf("ROOT_MOD") else emptyList()

                    Timber.i("Mapping prefix $it and found $matchResults")

                    if (matchResults.isEmpty()) {
                        matchResults = List(1) { PathType.DEFAULT.name }
                    }

                    matchResults + bare
                }
                .flatten()
                .distinct()
                .map { it to prefixToPath(it) }
        }

        val convertPrefixes: (AppFileChangeList) -> List<String> = { fileList ->
            val pathTypePairs = getPathTypePairs(fileList)

            fileList.pathPrefixes.map { prefix ->
                var modified = prefix

                val prefixContainsNoPlaceholder = findPlaceholderWithin(prefix).none()

                if (prefixContainsNoPlaceholder) {
                    modified = Paths.get(PathType.DEFAULT.name, prefix).pathString
                }

                pathTypePairs.forEach {
                    modified = modified.replace(it.first, it.second)
                }

                // if the prefix has not been modified then there were no placeholders in it
                // so we need to set it to point to the default path
                if (modified == prefix) {
                    modified = Paths.get(prefixToPath(PathType.DEFAULT.name), modified).toString()
                }

                modified
            }
        }

        val getFilePrefix: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            if (file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(fileList.pathPrefixes[file.pathPrefixIndex]).pathString
            } else {
                ""
            }
        }

        val getFilePrefixPath: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            Paths.get(getFilePrefix(file, fileList), file.filename).pathString
        }

        val getFullFilePath: (AppFileInfo, AppFileChangeList) -> Path = getFullFilePath@{ file, fileList ->
            val gameInstallPrefix = "%${PathType.GameInstall.name}%"
            if (file.filename.startsWith(gameInstallPrefix)) {
                // Steam API sometimes returns prefix="" and filename="%GameInstall%save0.dat" instead of splitting correctly.
                return@getFullFilePath Paths.get(
                    prefixToPath(PathType.GameInstall.name),
                    file.filename.removePrefix(gameInstallPrefix)
                )
            }

            val convertedPrefixes = convertPrefixes(fileList)

            if (file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(convertedPrefixes[file.pathPrefixIndex], file.filename)
            } else {
                // if the file does not reference any prefix then we need to set it to the default path
                Paths.get(prefixToPath(PathType.DEFAULT.name), file.filename)
            }
        }

        val getFilesDiff: (List<UserFileInfo>, List<UserFileInfo>) -> Pair<Boolean, FileChanges> = { currentFiles, oldFiles ->
            val overlappingFiles = currentFiles.filter { currentFile ->
                oldFiles.any { currentFile.prefixPath == it.prefixPath }
            }

            val newFiles = currentFiles.filter { currentFile ->
                !oldFiles.any { currentFile.prefixPath == it.prefixPath }
            }

            val deletedFiles = oldFiles.filter { oldFile ->
                !currentFiles.any { oldFile.prefixPath == it.prefixPath }
            }

            val modifiedFiles = overlappingFiles.filter { file ->
                oldFiles.first {
                    it.prefixPath == file.prefixPath
                }.let {
                    Timber.i("Comparing SHA of ${it.prefixPath} and ${file.prefixPath}")
                    Timber.i("[${it.sha.joinToString(", ")}]\n[${file.sha.joinToString(", ")}]")

                    !it.sha.contentEquals(file.sha)
                }
            }

            val changesExist = newFiles.isNotEmpty() || deletedFiles.isNotEmpty() || modifiedFiles.isNotEmpty()

            changesExist to FileChanges(deletedFiles, modifiedFiles, newFiles)
        }

        val hasHashConflicts: (Map<String, List<UserFileInfo>>, AppFileChangeList) -> Boolean =
            { localUserFiles, fileList ->
                fileList.files.any { file ->
                    Timber.i("Checking for " + "${getFilePrefix(file, fileList)} in ${localUserFiles.keys}")

                    localUserFiles[getFilePrefix(file, fileList)]?.let { localUserFile ->
                        localUserFile.firstOrNull {
                            Timber.i("Comparing ${file.filename} and ${it.filename}")

                            it.filename == file.filename
                        }?.let {
                            Timber.i("Comparing SHA of ${getFilePrefixPath(file, fileList)} and ${it.prefixPath}")
                            Timber.i("[${file.shaFile.joinToString(", ")}]\n[${it.sha.joinToString(", ")}]")

                            !file.shaFile.contentEquals(it.sha)
                        }
                    } == true
                }
            }

        val getLocalUserFilesAsPrefixMap: () -> Map<String, List<UserFileInfo>> = {
            val savePatterns = appInfo.ufs.saveFilePatterns.filter { userFile -> userFile.root.isWindows }

            if (savePatterns.isNotEmpty()) {
                val result = mutableMapOf<String, MutableList<UserFileInfo>>()

                savePatterns.forEach { userFile ->
                    val basePath = Paths.get(prefixToPath(userFile.root.toString()), userFile.substitutedPath)

                    Timber.i("Looking for saves in $basePath with pattern ${userFile.pattern} (prefix ${userFile.prefix})")

                    val files = FileUtils.findFilesRecursive(
                        rootPath = basePath,
                        pattern = userFile.pattern,
                        maxDepth = 5,
                    ).map {
                        val sha = CryptoHelper.shaHash(Files.readAllBytes(it))

                        Timber.i("Found ${it.pathString}\n\tin ${userFile.prefix}\n\twith sha [${sha.joinToString(", ")}]")

                        val relativePath = basePath.relativize(it).pathString

                        UserFileInfo(userFile.root, userFile.substitutedPath, relativePath, Files.getLastModifiedTime(it).toMillis(), sha)
                    }.collect(Collectors.toList())

                    Timber.i("Found ${files.size} file(s) in $basePath for pattern ${userFile.pattern}")

                    val prefixKey = Paths.get(userFile.prefix).pathString
                    result.getOrPut(prefixKey) { mutableListOf() }.addAll(files)
                }

                result
            } else {
                // Fallback: no UFS patterns; scan SteamUserData root recursively (depth 5)
                val rootType = PathType.SteamUserData
                val basePath = Paths.get(prefixToPath(rootType.toString()))

                Timber.i("No UFS patterns; scanning $basePath recursively (depth 5) under ${rootType.name}")

                val files = FileUtils.findFilesRecursive(
                    rootPath = basePath,
                    pattern = "*",
                    maxDepth = 5,
                ).map {
                    val sha = CryptoHelper.shaHash(Files.readAllBytes(it))

                    val relativePath = basePath.relativize(it).pathString

                    Timber.i("Found ${it.pathString}\n\tin %${rootType.name}%\n\twith sha [${sha.joinToString(", ")}]")

                    // Store relative path in filename; empty path component
                    UserFileInfo(rootType, "", relativePath, Files.getLastModifiedTime(it).toMillis(), sha)
                }.collect(Collectors.toList())

                Timber.i("Found ${files.size} file(s) in $basePath for fallback recursive scan")

                mapOf(Paths.get("%${rootType.name}%").pathString to files)
            }
        }

        val fileChangeListToUserFiles: (AppFileChangeList) -> List<UserFileInfo> = { appFileListChange ->
            val pathTypePairs = getPathTypePairs(appFileListChange)

            appFileListChange.files.map {
                UserFileInfo(
                    root = if (it.pathPrefixIndex < pathTypePairs.size) {
                        PathType.from(pathTypePairs[it.pathPrefixIndex].first)
                    } else {
                        PathType.GameInstall
                    },
                    path = if (it.pathPrefixIndex < pathTypePairs.size) {
                        appFileListChange.pathPrefixes[it.pathPrefixIndex]
                    } else {
                        ""
                    },
                    filename = it.filename,
                    timestamp = it.timestamp.time,
                    sha = it.shaFile,
                )
            }
        }

        val buildUrl: (Boolean, String, String) -> String = { useHttps, urlHost, urlPath ->
            val scheme = if (useHttps) "https://" else "http://"
            "$scheme${urlHost}$urlPath"
        }

        val downloadFiles: (AppFileChangeList, CoroutineScope) -> Deferred<UserFilesDownloadResult> = { fileList, parentScope ->
            parentScope.async {
                var filesDownloaded = 0
                var bytesDownloaded = 0L
                val totalFiles = fileList.files.size

                fileList.files.forEachIndexed { index, file ->
                    val prefixedPath = getFilePrefixPath(file, fileList)
                    val actualFilePath = getFullFilePath(file, fileList)

                    Timber.i("$prefixedPath -> $actualFilePath")

                    val fileDownloadInfo = steamCloud.clientFileDownload(appInfo.id, prefixedPath).await()

                    if (fileDownloadInfo.urlHost.isNotEmpty()) {
                        onProgress?.invoke("Downloading ${file.filename}", -1f)
                        val httpUrl = with(fileDownloadInfo) {
                            buildUrl(useHttps, urlHost, urlPath)
                        }

                        Timber.i("Downloading $httpUrl")

                        val headers = Headers.headersOf(
                            *fileDownloadInfo.requestHeaders
                                .map { listOf(it.name, it.value) }
                                .flatten()
                                .toTypedArray(),
                        )

                        val request = Request.Builder()
                            .url(httpUrl)
                            .headers(headers)
                            .build()

                        val httpClient = steamInstance.steamClient!!.configuration.httpClient

                        val response = withTimeout(SteamService.requestTimeout) {
                            httpClient.newCall(request).execute()
                        }

                        if (!response.isSuccessful) {
                            Timber.w("File download of $prefixedPath was unsuccessful")
                            response.close()
                            return@forEachIndexed
                        }

                        try {
                            val totalFileSize = fileDownloadInfo.rawFileSize.toLong()
                            var totalBytesRead = 0L
                            var lastReportedProgress = -1f
                            val progressThreshold = 0.01f // Update every 1%

                            val copyToFile: (InputStream) -> Unit = { input ->
                                Files.createDirectories(actualFilePath.parent)

                                FileOutputStream(actualFilePath.toString()).use { fs ->
                                    input.copyTo(fs, 8 * 1024) { bytesRead ->
                                        totalBytesRead = bytesRead
                                        if (totalFileSize > 0) {
                                            val currentProgress = (totalBytesRead.toFloat() / totalFileSize).coerceIn(0f, 1f)
                                            if (currentProgress - lastReportedProgress >= progressThreshold || currentProgress >= 1f) {
                                                onProgress?.invoke("Downloading ${file.filename}", currentProgress)
                                                lastReportedProgress = currentProgress
                                            }
                                        }
                                    }

                                    if (totalBytesRead != totalFileSize) {
                                        Timber.w("Bytes read from stream of $prefixedPath does not match expected size")
                                    }
                                }
                            }

                            withTimeout(SteamService.responseTimeout) {
                                if (fileDownloadInfo.fileSize != fileDownloadInfo.rawFileSize) {
                                    response.body?.byteStream()?.use { inputStream ->
                                        ZipInputStream(inputStream).use { zipInput ->
                                            val entry = zipInput.nextEntry

                                            if (entry == null) {
                                                Timber.w("Downloaded user file $prefixedPath has no zip entries")
                                                return@withTimeout
                                            }

                                            copyToFile(zipInput)

                                            if (zipInput.nextEntry != null) {
                                                Timber.e("Downloaded user file $prefixedPath has more than one zip entry")
                                            }
                                        }
                                    }
                                } else {
                                    response.body?.byteStream()?.use { inputStream ->
                                        copyToFile(inputStream)
                                    }
                                }

                                filesDownloaded++

                                bytesDownloaded += fileDownloadInfo.fileSize
                            }
                        } catch (e: FileSystemException) {
                            Timber.w("Could not download $actualFilePath: %s", e.message);
                        } catch (e: SocketTimeoutException) {
                            Timber.w("Could not download $actualFilePath: %s", e.message);
                        }

                        response.close()
                    } else {
                        Timber.w("URL host of $prefixedPath was empty")
                    }
                }

                if (totalFiles > 0) {
                    onProgress?.invoke("Download complete", 1.0f)
                }

                UserFilesDownloadResult(filesDownloaded, bytesDownloaded)
            }
        }

        val uploadFiles: (FileChanges, CoroutineScope) -> Deferred<UserFilesUploadResult> = { fileChanges, parentScope ->
            parentScope.async {
                var filesUploaded = 0
                var bytesUploaded = 0L

                val filesToDelete = fileChanges.filesDeleted.map { it.prefixPath }

                val filesToUpload = fileChanges.filesCreated
                    .union(fileChanges.filesModified)
                    .map { it.prefixPath to it }
                    // Filter out entries whose files no longer exist at upload time
                    .filter { Files.exists(it.second.getAbsPath(prefixToPath)) }

                val totalFiles = filesToUpload.size

                Timber.i(
                    "Beginning app upload batch with ${filesToDelete.size} file(s) to delete " +
                        "and ${filesToUpload.size} file(s) to upload",
                )

                val uploadBatchResponse = steamCloud.beginAppUploadBatch(
                    appId = appInfo.id,
                    machineName = SteamUtils.getMachineName(steamInstance),
                    clientId = clientId,
                    filesToDelete = filesToDelete,
                    filesToUpload = filesToUpload.map { it.first },
                    // TODO: have branch be user selected and use that selection here
                    appBuildId = appInfo.branches["public"]?.buildId ?: 0,
                ).await()

                var uploadBatchSuccess = true

                filesToUpload.map { it.second }.forEachIndexed { index, file ->
                    val absFilePath = file.getAbsPath(prefixToPath)

                    val fileSize = try {
                        Files.size(absFilePath).toInt()
                    } catch (e: Exception) {
                        Timber.w("Skipping upload of ${file.prefixPath}: ${e.javaClass.simpleName}: ${e.message}")
                        uploadBatchSuccess = false
                        return@forEachIndexed
                    }

                    Timber.i("Beginning upload of ${file.prefixPath} whose timestamp is ${file.timestamp}")

                    // Report start of upload
                    onProgress?.invoke("Uploading ${file.filename}", 0f)

                    val uploadInfo = steamCloud.beginFileUpload(
                        appId = appInfo.id,
                        filename = if (appInfo.ufs.saveFilePatterns.isEmpty()) {
                            file.path + file.filename
                        } else {
                            file.prefixPath
                        },
                        fileSize = fileSize,
                        rawFileSize = fileSize,
                        fileSha = file.sha,
                        // timestamp = prootTimestampToDate(file.timestamp),
                        timestamp = Date(file.timestamp),
                        uploadBatchId = uploadBatchResponse.batchID,
                    ).await()

                    var uploadFileSuccess = true
                    var bytesUploadedForFile = 0L
                    var lastReportedProgress = -1f
                    val progressThreshold = 0.01f // Update every 1% change

                    RandomAccessFile(absFilePath.pathString, "r").use { fs ->
                        uploadInfo.blockRequests.forEach { blockRequest ->
                            val httpUrl = buildUrl(
                                blockRequest.useHttps,
                                blockRequest.urlHost,
                                blockRequest.urlPath,
                            )

                            Timber.i("Uploading to $httpUrl")
                            Timber.i(
                                "Block Request:" +
                                    "\n\tblockOffset: ${blockRequest.blockOffset}" +
                                    "\n\tblockLength: ${blockRequest.blockLength}" +
                                    "\n\trequestHeaders:\n\t\t${
                                        blockRequest.requestHeaders.joinToString("\n\t\t") { "${it.name}: ${it.value}" }
                                    }" +
                                    "\n\texplicitBodyData: [${
                                        blockRequest.explicitBodyData.joinToString(
                                            ", ",
                                        )
                                    }]" +
                                    "\n\tmayParallelize: ${blockRequest.mayParallelize}",
                            )

                            val byteArray = ByteArray(blockRequest.blockLength)

                            fs.seek(blockRequest.blockOffset)

                            val bytesRead = fs.read(byteArray, 0, blockRequest.blockLength)

                            Timber.i("Read $bytesRead byte(s) for block")

                            val mediaType = if (blockRequest.requestHeaders.any { it.name.equals("Content-Type", ignoreCase = true) }) {
                                blockRequest.requestHeaders.first { it.name.equals("Content-Type", ignoreCase = true) }.value.toMediaTypeOrNull()
                            } else {
                                "application/octet-stream".toMediaTypeOrNull()
                            }

                            val requestBody = byteArray.toRequestBody(mediaType)

                            // val requestBody = byteArray.toRequestBody()

                            val headers = Headers.headersOf(
                                *blockRequest.requestHeaders
                                    .map { listOf(it.name, it.value) }
                                    .flatten()
                                    .toTypedArray(),
                            )

                            val request = Request.Builder()
                                .url(httpUrl)
                                .put(requestBody)
                                .headers(headers)
                                .addHeader("Accept", "text/html,*/*;q=0.9")
                                .addHeader("accept-encoding", "gzip,identity,*;q=0")
                                .addHeader("accept-charset", "ISO-8859-1,utf-8,*;q=0.7")
                                .addHeader("user-agent", "Valve/Steam HTTP Client 1.0")
                                .build()

                            val httpClient = steamInstance.steamClient!!.configuration.httpClient

                            Timber.i("Sending request to ${request.url} using\n$request")

                            withTimeout(SteamService.requestTimeout) {
                                val response = httpClient.newCall(request).execute()

                                if (!response.isSuccessful) {
                                    Timber.w(
                                        "Failed to upload part of %s: %s, %s",
                                        file.prefixPath,
                                        response.message,
                                        response?.body.toString(),
                                    )

                                    uploadFileSuccess = false
                                    uploadBatchSuccess = false
                                } else {
                                    // Update progress after successful block upload
                                    bytesUploadedForFile += blockRequest.blockLength
                                    if (fileSize > 0) {
                                        val currentProgress = (bytesUploadedForFile.toFloat() / fileSize).coerceIn(0f, 1f)
                                        // Only update if progress changed by at least 1% or we're at 100%
                                        if (currentProgress - lastReportedProgress >= progressThreshold || currentProgress >= 1f) {
                                            onProgress?.invoke("Uploading ${file.filename}", currentProgress)
                                            lastReportedProgress = currentProgress
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (uploadFileSuccess) {
                        filesUploaded++
                        bytesUploaded += fileSize
                    }

                    val commitSuccess = steamCloud.commitFileUpload(
                        transferSucceeded = uploadFileSuccess,
                        appId = appInfo.id,
                        fileSha = file.sha,
                        filename = if (appInfo.ufs.saveFilePatterns.isEmpty()) {
                            file.path + file.filename
                        } else {
                            file.prefixPath
                        },
                    ).await()

                    Timber.i("File ${file.prefixPath} commit success: $commitSuccess")
                }

                steamCloud.completeAppUploadBatch(
                    appId = appInfo.id,
                    batchId = uploadBatchResponse.batchID,
                    batchEResult = if (uploadBatchSuccess) EResult.OK else EResult.Fail,
                ).await()

                if (totalFiles > 0) {
                    onProgress?.invoke("Upload complete", 1.0f)
                }

                UserFilesUploadResult(uploadBatchSuccess, uploadBatchResponse.appChangeNumber, filesUploaded, bytesUploaded)
            }
        }

        var syncResult = SyncResult.Success
        var remoteTimestamp = 0L
        var localTimestamp = 0L
        var uploadsRequired = false
        var uploadsCompleted = true

        // sync metrics
        var filesUploaded = 0
        var filesDownloaded = 0
        var filesDeleted = 0
        var filesManaged = 0
        var bytesUploaded = 0L
        var bytesDownloaded = 0L
        var microsecTotal = 0L
        var microsecInitCaches = 0L
        var microsecValidateState = 0L
        var microsecAcLaunch = 0L
        var microsecAcPrepUserFiles = 0L
        var microsecAcExit = 0L
        var microsecBuildSyncList = 0L
        var microsecDeleteFiles = 0L
        var microsecDownloadFiles = 0L
        var microsecUploadFiles = 0L

        microsecTotal = measureTime {
            val localAppChangeNumber = overrideLocalChangeNumber ?: steamInstance.changeNumbersDao.getByAppId(appInfo.id)?.changeNumber ?: -1

            val changeNumber = if (localAppChangeNumber >= 0) localAppChangeNumber else 0
            val appFileListChange = steamCloud.getAppFileListChange(appInfo.id, changeNumber).await()

            val cloudAppChangeNumber = appFileListChange.currentChangeNumber

            Timber.i("AppChangeNumber: $localAppChangeNumber -> $cloudAppChangeNumber")

            appFileListChange.printFileChangeList(appInfo)

            // retrieve existing user files from local storage
            val localUserFilesMap: Map<String, List<UserFileInfo>>
            val allLocalUserFiles: List<UserFileInfo>

            microsecInitCaches = measureTime {
                localUserFilesMap = getLocalUserFilesAsPrefixMap()
                allLocalUserFiles = localUserFilesMap.map { it.value }.flatten()
            }.inWholeMicroseconds

            val downloadUserFiles: (CoroutineScope) -> Deferred<PostSyncInfo?> = { parentScope ->
                parentScope.async {
                    Timber.i("Downloading cloud user files")

                    val remoteUserFiles = fileChangeListToUserFiles(appFileListChange)
                    val filesDiff = getFilesDiff(remoteUserFiles, allLocalUserFiles).second
                    microsecDeleteFiles = measureTime {
                        var totalFilesDeleted = 0

                        filesDiff.filesDeleted.forEach {
                            val deleted = Files.deleteIfExists(it.getAbsPath(prefixToPath))
                            if (deleted) totalFilesDeleted++
                        }

                        filesDeleted = totalFilesDeleted
                    }.inWholeMicroseconds

                    microsecDownloadFiles = measureTime {
                        val downloadInfo = downloadFiles(appFileListChange, parentScope).await()
                        filesDownloaded = downloadInfo.filesDownloaded
                        bytesDownloaded = downloadInfo.bytesDownloaded
                    }.inWholeMicroseconds

                    val updatedLocalFiles: Map<String, List<UserFileInfo>>
                    val hasLocalChanges: Boolean
                    microsecValidateState = measureTime {
                        updatedLocalFiles = getLocalUserFilesAsPrefixMap()
                        hasLocalChanges = hasHashConflicts(updatedLocalFiles, appFileListChange)
                        filesManaged = updatedLocalFiles.size
                    }.inWholeMicroseconds

                    // var retries = 0

                    // do {
                    //     downloadFiles(appFileListChange, parentScope).await()
                    //     updatedLocalFiles = getLocalUserFilesAsPrefixMap()
                    //     hasLocalChanges =
                    //         hasHashConflicts(updatedLocalFiles, appFileListChange)
                    // } while (hasLocalChanges && retries++ < MAX_USER_FILE_RETRIES)
                    //

                    if (hasLocalChanges) {
                        Timber.e("Failed to download latest user files after $MAX_USER_FILE_RETRIES tries")

                        syncResult = SyncResult.DownloadFail

                        return@async PostSyncInfo(syncResult)
                    }

                    with(steamInstance) {
                        db.withTransaction {
                            fileChangeListsDao.insert(appInfo.id, updatedLocalFiles.map { it.value }.flatten())
                            changeNumbersDao.insert(appInfo.id, cloudAppChangeNumber)
                        }
                    }

                    return@async null
                }
            }

            val uploadUserFiles: (CoroutineScope) -> Deferred<Unit> = { parentScope ->
                parentScope.async {
                    Timber.i("Uploading local user files")

                    val fileChanges = steamInstance.fileChangeListsDao.getByAppId(appInfo.id)!!.let {
                        val result = getFilesDiff(allLocalUserFiles, it.userFileInfo)

                        result.second
                    }

                    uploadsRequired = fileChanges.filesCreated.isNotEmpty() || fileChanges.filesModified.isNotEmpty()

                    val uploadResult: UserFilesUploadResult

                    microsecUploadFiles = measureTime {
                        uploadResult = uploadFiles(fileChanges, parentScope).await()
                        filesUploaded = uploadResult.filesUploaded
                        bytesUploaded = uploadResult.bytesUploaded
                        uploadsCompleted = uploadsRequired && uploadResult.uploadBatchSuccess
                    }.inWholeMicroseconds

                    filesManaged = allLocalUserFiles.size

                    if (uploadResult.uploadBatchSuccess) {
                        with(steamInstance) {
                            db.withTransaction {
                                fileChangeListsDao.insert(appInfo.id, allLocalUserFiles)
                                changeNumbersDao.insert(appInfo.id, uploadResult.appChangeNumber)
                            }
                        }
                    } else {
                        syncResult = SyncResult.UpdateFail
                    }
                }
            }

            if (localAppChangeNumber < cloudAppChangeNumber) {
                // our change number is less than the expected, meaning we are behind and
                // need to download the new user files, but first we should check that
                // the local user files are not conflicting with their respective change
                // number or else that would mean that the user made changes locally and
                // on a separate device and they must choose between the two
                microsecAcLaunch = measureTime {
                    var hasLocalChanges: Boolean

                    microsecAcPrepUserFiles = measureTime {
                        hasLocalChanges = steamInstance.fileChangeListsDao.getByAppId(appInfo.id)?.let {
                            getFilesDiff(allLocalUserFiles, it.userFileInfo).first
                        } == true
                    }.inWholeMicroseconds

                    /*TODO: hasLocalChanges should be true if the user plays offline for the first time without ever pulling cloud saves
                       If that happens, the next time they go online, their change number is -1, and saves are always overwritten by cloud*/
                    if (!hasLocalChanges) {
                        // we can safely download the new changes since no changes have been
                        // made locally

                        Timber.i("No local changes but new cloud user files")

                        downloadUserFiles(parentScope).await()?.let {
                            return@async it
                        }
                    } else {
                        Timber.i("Found local changes and new cloud user files, conflict resolution...")

                        when (preferredSave) {
                            SaveLocation.Local -> {
                                // overwrite remote save with the local one
                                uploadUserFiles(parentScope).await()
                            }

                            SaveLocation.Remote -> {
                                // overwrite local save with the remote one
                                downloadUserFiles(parentScope).await()?.let {
                                    return@async it
                                }
                            }

                            SaveLocation.None -> {
                                syncResult = SyncResult.Conflict
                                remoteTimestamp = appFileListChange.files.map { it.timestamp.time }.maxOrNull() ?: 0L
                                localTimestamp = allLocalUserFiles.map { it.timestamp }.maxOrNull() ?: 0L
                            }
                        }
                    }
                }.inWholeMicroseconds
            } else if (localAppChangeNumber == cloudAppChangeNumber) {
                // our app change numbers are the same so the file hashes should match
                // if they do not then that means we have new user files locally that
                // need uploading
                microsecAcExit = measureTime {
                    // var fileChanges: FileChanges? = null

                    val hasLocalChanges = steamInstance.fileChangeListsDao.getByAppId(appInfo.id)
                        ?.let {
                            val result = getFilesDiff(allLocalUserFiles, it.userFileInfo)
                            // fileChanges = result.second
                            result.first
                        } == true

                    if (hasLocalChanges) {
                        Timber.i("Found local changes and no new cloud user files")

                        uploadUserFiles(parentScope).await()
                    } else {
                        Timber.i("No local changes and no new cloud user files, doing nothing...")

                        syncResult = SyncResult.UpToDate
                    }
                }.inWholeMicroseconds
            } else {
                // our last scenario is if the change number we have is greater than
                // the change number from the cloud. This scenario should not happen, I
                // believe, since we get the new app change number after having downloaded
                // or uploaded from/to the cloud, so we should always be either behind or
                // on par with the cloud change number, never ahead
                Timber.e("Local change number greater than cloud $localAppChangeNumber > $cloudAppChangeNumber")

                syncResult = SyncResult.UnknownFail
            }
        }.inWholeMicroseconds

        postSyncInfo = PostSyncInfo(
            syncResult = syncResult,
            remoteTimestamp = remoteTimestamp,
            localTimestamp = localTimestamp,
            uploadsRequired = uploadsRequired,
            uploadsCompleted = uploadsCompleted,
            filesUploaded = filesUploaded,
            filesDownloaded = filesDownloaded,
            filesDeleted = filesDeleted,
            filesManaged = filesManaged,
            bytesUploaded = bytesUploaded,
            bytesDownloaded = bytesDownloaded,
            microsecTotal = microsecTotal,
            microsecInitCaches = microsecInitCaches,
            microsecValidateState = microsecValidateState,
            microsecAcLaunch = microsecAcLaunch,
            microsecAcPrepUserFiles = microsecAcPrepUserFiles,
            microsecAcExit = microsecAcExit,
            // microsecBuildSyncList = microsecBuildSyncList,
            microsecDeleteFiles = microsecDeleteFiles,
            microsecDownloadFiles = microsecDownloadFiles,
            microsecUploadFiles = microsecUploadFiles,
        )

        postSyncInfo
    }

    private fun AppFileChangeList.printFileChangeList(appInfo: SteamApp) {
        with(this) {
            Timber.i(
                "GetAppFileListChange(${appInfo.id}):" +
                    "\n\tTotal Files: ${files.size}" +
                    "\n\tCurrent Change Number: $currentChangeNumber" +
                    "\n\tIs Only Delta: $isOnlyDelta" +
                    "\n\tApp BuildID Hwm: $appBuildIDHwm" +
                    "\n\tPath Prefixes: \n\t\t${pathPrefixes.joinToString("\n\t\t")}" +
                    "\n\tMachine Names: \n\t\t${machineNames.joinToString("\n\t\t")}" +
                    files.joinToString {
                        "\n\t${it.filename}:" +
                            "\n\t\tshaFile: ${it.shaFile}" +
                            "\n\t\ttimestamp: ${it.timestamp}" +
                            "\n\t\trawFileSize: ${it.rawFileSize}" +
                            "\n\t\tpersistState: ${it.persistState}" +
                            "\n\t\tplatformsToSync: ${it.platformsToSync}" +
                            "\n\t\tpathPrefixIndex: ${it.pathPrefixIndex}" +
                            "\n\t\tmachineNameIndex: ${it.machineNameIndex}"
                    },
            )
        }
    }
}
