package app.gamenative.mods

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class LocalModImporterRobolectricTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var provider: FakeModDocumentsProvider

    @Before
    fun registerProvider() {
        provider = FakeModDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test
    fun inspectFolder_preservesRelativeTreeAndTotalsFiles() = runBlocking {
        val selection = LocalModImporter.inspectFolder(
            context,
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
        )

        assertEquals(LocalModSourceType.FOLDER, selection.type)
        assertEquals("Test Mod", selection.displayName)
        assertEquals(3, selection.fileCount)
        assertEquals(1, selection.directoryCount)
        assertEquals(12L, selection.sizeBytes)
    }

    @Test
    fun inspectFiles_acceptsIniAndDllAsOpaqueLooseFiles() = runBlocking {
        val selection = LocalModImporter.inspectFiles(
            context,
            listOf(
                DocumentsContract.buildDocumentUri(AUTHORITY, SETTINGS_ID),
                DocumentsContract.buildDocumentUri(AUTHORITY, DLL_ID),
            ),
        )

        assertEquals(LocalModSourceType.FILES, selection.type)
        assertEquals(2, selection.fileCount)
        assertEquals(10L, selection.sizeBytes)
    }

    @Test
    fun inspectFolder_closesEachProviderCursorBeforeDescending() = runBlocking {
        provider.rejectNestedQueries = true

        val selection = LocalModImporter.inspectFolder(
            context,
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
        )

        assertEquals(3, selection.fileCount)
        assertEquals(0, provider.openCursorCount)
    }

    @Test
    fun inspectFolder_acceptsProvidersThatOmitTheOptionalSizeColumn() = runBlocking {
        provider.omitSizeColumn = true

        val selection = LocalModImporter.inspectFolder(
            context,
            DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
        )

        assertEquals(3, selection.fileCount)
        assertEquals(0L, selection.sizeBytes)
    }

    @Test
    fun inspectFolder_rejectsWindowsCaseInsensitivePathCollision() {
        provider.addCaseCollision = true

        assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImporter.inspectFolder(
                    context,
                    DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
                )
            }
        }
    }

    @Test
    fun inspectFolder_localizesProviderSecurityFailures() {
        provider.failQueries = true

        val error = assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImporter.inspectFolder(
                    context,
                    DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID),
                )
            }
        }

        assertEquals(context.getString(R.string.local_mod_unreadable), error.message)
    }

    @Test
    fun inspectFiles_rejectsAnOversizedUriBinderPayload() {
        val oversizedUri = Uri.parse("content://$AUTHORITY/document/${"x".repeat(140_000)}")

        assertThrows(IOException::class.java) {
            runBlocking {
                LocalModImporter.inspectFiles(context, listOf(oversizedUri))
            }
        }
    }

    @Test
    fun validateImportRequest_rejectsPathLikeIdentifiers() {
        val baseRequest = LocalModImportRequest(
            installId = "local_safe-id",
            appId = "STEAM_123",
            sourceType = LocalModSourceType.FILES,
            modName = "Test mod",
            sourceName = "settings.ini",
        )

        assertThrows(IOException::class.java) {
            LocalModImporter.validateImportRequest(
                context,
                baseRequest.copy(appId = "../outside"),
                emptyList(),
                requireSource = false,
            )
        }
        assertThrows(IOException::class.java) {
            LocalModImporter.validateImportRequest(
                context,
                baseRequest.copy(installId = "local_../outside"),
                emptyList(),
                requireSource = false,
            )
        }
    }

    @Test
    fun pendingLocalArchiveResume_usesPersistedArchivePath() {
        val archive = File(
            NexusModManager.cacheRoot(context, "resume-path-test"),
            "archives/local_test_mod_with_spaces.zip",
        )
        val partial = File(archive.parentFile, "${archive.name}.part")
        partial.parentFile?.mkdirs()
        partial.writeText("data")
        val install = NexusImportState.markDownloadComplete(
            ModInstall(
                installId = "local_test",
                appId = "resume-path-test",
                source = ModInstallSource.LOCAL_ARCHIVE.name,
                modName = "Resume test",
                fileName = "mod with spaces.zip",
                sizeBytes = partial.length(),
                archivePath = archive.absolutePath,
                extractedPath = "",
                status = ModInstallStatus.IMPORTING.name,
            ),
            partial.length(),
        )

        assertEquals(true, NexusModManager.hasCompletePendingLocalContent(context, install))
    }

    @Test
    fun pendingLocalArchiveResume_requiresPersistedCompletionMarker() {
        val archive = File(
            NexusModManager.cacheRoot(context, "incomplete-resume-test"),
            "archives/local_test.zip",
        )
        val partial = File(archive.parentFile, "${archive.name}.part")
        partial.parentFile?.mkdirs()
        partial.writeText("data")
        val install = ModInstall(
            installId = "local_test",
            appId = "incomplete-resume-test",
            source = ModInstallSource.LOCAL_ARCHIVE.name,
            modName = "Incomplete resume test",
            fileName = "test.zip",
            sizeBytes = partial.length(),
            archivePath = archive.absolutePath,
            extractedPath = "",
            status = ModInstallStatus.IMPORTING.name,
        )

        assertFalse(NexusModManager.hasCompletePendingLocalContent(context, install))
    }

    @Test
    fun pendingLocalArchiveUpdate_requiresRestorablePreviousContent() =
        withCleanCacheRoot("archive-update-rollback-test") { root ->
            val appId = "archive-update-rollback-test"
            val archive = root.resolve("archives/local_test.zip")
            val partial = root.file("archives/local_test.zip.part", "archive")
            val previous = localInstall(appId, status = ModInstallStatus.READY).copy(
                source = ModInstallSource.LOCAL_ARCHIVE.name,
                fileName = "local_test.zip",
                archivePath = archive.absolutePath,
            )
            val interrupted = completedImport(
                previous.copy(
                    status = ModInstallStatus.IMPORTING.name,
                    metadataJson = NexusImportState.importMetadata("", previous),
                ),
                partial.length(),
            )

            assertFalse(NexusModManager.hasCompletePendingLocalContent(context, interrupted))

            root.importDirectory(interrupted.installId, files = arrayOf("old.ini" to "old"))
            assertTrue(NexusModManager.hasCompletePendingLocalContent(context, interrupted))
        }

    @Test
    fun previousInstallMetadata_isNotRestoredWithoutPreviousContent() =
        withCleanCacheRoot("missing-previous-content-test") {
            val previous = localInstall(
                appId = "missing-previous-content-test",
                status = ModInstallStatus.READY,
            )
            val interrupted = previous.copy(
                status = ModInstallStatus.ERROR.name,
                metadataJson = NexusImportState.errorMetadata("", "Failed", previous),
            )

            assertNull(
                NexusModManager.restorePreviousLocalInstall(
                    context,
                    interrupted,
                    "Restore failed",
                ),
            )
        }

    @Test
    fun sourceLessResume_doesNotRecreateDeletedInstall() {
        val appId = "deleted-local-resume-test"
        val request = LocalModImportRequest(
            installId = "local_deleted",
            appId = appId,
            sourceType = LocalModSourceType.FILES,
            modName = "Deleted import",
            sourceName = "settings.ini",
        )
        val dao = NexusModManager.dao(context)

        assertThrows(LocalModSourceRequiredException::class.java) {
            runBlocking {
                LocalModImporter.importMod(context, request, emptyList())
            }
        }
        assertNull(runBlocking { dao.getInstall(request.installId) })
    }

    @Test
    fun staleDuplicate_doesNotBlockFreshImport() = runBlocking {
        val appId = "stale-local-duplicate-test"
        withCleanCacheRoot(appId) {
            val dao = NexusModManager.dao(context)
            val sourceUri = DocumentsContract.buildDocumentUri(AUTHORITY, SETTINGS_ID)
            val source = LocalModImporter.inspectFiles(context, listOf(sourceUri))
            val firstRequest = localFilesRequest("local_stale", appId, source)
            val secondRequest = localFilesRequest("local_fresh", appId, source)
            try {
                val stale = LocalModImporter.importMod(context, firstRequest, source.uris)
                File(stale.extractedPath).deleteRecursively()

                val fresh = LocalModImporter.importMod(context, secondRequest, source.uris)

                assertEquals(ModInstallStatus.READY.name, fresh.status)
                assertTrue(File(fresh.extractedPath).resolve("settings.ini").isFile)
                assertNotNull(dao.getInstall(stale.installId))
            } finally {
                dao.deleteInstall(firstRequest.installId)
                dao.deleteInstall(secondRequest.installId)
            }
        }
    }

    @Test
    fun usableDuplicate_stillRejectsFreshImport() = runBlocking {
        val appId = "usable-local-duplicate-test"
        withCleanCacheRoot(appId) {
            val dao = NexusModManager.dao(context)
            val sourceUri = DocumentsContract.buildDocumentUri(AUTHORITY, SETTINGS_ID)
            val source = LocalModImporter.inspectFiles(context, listOf(sourceUri))
            val firstRequest = localFilesRequest("local_existing", appId, source)
            val secondRequest = localFilesRequest("local_duplicate", appId, source)
            try {
                val existing = LocalModImporter.importMod(context, firstRequest, source.uris)
                val error = runCatching {
                    LocalModImporter.importMod(context, secondRequest, source.uris)
                }.exceptionOrNull()

                assertTrue(error is DuplicateLocalModContentException)
                assertEquals(
                    existing.installId,
                    (error as DuplicateLocalModContentException).existingInstall.installId,
                )
                assertNull(dao.getInstall(secondRequest.installId))
            } finally {
                dao.deleteInstall(firstRequest.installId)
                dao.deleteInstall(secondRequest.installId)
            }
        }
    }

    @Test
    fun stagedRetryWithoutRollbackContent_requiresSourceReselection() =
        withCleanCacheRoot("missing-local-rollback-test") { root ->
            val appId = "missing-local-rollback-test"
            val staged = root.importDirectory("local_test", ".tmp", "new.ini" to "new!")
            val interrupted = interruptedReplacement(previousInstall(appId, root))

            assertFalse(NexusModManager.hasCompletePendingLocalContent(context, interrupted))
            assertTrue(staged.isDirectory)
        }

    @Test
    fun interruptedLooseFilePromotion_isRecoveredWithoutReselectingSource() =
        withCleanCacheRoot("promoted-local-resume-test") { root ->
            val appId = "promoted-local-resume-test"
            val staged = root.importPath("local_test", ".tmp")
            val promoted = root.importDirectory("local_test", files = arrayOf("settings.ini" to "data"))
            val install = completedImport(
                localInstall(appId, status = ModInstallStatus.IMPORTING).copy(
                    modName = "Resume test",
                    archiveSha256 = "fingerprint",
                ),
            )

            assertTrue(NexusModManager.hasCompletePendingLocalContent(context, install))
            assertRecovered(install, staged, promoted)
            assertEquals("data", staged.resolve("settings.ini").readText())
            assertFalse(promoted.exists())
        }

    @Test
    fun interruptedLooseFilePromotion_replacesIncompleteStagingDirectory() =
        withCleanCacheRoot("promoted-local-incomplete-staging-test") { root ->
            val appId = "promoted-local-incomplete-staging-test"
            val staged = root.importDirectory("local_test", ".tmp", "partial.ini" to "partial")
            val promoted = root.importDirectory("local_test", files = arrayOf("settings.ini" to "data"))
            val install = completedImport(
                localInstall(appId, status = ModInstallStatus.IMPORTING).copy(
                    modName = "Resume test",
                    archiveSha256 = "fingerprint",
                ),
            )

            assertRecovered(install, staged, promoted)
            assertEquals("data", staged.resolve("settings.ini").readText())
            assertFalse(staged.resolve("partial.ini").exists())
            assertFalse(promoted.exists())
        }

    @Test
    fun completedStagingWithPreviousPromotedContent_canResumeReplacement() =
        withCleanCacheRoot("staged-local-replacement-test") { root ->
            val appId = "staged-local-replacement-test"
            val staged = root.importDirectory("local_test", ".tmp", "new.ini" to "new!")
            root.importDirectory("local_test", files = arrayOf("old.ini" to "old"))
            val importing = interruptedReplacement(previousInstall(appId, root))

            assertTrue(NexusModManager.hasCompletePendingLocalContent(context, importing))
            assertEquals("new!", staged.resolve("new.ini").readText())
        }

    @Test
    fun deleteInterruptedLocalImport_removesStagedContent() = runBlocking {
        val appId = "delete-local-staging-test"
        withCleanCacheRoot(appId) { root ->
            val staged = root.importDirectory(
                "local_delete_test",
                ".tmp",
                "settings.ini" to "data",
            )
            val install = localInstall(appId, "local_delete_test").copy(
                modName = "Interrupted import",
            )
            val dao = NexusModManager.database(context).modDao()
            dao.upsertInstall(install)
            try {
                NexusModManager.deleteInstall(
                    context = context,
                    install = install,
                    restoreBackups = false,
                )

                assertFalse(staged.exists())
                assertNull(dao.getInstall(install.installId))
            } finally {
                dao.deleteInstall(install.installId)
            }
        }
    }

    @Test
    fun orphanCleanup_preservesValidatedRetrySnapshotAndRemovesInvalidOne() = runBlocking {
        val appId = "local-cleanup-retry-test"
        withCleanCacheRoot(appId) { root ->
            val valid = localInstall(appId, "local_valid_retry")
            val invalid = localInstall(appId, "local_invalid_retry")
            val validStaging = root.importDirectory(
                valid.installId,
                ".tmp",
                "valid.ini" to "data",
            )
            val invalidStaging = root.importDirectory(
                invalid.installId,
                ".tmp",
                "invalid.ini" to "bad",
            )
            val dao = NexusModManager.database(context).modDao()
            dao.upsertInstall(
                completedImport(valid, validStaging.resolve("valid.ini").length()),
            )
            dao.upsertInstall(invalid)
            try {
                val beforeCleanup = NexusModManager.scanStorageForApp(context, appId)
                assertEquals(4L, beforeCleanup.extractedCacheBytes)
                assertEquals(3L, beforeCleanup.cleanableBytes)

                NexusModManager.cleanupOrphanedFilesForApp(context, appId)

                assertTrue(validStaging.isDirectory)
                assertFalse(invalidStaging.exists())
            } finally {
                dao.deleteInstall(valid.installId)
                dao.deleteInstall(invalid.installId)
            }
        }
    }

    @Test
    fun orphanCleanup_protectsActiveImportAndReclaimsStagingAfterCompletion() = runBlocking {
        val appId = "local-cleanup-active-test"
        val installId = "local_active_cleanup"
        withCleanCacheRoot(appId) { root ->
            val archive = root.file("archives/${installId}_mod.zip.part", "data")
            val extracted = listOf("", ".tmp", ".previous").map { suffix ->
                root.importDirectory(installId, suffix, "settings.ini" to "data")
            }
            val dao = NexusModManager.database(context).modDao()
            try {
                assertEquals(
                    ModImportStartResult.STARTED,
                    ModDownloadRegistry.tryStart(installId, appId, "Active import"),
                )
                NexusModManager.cleanupOrphanedFilesForApp(context, appId)

                assertTrue(archive.isFile)
                extracted.forEach { assertTrue(it.isDirectory) }

                dao.upsertInstall(
                    ModInstall(
                        installId = installId,
                        appId = appId,
                        source = ModInstallSource.LOCAL_ARCHIVE.name,
                        modName = "Active import",
                        fileName = "mod.zip",
                        archivePath = archive.absolutePath.removeSuffix(".part"),
                        extractedPath = extracted.first().absolutePath,
                        status = ModInstallStatus.ERROR.name,
                    ),
                )
                NexusModManager.cleanupFailedArchivesForApp(context, appId)

                assertTrue(archive.isFile)

                ModDownloadRegistry.finish(installId)
                dao.upsertInstall(
                    requireNotNull(dao.getInstall(installId)).copy(
                        archivePath = "",
                        status = ModInstallStatus.READY.name,
                    ),
                )
                NexusModManager.cleanupOrphanedFilesForApp(context, appId)

                assertFalse(archive.exists())
            } finally {
                ModDownloadRegistry.finish(installId)
                dao.deleteInstall(installId)
            }
        }
    }

    @Test
    fun orphanCleanup_preservesRecoverableRollbackAfterCleanupFailure() = runBlocking {
        val appId = "local-cleanup-rollback-test"
        val installId = "local_cleanup_rollback"
        withCleanCacheRoot(appId) { root ->
            val previous = localInstall(appId, installId, ModInstallStatus.READY)
            val failed = previous.copy(
                status = ModInstallStatus.ERROR.name,
                metadataJson = NexusImportState.errorMetadata(
                    "",
                    "Cleanup failed",
                    previous,
                ),
            )
            val rollback = root.importDirectory(installId, ".previous", "old.ini" to "old")
            val dao = NexusModManager.database(context).modDao()
            dao.upsertInstall(failed)
            try {
                NexusModManager.cleanupOrphanedFilesForApp(context, appId)

                assertEquals("old", rollback.resolve("old.ini").readText())
            } finally {
                dao.deleteInstall(installId)
            }
        }
    }

    @Test
    fun discardIncompleteLocalContent_restoresRollbackDirectory() =
        withCleanCacheRoot("local-restore-backup-test") { root ->
            val appId = "local-restore-backup-test"
            val installId = "local_restore_backup"
            val promoted = root.importPath(installId)
            val staged = root.importDirectory(installId, ".tmp", "partial.ini" to "partial")
            val backup = root.importDirectory(installId, ".previous", "old.ini" to "old")
            val interrupted = interruptedInstall(localInstall(appId, installId, ModInstallStatus.READY))

            assertTrue(discardIncomplete(interrupted))
            assertFalse(staged.exists())
            assertFalse(backup.exists())
            assertEquals("old", promoted.resolve("old.ini").readText())
        }

    @Test
    fun discardIncompleteLocalContent_keepsExistingPreviousTargetWithoutBackup() =
        withCleanCacheRoot("local-keep-previous-test") { root ->
            val appId = "local-keep-previous-test"
            val installId = "local_keep_previous"
            val promoted = root.importDirectory(installId, files = arrayOf("old.ini" to "old"))
            val staged = root.importDirectory(installId, ".tmp", "partial.ini" to "partial")
            val interrupted = interruptedInstall(localInstall(appId, installId, ModInstallStatus.READY))

            assertTrue(discardIncomplete(interrupted))
            assertFalse(staged.exists())
            assertEquals("old", promoted.resolve("old.ini").readText())
        }

    @Test
    fun discardIncompleteLocalContent_removesUnusableNewImportStaging() =
        withCleanCacheRoot("local-discard-incomplete-test") { root ->
            val appId = "local-discard-incomplete-test"
            val installId = "local_discard_incomplete"
            val promoted = root.importDirectory(installId, files = arrayOf("partial.ini" to "partial"))
            val staged = root.importDirectory(installId, ".tmp", "partial.ini" to "partial")
            val install = localInstall(appId, installId).copy(status = ModInstallStatus.IMPORTING.name)

            assertFalse(discardIncomplete(install))
            assertFalse(staged.exists())
            assertFalse(promoted.exists())
        }

    @Test
    fun interruptedRetry_doesNotMistakePreviousContentForPromotedSnapshot() =
        withCleanCacheRoot("promoted-local-previous-test") { root ->
            val appId = "promoted-local-previous-test"
            val staged = root.importPath("local_test", ".tmp")
            val promoted = root.importDirectory("local_test", files = arrayOf("old.ini" to "same"))
            val importing = interruptedReplacement(
                previousInstall(appId, root).copy(sizeBytes = 4L),
            )

            assertFalse(NexusModManager.hasCompletePendingLocalContent(context, importing))
            assertNull(recover(importing, staged, promoted))
            assertEquals("same", promoted.resolve("old.ini").readText())
        }

    @Test
    fun interruptedRetry_recoversPromotedSnapshotAndPreservesRollbackContent() =
        withCleanCacheRoot("promoted-local-rollback-test") { root ->
            val appId = "promoted-local-rollback-test"
            val staged = root.importPath("local_test", ".tmp")
            val promoted = root.importDirectory("local_test", files = arrayOf("new.ini" to "new!"))
            val previousContent = root.importDirectory(
                "local_test",
                ".previous",
                "old.ini" to "old",
            )
            val importing = interruptedReplacement(previousInstall(appId, root))

            assertTrue(NexusModManager.hasCompletePendingLocalContent(context, importing))
            assertRecovered(importing, staged, promoted)
            assertEquals("new!", staged.resolve("new.ini").readText())
            assertEquals("old", promoted.resolve("old.ini").readText())
            assertFalse(previousContent.exists())

            runBlocking {
                LocalModImportPipeline.finalizeExtractedContent(
                    staged,
                    promoted,
                    "Test finalization failed",
                ) { Unit }
            }

            assertEquals("new!", promoted.resolve("new.ini").readText())
            assertFalse(previousContent.exists())
        }

    @Test
    fun interruptedRetry_recoveryReplacesPartialPromotionBeforeEarlyExit() =
        withCleanCacheRoot("promoted-local-partial-exit-test") { root ->
            val appId = "promoted-local-partial-exit-test"
            val staged = root.importDirectory("local_test", ".tmp", "new.ini" to "new!")
            val promoted = root.importDirectory("local_test", files = arrayOf("partial.ini" to "partial"))
            root.importDirectory("local_test", ".previous", "old.ini" to "old")
            val importing = interruptedReplacement(previousInstall(appId, root))

            assertRecovered(importing, staged, promoted)
            staged.deleteRecursively()

            assertEquals("old", promoted.resolve("old.ini").readText())
            assertFalse(promoted.resolve("partial.ini").exists())
            assertFalse(root.importPath("local_test", ".previous").exists())
        }

    @Test
    fun interruptedNewImport_recoveryDiscardsPartialPromotionBeforeEarlyExit() =
        withCleanCacheRoot("promoted-local-new-partial-test") { root ->
            val appId = "promoted-local-new-partial-test"
            val staged = root.importDirectory("local_test", ".tmp", "new.ini" to "new!")
            val promoted = root.importDirectory("local_test", files = arrayOf("partial.ini" to "partial"))
            val importing = completedImport(
                localInstall(appId, status = ModInstallStatus.IMPORTING).copy(
                    modName = "New install",
                    fileName = "new.ini",
                    archiveSha256 = "new-fingerprint",
                ),
            )

            assertRecovered(importing, staged, promoted)
            staged.deleteRecursively()
            assertFalse(promoted.exists())
        }

    @Test
    fun interruptedRetry_recoveryKeepsPreviousContentOnEarlyExit() =
        withCleanCacheRoot("promoted-local-early-exit-test") { root ->
            val appId = "promoted-local-early-exit-test"
            val staged = root.importPath("local_test", ".tmp")
            val promoted = root.importDirectory("local_test", files = arrayOf("new.ini" to "new!"))
            root.importDirectory("local_test", ".previous", "old.ini" to "old")
            val importing = interruptedReplacement(previousInstall(appId, root))

            assertRecovered(importing, staged, promoted)
            staged.deleteRecursively()

            assertEquals("old", promoted.resolve("old.ini").readText())
            assertFalse(root.importPath("local_test", ".previous").exists())
        }

    private inline fun <T> withCleanCacheRoot(appId: String, block: (File) -> T): T {
        val root = NexusModManager.cacheRoot(context, appId)
        root.deleteRecursively()
        check(root.mkdirs())
        return try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun File.file(relativePath: String, content: String) =
        resolve(relativePath).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun File.importPath(installId: String, suffix: String = "") =
        resolve("extracted/$installId$suffix")

    private fun File.importDirectory(
        installId: String,
        suffix: String = "",
        vararg files: Pair<String, String>,
    ) = importPath(installId, suffix).apply {
        mkdirs()
        files.forEach { (name, content) -> resolve(name).writeText(content) }
    }

    private fun completedImport(install: ModInstall, bytes: Long = install.sizeBytes) =
        NexusImportState.markDownloadComplete(install, bytes)

    private fun interruptedInstall(previous: ModInstall) = previous.copy(
        status = ModInstallStatus.IMPORTING.name,
        metadataJson = NexusImportState.importMetadata("", previous),
    )

    private fun previousInstall(appId: String, root: File) =
        localInstall(appId, status = ModInstallStatus.READY).copy(
            modName = "Previous install",
            fileName = "old.ini",
            sizeBytes = 3L,
            extractedPath = root.importPath("local_test").absolutePath,
            archiveSha256 = "old-fingerprint",
        )

    private fun interruptedReplacement(previous: ModInstall) = completedImport(
        interruptedInstall(previous).copy(
            sizeBytes = 4L,
            archiveSha256 = "new-fingerprint",
        ),
        bytes = 4L,
    )

    private fun recover(install: ModInstall, staged: File, promoted: File) =
        LocalModImporter.recoverInterruptedPromotion(
            context = context,
            previousInstall = install,
            stagedContent = staged,
            promotedContent = promoted,
        )

    private fun assertRecovered(install: ModInstall, staged: File, promoted: File) {
        assertEquals(4L, recover(install, staged, promoted))
    }

    private fun discardIncomplete(install: ModInstall) =
        NexusModManager.discardIncompletePendingLocalContent(
            context,
            install,
            "Cleanup failed",
        )

    private fun localInstall(
        appId: String,
        installId: String = "local_test",
        status: ModInstallStatus = ModInstallStatus.ERROR,
    ) = ModInstall(
        installId = installId,
        appId = appId,
        source = ModInstallSource.LOCAL_FILES.name,
        modName = "Local test",
        fileName = "settings.ini",
        sizeBytes = 4L,
        archivePath = "",
        extractedPath = File(
            NexusModManager.cacheRoot(context, appId),
            "extracted/$installId",
        ).absolutePath,
        status = status.name,
    )

    private fun localFilesRequest(
        installId: String,
        appId: String,
        source: LocalModSourceSelection,
    ) = LocalModImportRequest(
        installId = installId,
        appId = appId,
        sourceType = LocalModSourceType.FILES,
        modName = "Local test",
        sourceName = source.displayName,
        sizeBytes = source.sizeBytes,
    )

    private class FakeModDocumentsProvider : ContentProvider() {
        var rejectNestedQueries = false
        var failQueries = false
        var omitSizeColumn = false
        var addCaseCollision = false
        var openCursorCount = 0

        private data class FakeDocument(
            val id: String,
            val name: String,
            val mimeType: String,
            val size: Long?,
        )

        private val documents = mapOf(
            ROOT_ID to FakeDocument(
                ROOT_ID,
                "Test Mod",
                DocumentsContract.Document.MIME_TYPE_DIR,
                null,
            ),
            DATA_ID to FakeDocument(
                DATA_ID,
                "Data",
                DocumentsContract.Document.MIME_TYPE_DIR,
                null,
            ),
            SETTINGS_ID to FakeDocument(SETTINGS_ID, "settings.ini", "text/plain", 4L),
            DLL_ID to FakeDocument(DLL_ID, "plugin.dll", "application/octet-stream", 6L),
            README_ID to FakeDocument(README_ID, "readme.txt", "text/plain", 2L),
            CASE_COLLISION_ID to FakeDocument(
                CASE_COLLISION_ID,
                "SETTINGS.INI",
                "text/plain",
                4L,
            ),
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            if (failQueries) throw SecurityException("Provider denied access")
            if (rejectNestedQueries && openCursorCount > 0) {
                throw IllegalStateException("Nested provider query while a parent cursor is open")
            }
            val segments = uri.pathSegments
            val childQuery = segments.lastOrNull() == "children"
            val documentId = if (childQuery) {
                segments.getOrNull(segments.lastIndex - 1)
            } else {
                segments.lastOrNull()
            }
            val rows = if (childQuery) {
                when (documentId) {
                    ROOT_ID -> listOfNotNull(
                        documents[DATA_ID],
                        documents[DLL_ID],
                        documents[README_ID],
                    )
                    DATA_ID -> buildList {
                        documents[SETTINGS_ID]?.let(::add)
                        if (addCaseCollision) documents[CASE_COLLISION_ID]?.let(::add)
                    }
                    else -> emptyList()
                }
            } else {
                listOfNotNull(documentId?.let(documents::get))
            }
            return documentCursor(projection, rows)
        }

        override fun getType(uri: Uri): String? = null

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val documentId = uri.pathSegments.lastOrNull()
                ?: throw IOException("Missing document ID")
            val content = when (documentId) {
                SETTINGS_ID, CASE_COLLISION_ID -> "data"
                DLL_ID -> "plugin"
                README_ID -> "ok"
                else -> throw IOException("Unknown document ID")
            }
            val file = File(requireNotNull(context).cacheDir, "local-mod-$documentId".hashCode().toString())
            file.writeText(content)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private fun documentCursor(
            projection: Array<out String>?,
            rows: List<FakeDocument>,
        ): Cursor {
            val requestedColumns = projection ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val columns = if (omitSizeColumn) {
                requestedColumns
                    .filterNot { it == DocumentsContract.Document.COLUMN_SIZE }
                    .toTypedArray()
            } else {
                requestedColumns
            }
            openCursorCount++
            return object : MatrixCursor(columns) {
                private var trackedOpen = true

                override fun close() {
                    if (trackedOpen) {
                        trackedOpen = false
                        openCursorCount--
                    }
                    super.close()
                }
            }.apply {
                rows.forEach { document ->
                    val values = arrayOfNulls<Any>(columns.size)
                    columns.forEachIndexed { index, column ->
                        values[index] = when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> document.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> document.mimeType
                            DocumentsContract.Document.COLUMN_SIZE -> document.size
                            else -> null
                        }
                    }
                    addRow(values)
                }
            }
        }
    }

    private companion object {
        const val AUTHORITY = "app.gamenative.test.moddocuments"
        const val ROOT_ID = "root"
        const val DATA_ID = "root/data"
        const val SETTINGS_ID = "root/data/settings.ini"
        const val CASE_COLLISION_ID = "root/data/SETTINGS.INI"
        const val DLL_ID = "root/plugin.dll"
        const val README_ID = "root/readme.txt"
    }
}
