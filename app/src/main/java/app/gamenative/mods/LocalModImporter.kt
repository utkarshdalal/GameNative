package app.gamenative.mods

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LocalModSourceType(val installSource: ModInstallSource) {
    ARCHIVE(ModInstallSource.LOCAL_ARCHIVE),
    FILES(ModInstallSource.LOCAL_FILES),
    FOLDER(ModInstallSource.LOCAL_FOLDER),
    ;

    companion object {
        fun fromInstallSource(source: String): LocalModSourceType? =
            entries.firstOrNull { it.installSource.name == source }
    }
}

data class LocalModSourceSelection(
    val type: LocalModSourceType,
    val uris: List<Uri>,
    val displayName: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val directoryCount: Int = 0,
)

data class LocalModImportRequest(
    val installId: String,
    val appId: String,
    val sourceType: LocalModSourceType,
    val modName: String,
    val sourceName: String,
    val version: String = "",
    val sizeBytes: Long = 0L,
)

class DuplicateLocalModContentException(
    val existingInstall: ModInstall,
) : IOException()

class LocalModSourceRequiredException(message: String) : IOException(message)
internal class InvalidLocalModArchiveException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

internal fun String.truncateAtCodePointBoundary(maxLength: Int): String {
    val endIndex = maxLength.coerceIn(0, length)
    val splitsPair = endIndex in 1 until length &&
        Character.isHighSurrogate(this[endIndex - 1]) &&
        Character.isLowSurrogate(this[endIndex])
    return substring(0, if (splitsPair) endIndex - 1 else endIndex)
}

object LocalModImporter {
    internal const val MAX_MOD_NAME_LENGTH = 200
    internal const val MAX_VERSION_LENGTH = 100
    private const val MAX_SOURCE_NAME_LENGTH = 1_024
    private const val COPY_BUFFER_SIZE = 256 * 1024

    // Safety ceilings for provider traversal, Binder payloads, path handling, and disk expansion.
    // Keep the archive-related limits aligned with ModArchiveExtractor.
    private const val MAX_LOOSE_FILES = 1_000
    private const val MAX_TREE_ENTRIES = ModImportSafetyLimits.MAX_ENTRIES
    private const val MAX_TREE_DEPTH = ModImportSafetyLimits.MAX_DIRECTORY_DEPTH
    private const val MAX_CONTENT_BYTES = ModImportSafetyLimits.MAX_CONTENT_BYTES
    private const val MAX_RELATIVE_PATH_LENGTH = ModImportSafetyLimits.MAX_RELATIVE_PATH_LENGTH
    private const val MAX_SOURCE_URI_PAYLOAD_CHARS = 128 * 1024

