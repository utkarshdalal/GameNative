package app.gamenative.service.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFileFilterTest {

    @Test
    fun excluded_dir_components() {
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Crashpad/foo.dmp"))
        assertTrue(SyncFileFilter.isChromiumInternal("Crashpad/anything"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/ShaderCache/key/data"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/GPUCache/0001"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/data_reduction_proxy_leveldb/MANIFEST"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/Site Characteristics Database/LOG"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Stability/foo.pma"))
    }

    @Test
    fun excluded_basenames_and_extensions() {
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/previews_opt_out.db"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/page_load_capping_opt_out.db"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/BrowserMetrics-spare.pma"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/BrowserMetrics-active.pma"))
        assertTrue(SyncFileFilter.isChromiumInternal("crashes/something.dmp"))
        assertTrue(SyncFileFilter.isChromiumInternal("metrics.pma"))
        assertTrue(SyncFileFilter.isChromiumInternal("crashes/SOMETHING.DMP"))
    }

    @Test
    fun excluded_user_data_root_files() {
        // chromium User Data root-level runtime files.
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/chrome_debug.log"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/First Run"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Last Browser"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Last Version"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Local State"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Variations"))
        // leveldb advisory lock -- chromium recreates it on open; a stale one wastes cloud manifest
        // space at best, and confuses leveldb if pulled back at worst.
        assertTrue(SyncFileFilter.isChromiumInternal("User Data/Default/Local Storage/leveldb/LOCK"))
    }

    @Test
    fun windows_separators_normalized() {
        assertTrue(SyncFileFilter.isChromiumInternal("User Data\\Crashpad\\foo.dmp"))
        assertTrue(SyncFileFilter.isChromiumInternal("User Data\\BrowserMetrics-spare.pma"))
    }

    @Test
    fun load_bearing_paths_kept() {
        assertFalse(SyncFileFilter.isChromiumInternal("cc.save"))
        assertFalse(SyncFileFilter.isChromiumInternal("cc.save.backup1"))
        assertFalse(SyncFileFilter.isChromiumInternal("save/file1.rpgsave"))
        assertFalse(SyncFileFilter.isChromiumInternal("global.rpgsave"))
        // chromium-LS leveldb is wanted in cloud
        assertFalse(SyncFileFilter.isChromiumInternal("User Data/Default/Local Storage/leveldb/000003.ldb"))
        assertFalse(SyncFileFilter.isChromiumInternal("User Data/Default/Local Storage/leveldb/MANIFEST-000001"))
        // chromium-IDB is wanted
        assertFalse(SyncFileFilter.isChromiumInternal("User Data/Default/IndexedDB/file__0.indexeddb.leveldb/CURRENT"))
    }

    // our own swap/staging scratch. it only exists on disk when a process died mid-write, and
    // uploading one would put a partial store in cloud.
    @Test
    fun excludes_our_own_swap_and_staging_scratch() {
        assertTrue(SyncFileFilter.isChromiumInternal("save/Local Storage/leveldb.gnnew/000003.log"))
        assertTrue(SyncFileFilter.isChromiumInternal("save/Local Storage/leveldb.gnold/CURRENT"))
        assertTrue(SyncFileFilter.isChromiumInternal("save/file1.rpgsave.gntmp"))
        // real saves that merely mention the words must survive
        assertFalse(SyncFileFilter.isChromiumInternal("save/gnnew.rpgsave"))
        assertFalse(SyncFileFilter.isChromiumInternal("save/my.gnold.save"))
        assertFalse(SyncFileFilter.isChromiumInternal("save/file1.rpgsave"))
    }

    @Test
    fun edge_cases() {
        assertFalse(SyncFileFilter.isChromiumInternal(""))
        assertFalse(SyncFileFilter.isChromiumInternal("/"))
        assertFalse(SyncFileFilter.isChromiumInternal("//"))
        // partial-name should NOT match a directory component (component match is exact)
        assertFalse(SyncFileFilter.isChromiumInternal("CrashpadX/file"))
        assertFalse(SyncFileFilter.isChromiumInternal("X-Crashpad/file"))
        // BrowserMetrics is a prefix match -- only on basenames, not directory components
        assertFalse(SyncFileFilter.isChromiumInternal("BrowserMetrics/data.txt")) // dir-component, not in deny list
        assertTrue(SyncFileFilter.isChromiumInternal("anywhere/BrowserMetrics-active.pma"))
        // saves named like crashpad must still survive (no false positive on basename)
        assertFalse(SyncFileFilter.isChromiumInternal("my_crash_save.dat"))
    }
}
