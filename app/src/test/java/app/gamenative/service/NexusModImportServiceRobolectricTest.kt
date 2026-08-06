package app.gamenative.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallSource
import app.gamenative.data.ModInstallStatus
import app.gamenative.mods.NexusImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class NexusModImportServiceRobolectricTest {
    private lateinit var provider: RejectingMimeProbeProvider

    @Before
    fun registerProvider() {
        provider = RejectingMimeProbeProvider()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test
    fun grantLocalSourceUris_treeUriDoesNotProbeDocumentProvider() {
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            AUTHORITY,
            "primary:Download/Test Mod",
        )
        val intent = Intent()

        NexusModImportService.run {
            intent.grantLocalSourceUris(listOf(treeUri))
        }

        assertEquals(0, provider.mimeTypeProbeCount)
        assertEquals(treeUri, intent.data)
        assertEquals(treeUri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun localSourceUris_roundTripMultipleDistinctUris() {
        val uris = listOf(
            DocumentsContract.buildDocumentUri(AUTHORITY, "first.zip"),
            DocumentsContract.buildDocumentUri(AUTHORITY, "settings.ini"),
        )
        val intent = Intent()

        val decoded = NexusModImportService.run {
            intent.grantLocalSourceUris(uris)
            decodeLocalSourceUris(intent)
        }

        assertEquals(0, provider.mimeTypeProbeCount)
        assertEquals(uris, decoded)
        assertEquals(2, intent.clipData?.itemCount)
    }

    @Test
    fun terminalLocalResumeFailure_restoresPreviousInstallOnlyWhenContentIsAvailable() {
        val previous = localInstall(ModInstallStatus.READY)
        val restored = localResumeFailure(
            previous = previous,
            preserveCompletedTransfer = true,
            restorePreviousInstall = true,
        )
        val missing = localResumeFailure(
            previous = previous,
            preserveCompletedTransfer = false,
            restorePreviousInstall = false,
        )

        assertEquals(ModInstallStatus.READY.name, restored.status)
        assertEquals(previous.installId, restored.installId)
        assertEquals(previous.archiveSha256, restored.archiveSha256)
        assertEquals(ModInstallStatus.ERROR.name, missing.status)
        assertFalse(NexusImportState.hasCompletedLocalSnapshot(missing, 4L))
        assertEquals(
            previous.installId,
            NexusImportState.restorablePreviousInstall(missing)?.installId,
        )
    }

    @Test
    fun terminalLocalResumeFailure_preservesOnlyUsableCompletedSnapshot() {
        val discarded = localResumeFailure(
            preserveCompletedTransfer = false,
            restorePreviousInstall = false,
        )
        val preserved = localResumeFailure(
            preserveCompletedTransfer = true,
            restorePreviousInstall = false,
        )

        assertEquals(ModInstallStatus.ERROR.name, discarded.status)
        assertEquals(ModInstallStatus.ERROR.name, preserved.status)
        assertFalse(NexusImportState.hasCompletedLocalSnapshot(discarded, 4L))
        assertTrue(NexusImportState.hasCompletedLocalSnapshot(preserved, 4L))
    }

    private fun localResumeFailure(
        previous: ModInstall? = null,
        preserveCompletedTransfer: Boolean,
        restorePreviousInstall: Boolean,
    ): ModInstall {
        val importing = previous?.copy(
            status = ModInstallStatus.IMPORTING.name,
            metadataJson = NexusImportState.importMetadata("", previous),
        ) ?: localInstall(ModInstallStatus.IMPORTING)
        val interrupted = NexusImportState.markDownloadComplete(
            importing,
            downloadedBytes = 4L,
        )
        return terminalLocalResumeFailure(
            interrupted,
            "Import failed",
            preserveCompletedTransfer,
            restorePreviousInstall,
        )
    }

    private fun localInstall(status: ModInstallStatus) = ModInstall(
        installId = "local_test",
        appId = "test-app",
        source = ModInstallSource.LOCAL_FILES.name,
        modName = "Local test",
        fileName = "settings.ini",
        sizeBytes = 4L,
        archivePath = "",
        extractedPath = "/mods/local_test",
        status = status.name,
        archiveSha256 = "fingerprint",
    )

    private class RejectingMimeProbeProvider : ContentProvider() {
        var mimeTypeProbeCount = 0

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri): String? {
            mimeTypeProbeCount++
            throw IllegalArgumentException("Tree URI must not be MIME-probed: $uri")
        }

        override fun getStreamTypes(uri: Uri, mimeTypeFilter: String): Array<String>? {
            mimeTypeProbeCount++
            throw IllegalArgumentException("Tree URI must not be stream-probed: $uri")
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
    }

    private companion object {
        const val AUTHORITY = "app.gamenative.test.localmodservice"
    }
}