    private val knownArchiveExtensions = setOf("zip", "7z", "rar", "exe")
    private val invalidWindowsNameCharacters = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]")
    private val reservedWindowsName = Regex(
        "^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val safeAppId = Regex("[A-Za-z0-9._-]{1,160}")
    private val safeInstallId = Regex("local_[A-Za-z0-9._-]{1,160}")
    private val contentRegistrationMutex = Mutex()

    private data class SourceEntry(
        val uri: Uri,
        val relativePath: String,
        val directory: Boolean,
        val declaredSize: Long?,
    )

    private data class ContentCopyResult(
        val copiedBytes: Long,
        val fingerprint: String,
    )

    private data class DocumentMetadata(
        val displayName: String,
        val sizeBytes: Long?,
    )

    private data class FolderChild(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
    )

    suspend fun inspectArchive(context: Context, uri: Uri): LocalModSourceSelection =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val metadata = queryOpenDocument(context, uri)
            openLocalInputStream(
                context,
                uri,
                IOException(context.getString(R.string.local_mod_unreadable)),
            )?.use { input ->
                currentCoroutineContext().ensureActive()
                val firstByte = try {
                    input.read()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    throw IOException(context.getString(R.string.local_mod_unreadable), error)
                }
                if (firstByte < 0) throw IOException(context.getString(R.string.local_mod_empty))
            } ?: throw IOException(context.getString(R.string.local_mod_unreadable))
            LocalModSourceSelection(
                type = LocalModSourceType.ARCHIVE,
                uris = listOf(uri),
                displayName = metadata.displayName,
                sizeBytes = metadata.sizeBytes ?: 0L,
                fileCount = 1,
            )
        }

    suspend fun inspectFiles(context: Context, uris: List<Uri>): LocalModSourceSelection =
        withContext(Dispatchers.IO) {
            val inspectionContext = currentCoroutineContext()
            val entries = enumerateLooseFiles(context, uris) { inspectionContext.ensureActive() }
            val uniqueUris = entries.map(SourceEntry::uri)
            LocalModSourceSelection(
                type = LocalModSourceType.FILES,
                uris = uniqueUris,
                displayName = if (entries.size == 1) {
                    entries.single().relativePath
                } else {
                    context.getString(R.string.local_mod_selected_files, entries.size)
                },
                sizeBytes = declaredTotalBytes(context, entries),
                fileCount = entries.size,
            )
        }

    suspend fun inspectFolder(context: Context, treeUri: Uri): LocalModSourceSelection =
        withContext(Dispatchers.IO) {
            val inspectionContext = currentCoroutineContext()
            val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
                .getOrElse { throw IOException(context.getString(R.string.local_mod_unreadable), it) }
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)
            val rootName = queryTreeDocument(context, rootUri).displayName
            val entries = enumerateFolder(context, treeUri) { inspectionContext.ensureActive() }
            val files = entries.filterNot(SourceEntry::directory)
            if (files.isEmpty()) throw IOException(context.getString(R.string.local_mod_empty_folder))
            LocalModSourceSelection(
                type = LocalModSourceType.FOLDER,
                uris = listOf(treeUri),
                displayName = rootName,
                sizeBytes = declaredTotalBytes(context, files),
                fileCount = files.size,
                directoryCount = entries.count(SourceEntry::directory),
            )
        }

    fun suggestedModName(
        selection: LocalModSourceSelection,
        fallbackName: String,
    ): String =
        suggestedModName(
            selection.displayName,
            selection.type,
            selection.fileCount,
            fallbackName,
        )

    internal fun suggestedModName(
        displayName: String,
        type: LocalModSourceType,
        fileCount: Int,
        fallbackName: String,
    ): String = when (type) {
        LocalModSourceType.ARCHIVE -> suggestedNameForFile(displayName, stripKnownArchiveOnly = true)
        LocalModSourceType.FOLDER -> displayName.trim().ifBlank { fallbackName }
        LocalModSourceType.FILES -> if (fileCount == 1) {
            suggestedNameForFile(displayName, stripKnownArchiveOnly = false)
        } else {
            fallbackName
        }
    }

    fun supportedArchiveLabel(displayName: String): String =
        displayName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .takeIf(knownArchiveExtensions::contains)
            ?.uppercase(Locale.US)
            .orEmpty()

    internal fun validateImportRequest(
        context: Context,
        request: LocalModImportRequest,
        sourceUris: List<Uri>,
        requireSource: Boolean,
    ): List<Uri> {
        if (!hasValidRequestMetadata(request)) {
            throw IOException(context.getString(R.string.local_mod_invalid_request))
        }
        if (request.sizeBytes < 0L || request.sizeBytes > MAX_CONTENT_BYTES) {
            throw IOException(context.getString(R.string.local_mod_too_large))
        }
        val normalizedUris = sourceUris.distinct()
        if (requireSource && normalizedUris.isEmpty()) {
            throw LocalModSourceRequiredException(context.getString(R.string.local_mod_reselect))
        }
        when (request.sourceType) {
            LocalModSourceType.ARCHIVE,
            LocalModSourceType.FOLDER,
            -> if (normalizedUris.size > 1) {
                val message = if (request.sourceType == LocalModSourceType.ARCHIVE) {
                    R.string.local_mod_archive_single
                } else {
                    R.string.local_mod_folder_single
                }
                throw IOException(context.getString(message))
            }
            LocalModSourceType.FILES -> if (normalizedUris.size > MAX_LOOSE_FILES) {
                throw IOException(context.getString(R.string.local_mod_too_many_files, MAX_LOOSE_FILES))
            }
        }
        validateUriPayload(context, normalizedUris)
        return normalizedUris
    }

    internal fun hasValidRequestMetadata(request: LocalModImportRequest): Boolean {
        val normalizedInstallId = request.installId.lowercase(Locale.US)
        return safeAppId.matches(request.appId) &&
            request.appId != "." &&
            request.appId != ".." &&
            safeInstallId.matches(request.installId) &&
            !normalizedInstallId.endsWith(".tmp") &&
            !normalizedInstallId.endsWith(".previous") &&
            request.modName.isNotBlank() &&
            request.modName.length <= MAX_MOD_NAME_LENGTH &&
            request.modName.none(Char::isISOControl) &&
            request.version.length <= MAX_VERSION_LENGTH &&
            request.version.none(Char::isISOControl) &&
            request.sourceName.isNotBlank() &&
            request.sourceName.length <= MAX_SOURCE_NAME_LENGTH &&
            request.sourceName.none(Char::isISOControl)
    }

    internal fun isCompatibleExistingInstall(
        existing: ModInstall?,
        request: LocalModImportRequest,
    ): Boolean =
        existing == null ||
            (
                existing.appId == request.appId &&
                    existing.source == request.sourceType.installSource.name
                )

    suspend fun importMod(
        context: Context,
        request: LocalModImportRequest,
        sourceUris: List<Uri>,
        onDetailedProgress: (ModImportProgress) -> Unit = {},
    ): ModInstall = withContext(Dispatchers.IO) {
        val importContext = currentCoroutineContext()
        val ensureNotCanceled = {
            importContext.ensureActive()
            checkCanceled(context, request.installId)
        }
        val normalizedSourceUris = validateImportRequest(
            context = context,
            request = request,
            sourceUris = sourceUris,
            requireSource = false,
        )
        when (ModDownloadRegistry.tryStart(request.installId, request.appId, request.modName)) {
            ModImportStartResult.CANCELED_BEFORE_START -> throw ModImportCanceledException(
                context.getString(R.string.nexus_import_canceled),
            )
            ModImportStartResult.ALREADY_ACTIVE -> throw IOException(
                context.getString(R.string.local_mod_already_importing),
            )
            ModImportStartResult.STARTED -> Unit
        }
        try {
            val dao = NexusModManager.dao(context)
            val root = NexusModManager.cacheRoot(context, request.appId)
            val archiveDir = File(root, "archives").apply { mkdirs() }
            val archiveFile = File(archiveDir, safeFileName("${request.installId}_${request.sourceName}"))
            val stagedArchiveFile = File(archiveDir, "${archiveFile.name}.part")
            val extractDir = File(root, "extracted/${request.installId}")
            val tempExtractDir = File(root, "extracted/${request.installId}.tmp")
            val previousInstall = dao.getInstall(request.installId)
            if (!isCompatibleExistingInstall(previousInstall, request)) {
                throw IOException(context.getString(R.string.local_mod_invalid_request))
            }
            if (normalizedSourceUris.isEmpty() && previousInstall == null) {
                throw LocalModSourceRequiredException(context.getString(R.string.local_mod_reselect))
            }
            val stagedCompletedBytes = when (request.sourceType) {
                LocalModSourceType.ARCHIVE -> stagedArchiveFile.length().takeIf { bytes ->
                    NexusImportState.hasCompletedDownload(previousInstall, bytes)
                }
                LocalModSourceType.FILES,
                LocalModSourceType.FOLDER,
                -> recoverInterruptedPromotion(
                    context = context,
                    previousInstall = previousInstall,
                    stagedContent = tempExtractDir,
                    promotedContent = extractDir,
                )
            }
            val restorablePreviousInstall = NexusModManager.restorePreviousLocalInstall(
                context = context,
                install = previousInstall,
                failureMessage = context.getString(R.string.local_mod_import_failed),
            )
            var activeImport = ModInstall(
                installId = request.installId,
                appId = request.appId,
                source = request.sourceType.installSource.name,
                modName = request.modName.trim(),
                fileName = request.sourceName,
                version = request.version.trim(),
                sizeBytes = request.sizeBytes,
                archivePath = if (request.sourceType == LocalModSourceType.ARCHIVE) {
                    archiveFile.absolutePath
                } else {
                    ""
                },
                extractedPath = extractDir.absolutePath,
                enabled = restorablePreviousInstall?.enabled ?: true,
                status = ModInstallStatus.IMPORTING.name,
                createdAt = previousInstall?.createdAt ?: System.currentTimeMillis(),
                metadataJson = NexusImportState.importMetadata("", restorablePreviousInstall),
                archiveSha256 = previousInstall?.archiveSha256.orEmpty(),
            )
            stagedCompletedBytes?.let { completedBytes ->
                activeImport = NexusImportState.markDownloadComplete(activeImport, completedBytes)
            }
            dao.upsertInstall(activeImport)

            suspend fun recordTerminal(status: ModInstallStatus, message: String) {
                dao.upsertInstall(
                    NexusImportState.terminalInstall(
                        importing = activeImport,
                        summary = "",
                        status = status,
                        message = message,
                        previousInstall = restorablePreviousInstall,
                    ),
                )
            }

            var archiveSnapshotComplete =
                request.sourceType == LocalModSourceType.ARCHIVE && stagedCompletedBytes != null
            try {
                ensureNotCanceled()
                val copied = if (normalizedSourceUris.isEmpty()) {
                    null
                } else {
                    when (request.sourceType) {
                        LocalModSourceType.ARCHIVE -> {
                            stagedArchiveFile.delete()
                            copyArchive(
                                context,
                                request,
                                normalizedSourceUris.single(),
                                stagedArchiveFile,
                                onDetailedProgress,
                                ensureNotCanceled,
                            )
                        }
                        LocalModSourceType.FILES,
                        LocalModSourceType.FOLDER,
                        -> copyContentSnapshot(
                            context,
                            request,
                            normalizedSourceUris,
                            tempExtractDir,
                            onDetailedProgress,
                            ensureNotCanceled,
                        )
                    }
                }
                if (copied == null) {
                    if (stagedCompletedBytes == null) {
                        throw LocalModSourceRequiredException(
                            context.getString(R.string.local_mod_reselect),
                        )
                    }
                } else {
                    activeImport = NexusImportState.markDownloadComplete(
                        activeImport.copy(
                            sizeBytes = copied.copiedBytes,
                            archiveSha256 = copied.fingerprint,
                            updatedAt = System.currentTimeMillis(),
                        ),
                        copied.copiedBytes,
                    )
                    dao.upsertInstall(activeImport)
                    archiveSnapshotComplete = request.sourceType == LocalModSourceType.ARCHIVE
                }

                ensureNotCanceled()
                contentRegistrationMutex.withLock {
                    ensureNotCanceled()
                    rejectDuplicate(
                        dao,
                        activeImport,
                        restorablePreviousInstall,
                        stagedArchiveFile,
                        tempExtractDir,
                    )
                    ensureNotCanceled()
                    when (request.sourceType) {
                        LocalModSourceType.ARCHIVE -> LocalModImportPipeline.extractAndRegister(
                            context = context,
                            importing = activeImport,
                            stagedArchiveFile = stagedArchiveFile,
                            extractDir = extractDir,
                            tempExtractDir = tempExtractDir,
                            onDetailedProgress = onDetailedProgress,
                            ensureNotCanceled = ensureNotCanceled,
                        )
                        LocalModSourceType.FILES,
                        LocalModSourceType.FOLDER,
                        -> LocalModImportPipeline.registerStagedContent(
                            context = context,
                            importing = activeImport,
                            stagedContentDir = tempExtractDir,
                            extractDir = extractDir,
                            ensureNotCanceled = ensureNotCanceled,
                        )
                    }
                }
            } catch (e: DuplicateLocalModContentException) {
                throw e
            } catch (e: OutOfMemoryError) {
                tempExtractDir.deleteRecursively()
                val failure = IOException(context.getString(R.string.nexus_archive_memory_error), e)
                recordTerminal(ModInstallStatus.ERROR, failure.message.orEmpty())
                throw failure
            } catch (e: ModImportCanceledException) {
                tempExtractDir.deleteRecursively()
                stagedArchiveFile.delete()
                recordTerminal(
                    ModInstallStatus.CANCELED,
                    e.message ?: context.getString(R.string.nexus_import_canceled),
                )
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tempExtractDir.deleteRecursively()
                if (
                    request.sourceType != LocalModSourceType.ARCHIVE ||
                    !archiveSnapshotComplete ||
                    shouldDiscardStagedArchive(e)
                ) {
                    stagedArchiveFile.delete()
                }
                val message = NexusImportState.userMessage(
                    error = e,
                    fallback = context.getString(R.string.local_mod_import_failed),
                )
                recordTerminal(ModInstallStatus.ERROR, message)
                throw IOException(message, e)
            }
        } finally {
            ModDownloadRegistry.finish(request.installId)
        }
    }

    private suspend fun rejectDuplicate(
        dao: app.gamenative.db.dao.ModDao,
        importing: ModInstall,
        restorablePreviousInstall: ModInstall?,
        stagedArchiveFile: File,
        tempExtractDir: File,
    ) {
        importing.archiveSha256.takeIf(String::isNotBlank)?.let { fingerprint ->
            dao.getLocalInstallsByContentHash(
                appId = importing.appId,
                archiveSha256 = fingerprint,
                excludingInstallId = importing.installId,
                reusableStatuses = NexusImportState.reusableStatuses,
            )
        }?.firstOrNull { existing ->
            NexusModManager.hasUsableExtractedContent(File(existing.extractedPath))
        }?.let { existing ->
            if (restorablePreviousInstall != null) {
                dao.upsertInstall(restorablePreviousInstall)
            } else {
                dao.deleteInstall(importing.installId)
            }
            stagedArchiveFile.delete()
            tempExtractDir.deleteRecursively()
            throw DuplicateLocalModContentException(existing)
        }
    }

    private fun copyArchive(
        context: Context,
        request: LocalModImportRequest,
        sourceUri: Uri,
        stagedArchiveFile: File,
        onDetailedProgress: (ModImportProgress) -> Unit,
        ensureNotCanceled: () -> Unit,
    ): ContentCopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val copiedBytes = copyUri(
            context = context,
            request = request,
            sourceUri = sourceUri,
            destination = stagedArchiveFile,
            digest = digest,
            copiedBefore = 0L,
            unreadableError = LocalModSourceRequiredException(
                context.getString(R.string.local_mod_unreadable),
            ),
            syncOutput = true,
            onDetailedProgress = onDetailedProgress,
            ensureNotCanceled = ensureNotCanceled,
        )
        if (copiedBytes <= 0L) throw IOException(context.getString(R.string.local_mod_empty))
        return ContentCopyResult(copiedBytes, digest.hexDigest())
    }

    internal fun recoverInterruptedPromotion(
        context: Context,
        previousInstall: ModInstall?,
        stagedContent: File,
        promotedContent: File,
    ): Long? {
        val failureMessage = context.getString(R.string.local_mod_import_failed)
        fun completedBytes(content: File): Long? {
            if (!content.isDirectory || content.walkTopDown().none(File::isFile)) return null
            val bytes = NexusModManager.directorySize(content)
            return bytes.takeIf {
                NexusImportState.hasCompletedLocalSnapshot(previousInstall, bytes)
            }
        }

        val restorablePrevious =
            NexusImportState.restorablePreviousInstall(previousInstall) != null
        val previousContent = File("${promotedContent.absolutePath}.previous")

        fun restorePreviousContent() {
            if (!restorablePrevious) {
                if (promotedContent.exists() && !promotedContent.deleteRecursively()) {
                    throw IOException(failureMessage)
                }
                return
            }
            if (!previousContent.isDirectory) {
                if (promotedContent.exists()) return
                throw IOException(failureMessage)
            }
            if (promotedContent.exists() && !promotedContent.deleteRecursively()) {
                throw IOException(failureMessage)
            }
            if (!previousContent.renameTo(promotedContent)) {
                throw IOException(failureMessage)
            }
        }

        completedBytes(stagedContent)?.let { bytes ->
            restorePreviousContent()
            return bytes
        }
        val promotedBytes = completedBytes(promotedContent) ?: return null
        if (restorablePrevious && !previousContent.isDirectory) {
            return null
        }
        if (stagedContent.exists() && !stagedContent.deleteRecursively()) {
            throw IOException(failureMessage)
        }
        stagedContent.parentFile?.mkdirs()
        if (!promotedContent.renameTo(stagedContent)) {
            throw IOException(failureMessage)
        }
        restorePreviousContent()
        return promotedBytes
    }

    private fun copyContentSnapshot(
        context: Context,
        request: LocalModImportRequest,
        sourceUris: List<Uri>,
        destination: File,
        onDetailedProgress: (ModImportProgress) -> Unit,
        ensureNotCanceled: () -> Unit,
    ): ContentCopyResult {
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException(context.getString(R.string.local_mod_import_failed))
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException(context.getString(R.string.local_mod_import_failed))
        }
        val entries = when (request.sourceType) {
            LocalModSourceType.FILES -> enumerateLooseFiles(context, sourceUris, ensureNotCanceled)
            LocalModSourceType.FOLDER -> {
                if (sourceUris.size != 1) {
                    throw IOException(context.getString(R.string.local_mod_folder_single))
                }
                enumerateFolder(context, sourceUris.single(), ensureNotCanceled)
            }
            LocalModSourceType.ARCHIVE ->
                throw IOException(context.getString(R.string.local_mod_invalid_request))
        }.sortedBy { it.relativePath.lowercase(Locale.US) }
        if (entries.none { !it.directory }) {
            throw IOException(context.getString(R.string.local_mod_no_files))
        }

        val root = destination.canonicalFile
        val contentDigest = MessageDigest.getInstance("SHA-256")
        var copiedBytes = 0L
        entries.forEach { entry ->
            ensureNotCanceled()
            val outputFile = File(root, entry.relativePath).canonicalFile
            if (outputFile != root && !outputFile.path.startsWith(root.path + File.separator)) {
                throw IOException(
                    context.getString(R.string.local_mod_unsafe_path, entry.relativePath),
                )
            }
            if (entry.directory) {
                if (!outputFile.mkdirs() && !outputFile.isDirectory) {
                    throw IOException(context.getString(R.string.local_mod_import_failed))
                }
                updateFingerprintHeader(contentDigest, 'D', entry.relativePath, 0L)
            } else {
                outputFile.parentFile?.let { parent ->
                    if (!parent.mkdirs() && !parent.isDirectory) {
                        throw IOException(context.getString(R.string.local_mod_import_failed))
                    }
                }
                val fileDigest = MessageDigest.getInstance("SHA-256")
                val copiedBefore = copiedBytes
                copiedBytes = copyUri(
                    context = context,
                    request = request,
                    sourceUri = entry.uri,
                    destination = outputFile,
                    digest = fileDigest,
                    copiedBefore = copiedBefore,
                    unreadableError = IOException(
                        context.getString(
                            R.string.local_mod_unreadable_file,
                            entry.relativePath,
                        ),
                    ),
                    onDetailedProgress = onDetailedProgress,
                    ensureNotCanceled = ensureNotCanceled,
                )
                val fileBytes = copiedBytes - copiedBefore
                updateFingerprintHeader(contentDigest, 'F', entry.relativePath, fileBytes)
                contentDigest.update(fileDigest.digest())
            }
        }
        return ContentCopyResult(copiedBytes, contentDigest.hexDigest())
    }

    private fun copyUri(
        context: Context,
        request: LocalModImportRequest,
        sourceUri: Uri,
        destination: File,
        digest: MessageDigest,
        copiedBefore: Long,
        unreadableError: IOException,
        syncOutput: Boolean = false,
        onDetailedProgress: (ModImportProgress) -> Unit,
        ensureNotCanceled: () -> Unit,
    ): Long {
        var copiedBytes = copiedBefore
        openLocalInputStream(context, sourceUri, unreadableError)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    ensureNotCanceled()
                    val read = try {
                        input.read(buffer)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        throw IOException(unreadableError.message, error)
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copiedBytes = safeAddBytes(context, copiedBytes, read.toLong())
                    reportCopyProgress(request, copiedBytes, onDetailedProgress)
                }
                if (syncOutput) output.fd.sync()
            }
        } ?: throw unreadableError
        return copiedBytes
    }

    private fun openLocalInputStream(
        context: Context,
        uri: Uri,
        unreadableError: IOException,
    ) = try {
        context.contentResolver.openInputStream(uri)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        throw IOException(unreadableError.message, error)
    }

    private fun enumerateLooseFiles(
        context: Context,
        uris: List<Uri>,
        ensureNotCanceled: () -> Unit = {},
    ): List<SourceEntry> {
        val uniqueUris = uris.distinct()
        if (uniqueUris.isEmpty()) throw IOException(context.getString(R.string.local_mod_no_files))
        if (uniqueUris.size > MAX_LOOSE_FILES) {
            throw IOException(context.getString(R.string.local_mod_too_many_files, MAX_LOOSE_FILES))
        }
        validateUriPayload(context, uniqueUris)
        return uniqueUris.map { uri ->
            ensureNotCanceled()
            val metadata = queryOpenDocument(context, uri)
            SourceEntry(
                uri = uri,
                relativePath = normalizeRelativePath(context, listOf(metadata.displayName)),
                directory = false,
                declaredSize = metadata.sizeBytes,
            )
        }.also { validateUniquePaths(context, it.map(SourceEntry::relativePath)) }
    }

    private fun enumerateFolder(
        context: Context,
        treeUri: Uri,
        ensureNotCanceled: () -> Unit = {},
    ): List<SourceEntry> {
        val resolver = context.contentResolver
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException(context.getString(R.string.local_mod_unreadable), it) }
        val entries = mutableListOf<SourceEntry>()
        val normalizedPaths = mutableSetOf<String>()
        val visitedDirectories = mutableSetOf<String>()

        fun visit(parentDocumentId: String, parentSegments: List<String>, depth: Int) {
            ensureNotCanceled()
            if (depth > MAX_TREE_DEPTH) {
                throw IOException(context.getString(R.string.local_mod_too_deep, MAX_TREE_DEPTH))
            }
            if (!visitedDirectories.add(parentDocumentId)) {
                throw IOException(context.getString(R.string.local_mod_folder_cycle))
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            val childrenCursor = try {
                resolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                throw IOException(context.getString(R.string.local_mod_unreadable), error)
            }
            val children = try {
                childrenCursor?.use { cursor ->
                    val idColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    )
                    val nameColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                    val mimeColumn = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    )
                    if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                        throw IOException(context.getString(R.string.local_mod_unreadable))
                    }
                    val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val result = mutableListOf<FolderChild>()
                    while (cursor.moveToNext()) {
                        ensureNotCanceled()
                        if (entries.size + result.size >= MAX_TREE_ENTRIES) {
                            throw IOException(
                                context.getString(
                                    R.string.local_mod_too_many_entries,
                                    MAX_TREE_ENTRIES,
                                ),
                            )
                        }
                        val documentId = cursor.getString(idColumn)
                        val displayName = cursor.getString(nameColumn).orEmpty()
                        val mimeType = cursor.getString(mimeColumn).orEmpty()
                        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (!isDirectory && sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            cursor.getLong(sizeColumn).coerceAtLeast(0L)
                        } else {
                            null
                        }
                        result += FolderChild(documentId, displayName, mimeType, size)
                    }
                    result
                }
            } catch (error: Exception) {
                if (error is CancellationException || error is IOException) throw error
                throw IOException(context.getString(R.string.local_mod_unreadable), error)
            } ?: throw IOException(context.getString(R.string.local_mod_unreadable))

            children.forEach { child ->
                ensureNotCanceled()
                if (entries.size >= MAX_TREE_ENTRIES) {
                    throw IOException(
                        context.getString(
                            R.string.local_mod_too_many_entries,
                            MAX_TREE_ENTRIES,
                        ),
                    )
                }
                val segments = parentSegments + child.displayName
                val relativePath = normalizeRelativePath(context, segments)
                if (!normalizedPaths.add(relativePath.lowercase(Locale.US))) {
                    throw IOException(
                        context.getString(R.string.local_mod_path_collision, relativePath),
                    )
                }
                val documentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    child.documentId,
                )
                val isDirectory = child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                entries += SourceEntry(
                    uri = documentUri,
                    relativePath = relativePath,
                    directory = isDirectory,
                    declaredSize = child.sizeBytes,
                )
                if (isDirectory) visit(child.documentId, segments, depth + 1)
            }
        }

        visit(rootDocumentId, emptyList(), 0)
        validateUniquePaths(context, entries.map(SourceEntry::relativePath))
        return entries
    }

    private fun queryOpenDocument(context: Context, uri: Uri): DocumentMetadata =
        queryDocumentMetadata(
            context = context,
            uri = uri,
            displayNameColumn = OpenableColumns.DISPLAY_NAME,
            sizeColumn = OpenableColumns.SIZE,
            fallbackName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.local_mod_default_name),
        )

    private fun queryTreeDocument(context: Context, uri: Uri): DocumentMetadata =
        queryDocumentMetadata(
            context = context,
            uri = uri,
            displayNameColumn = DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            sizeColumn = DocumentsContract.Document.COLUMN_SIZE,
            fallbackName = context.getString(R.string.local_mod_selected_folder),
        )

    private fun queryDocumentMetadata(
        context: Context,
        uri: Uri,
        displayNameColumn: String,
        sizeColumn: String,
        fallbackName: String,
    ): DocumentMetadata {
        var displayName: String? = null
        var sizeBytes: Long? = null
        val metadataCursor = try {
            context.contentResolver.query(
                uri,
                arrayOf(displayNameColumn, sizeColumn),
                null,
                null,
                null,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IOException(context.getString(R.string.local_mod_unreadable), error)
        }
        try {
            metadataCursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(displayNameColumn)
                    val sizeIndex = cursor.getColumnIndex(sizeColumn)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException || error is IOException) throw error
            throw IOException(context.getString(R.string.local_mod_unreadable), error)
        }
        return DocumentMetadata(
            displayName = displayName?.takeIf(String::isNotBlank) ?: fallbackName,
            sizeBytes = sizeBytes,
        )
    }

    internal fun normalizeRelativePath(segments: List<String>): String =
        normalizeRelativePath(context = null, segments = segments)

    private fun normalizeRelativePath(context: Context?, segments: List<String>): String {
        if (segments.isEmpty()) throw unsafePath(context, "")
        val normalized = segments.joinToString("/") { segment ->
            val trimmed = segment.trim()
            if (
                segment != trimmed ||
                trimmed.isBlank() ||
                trimmed == "." ||
                trimmed == ".." ||
                reservedWindowsName.matches(trimmed) ||
                invalidWindowsNameCharacters.containsMatchIn(trimmed) ||
                trimmed.endsWith('.') ||
                trimmed.length > 255
            ) {
                throw unsafePath(context, segment)
            }
            trimmed
        }
        if (normalized.length > MAX_RELATIVE_PATH_LENGTH) {
            throw unsafePath(context, normalized)
        }
        return normalized
    }

    internal fun validateUniquePaths(paths: List<String>) =
        validateUniquePaths(context = null, paths = paths)

    private fun validateUniquePaths(context: Context?, paths: List<String>) {
        val seen = mutableSetOf<String>()
        paths.forEach { path ->
            if (!seen.add(path.lowercase(Locale.US))) {
                throw IOException(
                    context?.getString(R.string.local_mod_path_collision, path)
                        ?: "Duplicate local mod path: $path",
                )
            }
        }
    }

    private fun declaredTotalBytes(context: Context, entries: List<SourceEntry>): Long {
        if (entries.any { !it.directory && it.declaredSize == null }) return 0L
        return entries.filterNot(SourceEntry::directory).fold(0L) { total, entry ->
            safeAddBytes(context, total, entry.declaredSize ?: 0L)
        }
    }

    private fun safeAddBytes(context: Context, current: Long, additional: Long): Long {
        if (additional < 0L || current > MAX_CONTENT_BYTES - additional) {
            throw IOException(context.getString(R.string.local_mod_too_large))
        }
        return current + additional
    }

    private fun unsafePath(context: Context?, path: String): IOException =
        IOException(
            context?.getString(R.string.local_mod_unsafe_path, path)
                ?: "Unsupported or unsafe local mod path: $path",
        )

    private fun validateUriPayload(context: Context, uris: List<Uri>) {
        var payloadChars = 0L
        uris.forEach { uri ->
            if (uri.scheme != "content") {
                throw IOException(context.getString(R.string.local_mod_unreadable))
            }
            payloadChars += uri.toString().length
            if (payloadChars > MAX_SOURCE_URI_PAYLOAD_CHARS) {
                throw IOException(context.getString(R.string.local_mod_uri_payload_too_large))
            }
        }
    }

    private fun reportCopyProgress(
        request: LocalModImportRequest,
        copiedBytes: Long,
        onDetailedProgress: (ModImportProgress) -> Unit,
    ) {
        val total = request.sizeBytes.takeIf { it > 0L } ?: 0L
        val progress = if (total > 0L) {
            (copiedBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val detail = ModImportProgress("Copying", progress, copiedBytes, total)
        ModDownloadRegistry.update(
            request.installId,
            progress,
            detail.status,
            copiedBytes,
            total,
        )
        onDetailedProgress(detail)
    }

    private fun checkCanceled(context: Context, installId: String) {
        if (ModDownloadRegistry.isCancelRequested(installId)) {
            throw ModImportCanceledException(context.getString(R.string.nexus_import_canceled))
        }
    }

    private fun updateFingerprintHeader(
        digest: MessageDigest,
        type: Char,
        path: String,
        size: Long,
    ) {
        digest.update(type.code.toByte())
        digest.update(0)
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0)
    }

    private fun MessageDigest.hexDigest(): String =
        digest().joinToString("") { "%02x".format(it) }

    private fun suggestedNameForFile(displayName: String, stripKnownArchiveOnly: Boolean): String {
        val trimmed = displayName.trim().substringAfterLast('/').substringAfterLast('\\')
        val extension = trimmed.substringAfterLast('.', "").lowercase(Locale.US)
        val shouldStrip = if (stripKnownArchiveOnly) {
            extension in knownArchiveExtensions
        } else {
            extension.isNotBlank()
        }
        return if (shouldStrip) trimmed.substringBeforeLast('.').ifBlank { trimmed } else trimmed
    }

    internal fun isInvalidArchiveError(error: Throwable): Boolean {
        var current: Throwable? = error
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            if (
                current is InvalidLocalModArchiveException ||
                current is UnsupportedModArchiveException ||
                current is ZipException ||
                current is EOFException
            ) {
                return true
            }
            val message = current.message.orEmpty().lowercase(Locale.US)
            if (
                listOf(
                    "checksum",
                    "corrupt",
                    "truncated",
                    "unexpected end",
                    "end header",
                    "invalid header",
                    "unsafe archive",
                    "archive entry escapes",
                    "entry without a path",
                    "archive has too many entries",
                    "archive expands beyond",
                    "extraction failed",
                    "encrypted",
                    "multipart",
                ).any(message::contains)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun shouldDiscardStagedArchive(error: Exception): Boolean =
        isInvalidArchiveError(error)

    private fun safeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val extension = sanitized.substringAfterLast('.', "")
            .takeIf { it.length in 1..16 && it.all(Char::isLetterOrDigit) }
            ?.let { ".$it" }
            .orEmpty()
        val stem = sanitized
            .removeSuffix(extension)
            .take(180 - extension.length)
            .trimEnd('.', ' ')
            .ifBlank { "local_mod_archive" }
        return (stem + extension).take(180)
    }
}
