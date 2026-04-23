package app.gamenative.service.gog

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class GOGCloudSavesManagerTest {
    private val context: Context = mock()
    private val manager = GOGCloudSavesManager(context)

    @Test
    fun parseCloudTimestamp_accepts_gog_offset_format() {
        val timestamp = manager.parseCloudTimestamp("2026-04-02T20:34:00.123456+00:00")

        assertEquals(1_775_162_040L, timestamp)
    }

    @Test
    fun parseCloudTimestamp_returns_null_for_invalid_format() {
        val timestamp = manager.parseCloudTimestamp("04/02/2026 20:34:00")

        assertNull(timestamp)
    }

    @Test
    fun parseCloudFilesResponse_returns_null_for_invalid_json_instead_of_empty_list() {
        val files = manager.parseCloudFilesResponse("not-json", "__default")

        assertNull(files)
    }

    @Test
    fun parseCloudFilesResponse_parses_matching_files_and_preserves_offset_timestamp() {
        val files = manager.parseCloudFilesResponse(
            """
            [
              {
                "name": "__default/save-1.sav",
                "hash": "abc123",
                "last_modified": "2026-04-02T20:34:00.123456+00:00"
              },
              {
                "name": "other-dir/save-2.sav",
                "hash": "ignored",
                "last_modified": "2026-04-02T21:00:00+00:00"
              }
            ]
            """.trimIndent(),
            "__default",
        )

        assertNotNull(files)
        assertEquals(1, files!!.size)
        assertEquals("save-1.sav", files.single().relativePath)
        assertEquals(1_775_162_040L, files.single().updateTimestamp)
    }

    @Test
    fun parseCloudFilesResponse_preserves_deletion_markers() {
        val files = manager.parseCloudFilesResponse(
            """
            [
              {
                "name": "__default/save-1.sav",
                "hash": "aadd86936a80ee8a369579c3926f1b3c",
                "last_modified": "2026-04-02T20:34:00.123456+00:00"
              }
            ]
            """.trimIndent(),
            "__default",
        )

        assertNotNull(files)
        assertEquals(1, files!!.size)
        assertTrue(files.single().isDeleted)
    }

    @Test
    fun parseCloudFilesResponse_skips_entries_with_missing_hash_or_wrong_dirname() {
        val files = manager.parseCloudFilesResponse(
            """
            [
              {
                "name": "__default/save-1.sav",
                "hash": "",
                "last_modified": "2026-04-02T20:34:00.123456+00:00"
              },
              {
                "name": "profiles/save-2.sav",
                "hash": "abc123",
                "last_modified": "2026-04-02T20:34:00.123456+00:00"
              }
            ]
            """.trimIndent(),
            "__default",
        )

        assertNotNull(files)
        assertTrue(files!!.isEmpty())
    }

    @Test
    fun deleteLocalFilesForRemoteState_deletes_nested_contents_but_keeps_root_directory() {
        val root = Files.createTempDirectory("gog-cloud-delete").toFile()
        val nestedDir = File(root, "profiles/slot1").apply { mkdirs() }
        File(nestedDir, "save.dat").writeText("save")
        File(root, "settings.json").writeText("config")

        val deleted = callDeleteLocalFilesForRemoteState(root)

        assertTrue(deleted)
        assertTrue(root.exists())
        assertEquals(emptyList<String>(), root.walkTopDown().filter { it != root }.map { it.name }.toList())
        root.deleteRecursively()
    }

    @Test
    fun deleteLocalFilesForRemoteState_succeeds_when_root_directory_is_missing() {
        val root = Files.createTempDirectory("gog-cloud-missing").toFile()
        root.deleteRecursively()

        val deleted = callDeleteLocalFilesForRemoteState(root)

        assertTrue(deleted)
    }

    @Test
    fun gzipCompress_produces_valid_gzip_magic_bytes() {
        val result = GOGCloudSavesManager.gzipCompress("hello".toByteArray())

        assertEquals(0x1f.toByte(), result[0])
        assertEquals(0x8b.toByte(), result[1])
    }

    @Test
    fun gzipCompress_mtime_xfl_os_fields_are_normalized() {
        val result = GOGCloudSavesManager.gzipCompress("hello".toByteArray())

        assertEquals(0.toByte(), result[4])  // MTIME byte 0
        assertEquals(0.toByte(), result[5])  // MTIME byte 1
        assertEquals(0.toByte(), result[6])  // MTIME byte 2
        assertEquals(0.toByte(), result[7])  // MTIME byte 3
        assertEquals(0.toByte(), result[8])  // XFL
        assertEquals((-1).toByte(), result[9]) // OS = 255 (unknown)
    }

    @Test
    fun gzipCompress_is_deterministic_same_input_same_md5() {
        val data = "save file content".toByteArray()

        val hash1 = md5Hex(GOGCloudSavesManager.gzipCompress(data))
        val hash2 = md5Hex(GOGCloudSavesManager.gzipCompress(data))

        assertEquals(hash1, hash2)
    }

    @Test
    fun gzipCompress_different_content_produces_different_md5() {
        val hash1 = md5Hex(GOGCloudSavesManager.gzipCompress("aaa".toByteArray()))
        val hash2 = md5Hex(GOGCloudSavesManager.gzipCompress("bbb".toByteArray()))

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun gzipCompress_output_can_be_decompressed_back_to_original() {
        val original = "round-trip save data".toByteArray()
        val compressed = GOGCloudSavesManager.gzipCompress(original)

        val decompressed = java.util.zip.GZIPInputStream(compressed.inputStream()).readBytes()

        assertTrue(original.contentEquals(decompressed))
    }

    @Test
    fun sync_classifier_selects_upload_when_only_local_files_not_in_cloud() {
        val classifier = GOGCloudSavesManager.SyncClassifier(
            notExistingRemotely = listOf(
                GOGCloudSavesManager.SyncFile(
                    relativePath = "save.sav",
                    absolutePath = "/tmp/save.sav",
                ),
            ),
        )

        assertEquals(GOGCloudSavesManager.SyncAction.UPLOAD, classifier.determineAction())
    }

    @Test
    fun sync_classifier_selects_download_when_only_new_cloud_files_exist() {
        val classifier = GOGCloudSavesManager.SyncClassifier(
            notExistingLocally = listOf(
                GOGCloudSavesManager.CloudFile(
                    relativePath = "new.sav",
                    md5Hash = "abc123",
                    updateTime = null,
                    updateTimestamp = null,
                ),
            ),
        )

        assertEquals(GOGCloudSavesManager.SyncAction.DOWNLOAD, classifier.determineAction())
    }


    private fun callDeleteLocalFilesForRemoteState(root: File): Boolean {
        val method = manager.javaClass.declaredMethods.first { it.name == "deleteLocalFilesForRemoteState" }
        method.isAccessible = true
        return method.invoke(manager, root) as Boolean
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
}
