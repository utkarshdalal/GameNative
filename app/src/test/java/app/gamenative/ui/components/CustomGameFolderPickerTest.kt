package app.gamenative.ui.components

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.fakes.RoboCursor

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CustomGameFolderPickerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun storageTree(docId: String): Uri =
        DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", docId)

    private fun downloadsTree(docId: String): Uri =
        DocumentsContract.buildTreeDocumentUri("com.android.providers.downloads.documents", docId)

    @Test fun primaryVolumeFolderIsUnderExternalStorage() {
        val root = Environment.getExternalStorageDirectory().path
        assertEquals("$root/Games/Foo", getPathFromTreeUri(context, storageTree("primary:Games/Foo")))
        assertEquals(root, getPathFromTreeUri(context, storageTree("primary:")))
    }

    @Test fun secondaryVolumeFolderIsUnderItsMountPoint() {
        assertEquals("/storage/6A1F-93F0/Games", getPathFromTreeUri(context, storageTree("6A1F-93F0:Games")))
    }

    @Test fun documentsShortcutFolderIsUnderDocuments() {
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
        assertEquals("$documents/Games", getPathFromTreeUri(context, storageTree("home:Games")))
    }

    @Test fun downloadsRootIsTheDownloadFolder() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        assertEquals(downloads, getPathFromTreeUri(context, downloadsTree("downloads")))
    }

    @Test fun downloadsRawIdCarriesThePath() {
        assertEquals(
            "/storage/emulated/0/Download/Foo",
            getPathFromTreeUri(context, downloadsTree("raw:/storage/emulated/0/Download/Foo")),
        )
    }

    @Test fun downloadsFolderIdIsReadFromMediaStore() {
        val row = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL, 2029738L)
        val cursor = RoboCursor().apply {
            setColumnNames(listOf(MediaStore.MediaColumns.DATA))
            setResults(arrayOf(arrayOf<Any>("/storage/emulated/0/Download/Games/Foo")))
        }
        shadowOf(context.contentResolver).setCursor(row, cursor)
        assertEquals("/storage/emulated/0/Download/Games/Foo", getPathFromTreeUri(context, downloadsTree("msd:2029738")))
    }

    @Test fun downloadsFolderIdWithoutMediaStoreRowHasNoPath() {
        assertNull(getPathFromTreeUri(context, downloadsTree("msd:2029738")))
    }

    @Test fun otherProviderWithPrimaryIdIsUnderExternalStorage() {
        val uri = DocumentsContract.buildTreeDocumentUri("com.example.files.documents", "primary:Games/Foo")
        val root = Environment.getExternalStorageDirectory().path
        assertEquals("$root/Games/Foo", getPathFromTreeUri(context, uri))
    }

    @Test fun otherProviderIdIsNotAStoragePath() {
        val uri = DocumentsContract.buildTreeDocumentUri("com.example.cloud.documents", "acct:folder/1")
        assertNull(getPathFromTreeUri(context, uri))
    }
}
