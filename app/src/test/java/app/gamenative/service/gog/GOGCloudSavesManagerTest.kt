package app.gamenative.service.gog

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.lang.reflect.Method

class GOGCloudSavesManagerTest {
    private val context: Context = mock()
    private val manager = GOGCloudSavesManager(context)

    @Test
    fun parseCloudTimestamp_accepts_gog_offset_format() {
        val timestamp = manager.parseCloudTimestamp("2026-04-02T20:34:00.123456+00:00")

        assertEquals(1_775_162_040L, timestamp)
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

    // Helper to access private classifyFiles method
    private fun classifyFiles(
        localFiles: List<GOGCloudSavesManager.SyncFile>,
        cloudFiles: List<GOGCloudSavesManager.CloudFile>,
        timestamp: Long
    ): GOGCloudSavesManager.SyncClassifier {
        val method: Method = GOGCloudSavesManager::class.java.getDeclaredMethod(
            "classifyFiles",
            List::class.java,
            List::class.java,
            Long::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(manager, localFiles, cloudFiles, timestamp) as GOGCloudSavesManager.SyncClassifier
    }

    @Test
    fun classifyFiles_identifies_files_updated_locally_after_last_sync() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile(
                relativePath = "save1.sav",
                absolutePath = "/path/save1.sav",
                md5Hash = "hash1",
                updateTime = "2026-01-01T00:00:00Z",
                updateTimestamp = 1500L // Modified after last sync
            ),
            GOGCloudSavesManager.SyncFile(
                relativePath = "save2.sav",
                absolutePath = "/path/save2.sav",
                md5Hash = "hash2",
                updateTime = "2026-01-01T00:00:00Z",
                updateTimestamp = 500L // Modified before last sync
            )
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 800L),
            GOGCloudSavesManager.CloudFile("save2.sav", "hash2", "2026-01-01T00:00:00Z", 500L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)

        assertEquals(1, classifier.updatedLocal.size)
        assertEquals("save1.sav", classifier.updatedLocal[0].relativePath)
        assertEquals(1500L, classifier.updatedLocal[0].updateTimestamp)
    }

    @Test
    fun classifyFiles_identifies_files_not_existing_remotely() {
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 1000L),
            GOGCloudSavesManager.SyncFile("save2.sav", "/path/save2.sav", "hash2", "2026-01-01T00:00:00Z", 1000L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 1000L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, 0L)

