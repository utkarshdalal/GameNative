package app.gamenative.service

import androidx.room.withTransaction
import app.gamenative.data.PostSyncInfo
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

/**
 * [Steam Auto Cloud](https://partner.steamgames.com/doc/features/cloud#steam_auto-cloud)
 */
object SteamAutoCloud {

    private const val MAX_USER_FILE_RETRIES = 3

    private fun findPlaceholderWithin(aString: String): Sequence<MatchResult> =
        Regex("%\\w+%").findAll(aString)

    fun syncUserFiles(
        appInfo: SteamApp,
        clientId: Long,
        steamInstance: SteamService,
        steamCloud: SteamCloud,
        preferredSave: SaveLocation = SaveLocation.None,
        parentScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        prefixToPath: (String) -> String,
        overrideLocalChangeNumber: Long? = null,
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

                modified
            }
        }

        val getFilePrefix: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            if (file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(fileList.pathPrefixes[file.pathPrefixIndex]).pathString
            } else {
                Paths.get("%${PathType.DEFAULT.name}%").pathString
            }
        }

        val getFilePrefixPath: (AppFileInfo, AppFileChangeList) -> String = { file, fileList ->
            Paths.get(getFilePrefix(file, fileList), file.filename).pathString
        }

        val getFullFilePath: (AppFileInfo, AppFileChangeList) -> Path = { file, fileList ->
            val convertedPrefixes = convertPrefixes(fileList)

            if (file.pathPrefixIndex < fileList.pathPrefixes.size) {
                Paths.get(convertedPrefixes[file.pathPrefixIndex], file.filename)
            } else {
                Paths.get(getAppDirPath(appInfo.id), file.filename)
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

        // val hasHashConflictsOrRemoteMissingFiles: (Map<String, List<UserFileInfo>>, AppFileChangeList) -> Boolean =
        //     { localUserFiles, fileList ->
        //         localUserFiles.values.any {
        //             it.any { localUserFile ->
        //                 fileList.files.firstOrNull { cloudFile ->
        //                     val cloudFilePath = getFilePrefixPath(cloudFile, fileList)
        //
        //                     val localFilePath = Paths.get(
        //                         localUserFile.prefix,
        //                         localUserFile.filename,
        //                     ).pathString
        //
        //                     Timber.i("Comparing $cloudFilePath and $localFilePath")
        //
        //                     cloudFilePath == localFilePath
        //                 }?.let {
        //                     Timber.i("Comparing SHA of ${getFilePrefixPath(it, fileList)} and ${localUserFile.prefixPath}")
        //                     Timber.i("[${it.shaFile.joinToString(", ")}]\n[${localUserFile.sha.joinToString(", ")}]")
        //
        //                     it.shaFile.contentEquals(localUserFile.sha)
        //                 } != true
        //             }
        //         }
        //     }

        val getLocalUserFilesAsPrefixMap: () -> Map<String, List<UserFileInfo>> = {
            appInfo.ufs.saveFilePatterns
                .filter { userFile -> userFile.root.isWindows }
                .associate { userFile ->
                    val files = FileUtils.findFiles(
                        Paths.get(prefixToPath(userFile.root.toString()), userFile.path),
                        userFile.pattern,
                    ).map {
                        val sha = CryptoHelper.shaHash(Files.readAllBytes(it))

                        Timber.i("Found ${it.pathString}\n\tin ${userFile.prefix}\n\twith sha [${sha.joinToString(", ")}]")

                        UserFileInfo(userFile.root, userFile.path, it.name, Files.getLastModifiedTime(it).toMillis(), sha)
                    }.collect(Collectors.toList())

                    Paths.get(userFile.prefix).pathString to files
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

        // val prootTimestampToDate: (Long) -> Date = { originalTimestamp ->
        //     val androidTimestamp = System.currentTimeMillis()
        //     val prootTimestamp = getProotTime(steamInstance)
        //     val timeDifference = androidTimestamp - prootTimestamp
        //     val adjustedTimestamp = originalTimestamp + timeDifference
        //
        //     Timber.i("Android: $androidTimestamp, PRoot: $prootTimestamp, $originalTimestamp -> $adjustedTimestamp")
        //
        //     Date(adjustedTimestamp)
        // }

        val downloadFiles: (AppFileChangeList, CoroutineScope) -> Deferred<UserFilesDownloadResult> = { fileList, parentScope ->
            parentScope.async {
                var filesDownloaded = 0
                var bytesDownloaded = 0L

                // val convertedPrefixes = convertPrefixes(fileList)

                fileList.files.forEach { file ->
                    val prefixedPath = getFilePrefixPath(file, fileList)
                    val actualFilePath = getFullFilePath(file, fileList)

                    Timber.i("$prefixedPath -> $actualFilePath")

                    val fileDownloadInfo = steamCloud.clientFileDownload(appInfo.id, prefixedPath).await()

                    if (fileDownloadInfo.urlHost.isNotEmpty()) {
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
                            return@forEach
                        }

                        try {
                            val copyToFile: (InputStream) -> Unit = { input ->
                                Files.createDirectories(actualFilePath.parent)

                                FileOutputStream(actualFilePath.toString()).use { fs ->
                                    val bytesRead = input.copyTo(fs)

                                    if (bytesRead != fileDownloadInfo.rawFileSize.toLong()) {
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
                        }

                        response.close()
                    } else {
                        Timber.w("URL host of $prefixedPath was empty")
                    }
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

                filesToUpload.map { it.second }.forEach { file ->
                    val absFilePath = file.getAbsPath(prefixToPath)

                    val fileSize = Files.size(absFilePath).toInt()

                    Timber.i("Beginning upload of ${file.prefixPath} whose timestamp is ${file.timestamp}")

                    val uploadInfo = steamCloud.beginFileUpload(
                        appId = appInfo.id,
                        filename = file.prefixPath,
                        fileSize = fileSize,
                        rawFileSize = fileSize,
                        fileSha = file.sha,
                        // timestamp = prootTimestampToDate(file.timestamp),
                        timestamp = Date(file.timestamp),
                        uploadBatchId = uploadBatchResponse.batchID,
                    ).await()

                    var uploadFileSuccess = true

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

                            val mediaType = "application/octet-stream".toMediaTypeOrNull()

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
                        filename = file.prefixPath,
                    ).await()

                    Timber.i("File ${file.prefixPath} commit success: $commitSuccess")
                }

                steamCloud.completeAppUploadBatch(
                    appId = appInfo.id,
                    batchId = uploadBatchResponse.batchID,
                    batchEResult = if (uploadBatchSuccess) EResult.OK else EResult.Fail,
                ).await()

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
                                remoteTimestamp = appFileListChange.files.map { it.timestamp.time }.max()
                                localTimestamp = allLocalUserFiles.map { it.timestamp }.max()
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
