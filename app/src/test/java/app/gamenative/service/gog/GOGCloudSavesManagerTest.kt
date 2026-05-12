package app.gamenative.service.gog

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