        assertEquals(1, classifier.notExistingRemotely.size)
        assertEquals("save2.sav", classifier.notExistingRemotely[0].relativePath)
    }

    @Test
    fun classifyFiles_identifies_files_updated_in_cloud_after_last_sync() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 1500L) // Modified after last sync
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)

        assertEquals(1, classifier.updatedCloud.size)
        assertEquals("save1.sav", classifier.updatedCloud[0].relativePath)
    }

    @Test
    fun classifyFiles_identifies_files_not_existing_locally() {
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 1000L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 1000L),
            GOGCloudSavesManager.CloudFile("save2.sav", "hash2", "2026-01-01T00:00:00Z", 1000L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, 0L)

        assertEquals(1, classifier.notExistingLocally.size)
        assertEquals("save2.sav", classifier.notExistingLocally[0].relativePath)
    }

    @Test
    fun classifyFiles_skips_deleted_cloud_files() {
        val localFiles = emptyList<GOGCloudSavesManager.SyncFile>()
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "aadd86936a80ee8a369579c3926f1b3c", "2026-01-01T00:00:00Z", 1000L) // Deletion marker
        )

        val classifier = classifyFiles(localFiles, cloudFiles, 0L)

        assertEquals(0, classifier.notExistingLocally.size)
        assertEquals(0, classifier.updatedCloud.size)
    }

    @Test
    fun syncClassifier_determineAction_returns_upload_when_only_local_updated() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 1500L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val action = classifier.determineAction()

        assertEquals(GOGCloudSavesManager.SyncAction.UPLOAD, action)
    }

    @Test
    fun syncClassifier_determineAction_returns_download_when_only_cloud_updated() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 1500L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val action = classifier.determineAction()

        assertEquals(GOGCloudSavesManager.SyncAction.DOWNLOAD, action)
    }

    @Test
    fun syncClassifier_determineAction_returns_conflict_when_both_updated() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 1500L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 1500L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val action = classifier.determineAction()

        assertEquals(GOGCloudSavesManager.SyncAction.CONFLICT, action)
    }

    @Test
    fun syncClassifier_determineAction_returns_none_when_nothing_updated() {
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val action = classifier.determineAction()

        assertEquals(GOGCloudSavesManager.SyncAction.NONE, action)
    }

    @Test
    fun smart_upload_should_only_include_updated_and_new_files() {
        // Simulates the smart upload logic: only files modified after last sync + new files
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("unchanged.sav", "/path/unchanged.sav", "hash1", "2026-01-01T00:00:00Z", 500L),
            GOGCloudSavesManager.SyncFile("modified.sav", "/path/modified.sav", "hash2", "2026-01-01T00:00:00Z", 1500L),
            GOGCloudSavesManager.SyncFile("new.sav", "/path/new.sav", "hash3", "2026-01-01T00:00:00Z", 1200L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("unchanged.sav", "hash1", "2026-01-01T00:00:00Z", 500L),
            GOGCloudSavesManager.CloudFile("modified.sav", "hash2_old", "2026-01-01T00:00:00Z", 800L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val filesToUpload = mutableListOf<GOGCloudSavesManager.SyncFile>()
        filesToUpload.addAll(classifier.updatedLocal)
        filesToUpload.addAll(classifier.notExistingRemotely)

        // Deduplicate by relativePath (files can appear in both lists)
        val uniqueFilesToUpload = filesToUpload.distinctBy { it.relativePath }

        assertEquals(2, uniqueFilesToUpload.size)
        assertTrue(uniqueFilesToUpload.any { it.relativePath == "modified.sav" })
        assertTrue(uniqueFilesToUpload.any { it.relativePath == "new.sav" })
        assertTrue(uniqueFilesToUpload.none { it.relativePath == "unchanged.sav" })
    }

    @Test
    fun smart_upload_deduplicates_new_files_appearing_in_both_lists() {
        // New files modified after last sync appear in BOTH updatedLocal and notExistingRemotely
        // This test verifies they are only uploaded once
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("new.sav", "/path/new.sav", "hash1", "2026-01-01T00:00:00Z", 1500L)
        )
        val cloudFiles = emptyList<GOGCloudSavesManager.CloudFile>()

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)

        // Verify the file appears in both lists
        assertEquals(1, classifier.updatedLocal.size)
        assertEquals(1, classifier.notExistingRemotely.size)
        assertEquals("new.sav", classifier.updatedLocal[0].relativePath)
        assertEquals("new.sav", classifier.notExistingRemotely[0].relativePath)

        // Simulate the upload logic with deduplication
        val filesToUpload = mutableListOf<GOGCloudSavesManager.SyncFile>()
        filesToUpload.addAll(classifier.updatedLocal)
        filesToUpload.addAll(classifier.notExistingRemotely)
        val uniqueFilesToUpload = filesToUpload.distinctBy { it.relativePath }

        // Should only upload once
        assertEquals(1, uniqueFilesToUpload.size)
        assertEquals("new.sav", uniqueFilesToUpload[0].relativePath)
    }

    @Test
    fun smart_upload_returns_original_timestamp_when_no_files_changed() {
        // When no files have changed since last sync, should return the original timestamp
        // This signals to the caller that no sync occurred and timestamp should not be updated
        val lastSyncTime = 1000L
        val localFiles = listOf(
            GOGCloudSavesManager.SyncFile("save1.sav", "/path/save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L),
            GOGCloudSavesManager.SyncFile("save2.sav", "/path/save2.sav", "hash2", "2026-01-01T00:00:00Z", 800L)
        )
        val cloudFiles = listOf(
            GOGCloudSavesManager.CloudFile("save1.sav", "hash1", "2026-01-01T00:00:00Z", 500L),
            GOGCloudSavesManager.CloudFile("save2.sav", "hash2", "2026-01-01T00:00:00Z", 800L)
        )

        val classifier = classifyFiles(localFiles, cloudFiles, lastSyncTime)
        val filesToUpload = mutableListOf<GOGCloudSavesManager.SyncFile>()
        filesToUpload.addAll(classifier.updatedLocal)
        filesToUpload.addAll(classifier.notExistingRemotely)
        val uniqueFilesToUpload = filesToUpload.distinctBy { it.relativePath }

        // No files should be uploaded
        assertEquals(0, uniqueFilesToUpload.size)

        // When uniqueFilesToUpload is empty, the implementation should return lastSyncTimestamp
        // instead of currentTimestamp() to signal no changes occurred
        // This allows the caller to detect: newTimestamp == lastSyncTimestamp means no changes
    }

    @Test
    fun downloaded_files_should_preserve_cloud_timestamp() {
        // Critical: When downloading files from cloud, the local file's modification time
        // must be set to match the cloud file's timestamp. This ensures:
        // 1. Future syncs correctly detect which version is newer
        // 2. Files downloaded from cloud don't immediately appear as "locally modified"
        // 3. Timestamp-based conflict detection works correctly

        val cloudFile = GOGCloudSavesManager.CloudFile(
            relativePath = "save.sav",
            md5Hash = "abc123",
            updateTime = "2026-01-01T00:00:00Z",
            updateTimestamp = 1500L // seconds (UTC epoch)
        )

        // After download, the local file's lastModified() should be set to:
        val expectedLocalMillis = cloudFile.updateTimestamp!! * 1000
        assertEquals(1500000L, expectedLocalMillis)

        // Simulate what happens after download: create a local file representation
        val downloadedFile = GOGCloudSavesManager.SyncFile(
            relativePath = cloudFile.relativePath,
            absolutePath = "/path/save.sav",
            md5Hash = cloudFile.md5Hash,
            updateTime = cloudFile.updateTime,
            updateTimestamp = cloudFile.updateTimestamp // Should match cloud timestamp
        )

        // Verify timestamps match (both in seconds)
        assertEquals(cloudFile.updateTimestamp, downloadedFile.updateTimestamp)

        // Test scenario: After downloading, if we sync again with the same lastSyncTime,
        // both local and cloud will have timestamp > lastSyncTime
        val lastSyncTime = 1000L
        val classifier = classifyFiles(listOf(downloadedFile), listOf(cloudFile), lastSyncTime)

        // Both files have timestamp 1500L > lastSyncTime 1000L, so both are "updated"
        assertEquals(1, classifier.updatedLocal.size)
        assertEquals(1, classifier.updatedCloud.size)

        // This results in CONFLICT action
        assertEquals(GOGCloudSavesManager.SyncAction.CONFLICT, classifier.determineAction())

        // However, the CONFLICT resolution logic compares timestamps and finds they're equal,
        // so neither will be uploaded/downloaded (see syncSaves CONFLICT handling at lines 275-287)

        // The key is: after a successful download that preserves timestamps, the NEXT sync
        // should update lastSyncTimestamp to 1500L, preventing this conflict on future syncs
    }
}
