package app.gamenative.mods

import android.content.Context
import androidx.room.withTransaction
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Finalizes content already acquired by [LocalModImporter].
 *
 * This deliberately remains local-import-only for now. Keeping the Nexus download path unchanged
 * limits the regression surface while local sources are introduced.
 */
internal object LocalModImportPipeline {
    suspend fun extractAndRegister(
        context: Context,
        importing: ModInstall,
        stagedArchiveFile: File,
        extractDir: File,
        tempExtractDir: File,
        onDetailedProgress: (ModImportProgress) -> Unit,
        ensureNotCanceled: () -> Unit = {},
    ): ModInstall = withContext(Dispatchers.IO) {
        ensureNotCanceled()
        val unpacking = ModImportProgress("Unpacking", progress = 0f)
        ModDownloadRegistry.update(importing.installId, 0f, unpacking.status)
        onDetailedProgress(unpacking)
        val extraction = try {
            ModArchiveExtractor.extract(
                archiveFile = stagedArchiveFile,
                destination = tempExtractDir,
                preservedSingleFileName = importing.fileName,
            ) { extractProgress ->
                ensureNotCanceled()
                val unpackProgress = when {
                    extractProgress.totalBytes > 0L ->
                        (
                            extractProgress.extractedBytes.toFloat() /
                                extractProgress.totalBytes.toFloat()
                            ).coerceIn(0f, 1f)
                    extractProgress.totalEntries > 0 ->
                        (
                            extractProgress.entriesProcessed.toFloat() /
                                extractProgress.totalEntries.toFloat()
                            ).coerceIn(0f, 1f)
                    else -> 0f
                }
                val detail = ModImportProgress(
                    status = "Unpacking",
                    progress = unpackProgress,
                    downloadedBytes = extractProgress.extractedBytes,
                    totalBytes = extractProgress.totalBytes,
                )
                ModDownloadRegistry.update(
                    installId = importing.installId,
                    progress = detail.progress,
                    status = detail.status,
                    downloadedBytes = detail.downloadedBytes,
                    totalBytes = detail.totalBytes,
                )
                onDetailedProgress(detail)
            }
        } catch (error: Exception) {
            if (LocalModImporter.isInvalidArchiveError(error)) {
                throw InvalidLocalModArchiveException(
                    context.getString(R.string.local_mod_invalid_archive),
                    error,
                )
            }
            throw error
        }
        requireUsableArchive(
            extraction.entries,
            context.getString(R.string.local_mod_empty_archive),
        )
        ensureNotCanceled()
        val ready = finalizeWithLocalizedFailure(context, extraction.destination, extractDir) {
            ensureNotCanceled()
            registerReadyInstall(context, importing, extractDir)
        }
        if (stagedArchiveFile.exists() && !stagedArchiveFile.delete()) {
            // Registration already succeeded, so leave this non-fatal. Storage cleanup treats
            // the unreferenced staging file as an orphan and can remove it on a later pass.
            Timber.w("Unable to delete staged local mod archive: ${stagedArchiveFile.path}")
        }
        ready
    }

    suspend fun registerStagedContent(
        context: Context,
        importing: ModInstall,
        stagedContentDir: File,
        extractDir: File,
        ensureNotCanceled: () -> Unit = {},
    ): ModInstall = withContext(Dispatchers.IO) {
        ensureNotCanceled()
        finalizeWithLocalizedFailure(context, stagedContentDir, extractDir) {
            ensureNotCanceled()
            registerReadyInstall(context, importing, extractDir)
        }
    }

    private suspend fun <T> finalizeWithLocalizedFailure(
        context: Context,
        source: File,
        target: File,
        finalize: suspend () -> T,
    ): T = try {
        finalizeExtractedContent(
            source = source,
            target = target,
            failureMessage = context.getString(R.string.local_mod_import_failed),
            finalize = finalize,
        )
    } catch (error: ModImportCanceledException) {
        throw error
    } catch (error: IOException) {
        throw IOException(context.getString(R.string.local_mod_import_failed), error)
    }

    private suspend fun registerReadyInstall(
        context: Context,
        importing: ModInstall,
        extractDir: File,
    ): ModInstall {
        val now = System.currentTimeMillis()
        val ready = importing.copy(
            archivePath = "",
            extractedPath = extractDir.absolutePath,
            status = ModInstallStatus.READY.name,
            downloadedAt = now,
            updatedAt = now,
            metadataJson = NexusImportState.importMetadata(""),
        )
        val database = NexusModManager.database(context)
        database.withTransaction {
            val dao = database.modDao()
            dao.upsertInstall(ready)
            val profile = ModProfileManager.ensureActiveProfile(dao, importing.appId)
            ModProfileManager.ensureStateForInstall(dao, profile, ready.installId)
        }
        return ready
    }

    internal fun requireUsableArchive(
        entries: List<ModArchiveEntry>,
        failureMessage: String,
    ) {
        if (entries.none { !it.directory }) {
            throw InvalidLocalModArchiveException(failureMessage)
        }
    }

    internal suspend fun <T> finalizeExtractedContent(
        source: File,
        target: File,
        failureMessage: String,
        finalize: suspend () -> T,
    ): T {
        val promotion = promoteExtractedContent(source, target, failureMessage)
        return try {
            finalize().also { promotion.commit() }
        } catch (error: Throwable) {
            promotion.rollback(error)
            throw error
        }
    }

    private fun promoteExtractedContent(
        source: File,
        target: File,
        failureMessage: String,
    ): ExtractedContentPromotion {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, "${target.name}.previous")
        if (backup.exists()) {
            // The target may be a partially promoted replacement. The rollback remains
            // authoritative until registration succeeds and commit renames it for deletion.
            if (target.exists() && !target.deleteRecursively()) {
                throw IOException(failureMessage)
            }
            if (!backup.renameTo(target)) {
                throw IOException(failureMessage)
            }
        }
        val hadPreviousTarget = target.exists()
        if (hadPreviousTarget && !target.renameTo(backup)) {
            throw IOException(failureMessage)
        }
        val promotion =
            ExtractedContentPromotion(target, backup, hadPreviousTarget, failureMessage)
        try {
            moveDirectory(source, target, failureMessage)
            return promotion
        } catch (error: Throwable) {
            promotion.rollback(error)
            throw error
        }
    }

    private class ExtractedContentPromotion(
        private val target: File,
        private val backup: File,
        private val hadPreviousTarget: Boolean,
        private val failureMessage: String,
    ) {
        fun commit() {
            if (!backup.exists()) return
            val discardedBackup =
                File(backup.parentFile, "${backup.name}.discard-${System.nanoTime()}")
            if (backup.renameTo(discardedBackup)) {
                discardedBackup.deleteRecursively()
            } else {
                backup.deleteRecursively()
            }
        }

        fun rollback(error: Throwable) {
            if (!target.deleteRecursively()) {
                // Do not overwrite or discard the known-good rollback while the failed
                // promoted target could not be removed.
                error.addSuppressed(IOException(failureMessage))
                return
            }
            if (hadPreviousTarget && !backup.renameTo(target)) {
                error.addSuppressed(IOException(failureMessage))
            }
        }
    }

    private fun moveDirectory(source: File, target: File, failureMessage: String) {
        if (!source.isDirectory) throw IOException(failureMessage)
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (!source.copyRecursively(target, overwrite = true)) {
            throw IOException(failureMessage)
        }
        if (!source.deleteRecursively()) {
            throw IOException(failureMessage)
        }
    }
}
