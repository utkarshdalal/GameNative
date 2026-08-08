package app.gamenative.html5.savesync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Html5LeveldbHealthTest {

    private lateinit var ctx: Context
    private lateinit var appWebview: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        appWebview = File(ctx.dataDir, "app_webview")
        if (appWebview.exists()) appWebview.deleteRecursively()
    }

    @After
    fun tearDown() {
        if (appWebview.exists()) appWebview.deleteRecursively()
    }

    @Test
    fun missing_app_webview_dir_is_no_op() {
        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(Html5LeveldbHealth.RepairResult(0, 0, 0, 0), r)
    }

    @Test
    fun clean_log_without_wedge_signature_is_not_flagged() {
        val ls = lsLeveldb("Default")
        ls.mkdirs()
        File(ls, "LOG").writeText("Recovering log #5\nCompacting 1@0 + 1@1 files\n")
        File(ls, "MANIFEST-000001").writeBytes(byteArrayOf(0))

        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(1, r.scanned)
        assertEquals(0, r.wedged)
        assertTrue("clean LOG must not be wiped", File(ls, "LOG").exists())
    }

    @Test
    fun wedge_signature_with_no_recoverable_db_falls_back_to_wipe() {
        val ls = lsLeveldb("Default")
        ls.mkdirs()
        // signature present but no real SSTs/MANIFEST — iq80 repair will throw → wipe path.
        File(ls, "LOG").writeText(
            "Recovering log #1\n" +
                "Compacting 2@0 + 1@1 files\n" +
                "Compaction error: Corruption: not an sstable (bad magic number)\n",
        )
        File(ls, "garbage.bin").writeBytes(byteArrayOf(1, 2, 3))

        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(1, r.scanned)
        assertEquals(1, r.wedged)
        assertEquals("wipe-fallback expected when no valid leveldb to repair", 1, r.wiped)
        assertEquals(0, r.repaired)
        assertFalse("wipe should clear contents", File(ls, "LOG").exists())
        assertFalse(File(ls, "garbage.bin").exists())
    }

    @Test
    fun scans_per_container_profile_dirs_too() {
        val def = lsLeveldb("Default").apply { mkdirs() }
        File(def, "LOG").writeText("clean\n")

        val p1 = lsLeveldb("Profile-7").apply { mkdirs() }
        File(p1, "LOG").writeText("Compaction error: Corruption: not an sstable\n")

        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(2, r.scanned)
        assertEquals(1, r.wedged)
    }

    @Test
    fun scans_idb_per_origin_leveldbs_too() {
        val idb = File(appWebview, "Default/IndexedDB/https_example_0.indexeddb.leveldb")
        idb.mkdirs()
        File(idb, "LOG").writeText("clean\n")

        // sibling that does NOT match the .leveldb suffix — must NOT be scanned
        val notIdb = File(appWebview, "Default/IndexedDB/https_example_0.blob")
        notIdb.mkdirs()
        File(notIdb, "LOG").writeText("clean\n")

        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(1, r.scanned)
        assertEquals(0, r.wedged)
    }

    @Test
    fun signature_in_log_old_is_also_detected() {
        val ls = lsLeveldb("Default").apply { mkdirs() }
        File(ls, "LOG").writeText("clean current LOG\n")
        File(ls, "LOG.old").writeText(
            "old log shows Compaction error: Corruption: not an sstable\n",
        )

        val r = Html5LeveldbHealth.repairIfWedged(ctx)
        assertEquals(1, r.scanned)
        assertEquals(1, r.wedged)
    }

    private fun lsLeveldb(profile: String): File =
        File(appWebview, "$profile/Local Storage/leveldb")
}
