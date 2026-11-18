package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// can pure-java iq80 parse chromium's leveldb flavor (snappy + custom comparators)? iq80 over leveldbjni-all:1.8
// because the latter ships no osx-arm64 or android-arm64-v8a natives. asserts ONLY that real saves open and
// iterate; semantics are covered elsewhere. fixtures via SaveFixtureHarness, skipped when absent.
class LevelDbParseProbeTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun probeLevelDb(levelDbDir: File, useIdb1: Boolean = false): Int {
        val options = Options().apply {
            createIfMissing(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) {
                // iq80's MANIFEST check rejects chromium IDB without idb_cmp1 registered
                comparator(Idb1Comparator())
            }
        }
        return Iq80DBFactory.factory.open(levelDbDir, options).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                var count = 0
                while (iter.hasNext() && count < 10) {
                    val entry = iter.next()
                    // read without interpreting: only the binary format is under test
                    entry.key
                    entry.value
                    count++
                }
                count
            }
        }
    }

    @Test
    fun probe_solcesto_indexedDB_leveldb_parses_with_idb_cmp1() {
        val fixture = SaveFixtureHarness.loadSolCesto()
        assumeNotNull("solcesto fixture absent — set GAMENATIVE_HTML5_SAVE_FIXTURE_ROOT or commit fixtures", fixture)
        assumeNotNull("solcesto IndexedDB leveldb dir absent", fixture!!.indexedDbLevelDb)
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tempFolder.root, "solcesto-idb")!!

        val count = probeLevelDb(idb, useIdb1 = true)
        assertTrue("expected >= 1 iterable key in solcesto IDB, got $count", count >= 1)

        val prefix = fixture.originPrefix
        assertTrue(
            "solcesto origin prefix should match chrome-extension_<id>_0 but was '$prefix'",
            prefix != null && prefix.startsWith("chrome-extension_") && prefix.endsWith("_0"),
        )
    }

    @Test
    fun probe_solcesto_localStorage_leveldb_parses() {
        val fixture = SaveFixtureHarness.loadSolCesto()
        assumeNotNull("solcesto fixture absent", fixture)
        assumeNotNull("solcesto Local Storage leveldb dir absent", fixture!!.localStorageLevelDb)
        val ls = SaveFixtureHarness.snapshotDir(fixture.localStorageLevelDb, tempFolder.root, "solcesto-ls")!!
        val count = probeLevelDb(ls)
        // C3 games keep saves in IDB, so an empty LS is ground truth; only assert it OPENS
        assertTrue("expected ≥ 0 iterable keys in solcesto localStorage, got $count", count >= 0)
    }

    @Test
    fun probe_lookOutside_localStorage_leveldb_parses() {
        val fixture = SaveFixtureHarness.loadLookOutside()
        assumeNotNull("lookOutside fixture absent", fixture)
        assumeNotNull("lookOutside Local Storage leveldb dir absent", fixture!!.localStorageLevelDb)
        val ls = SaveFixtureHarness.snapshotDir(fixture.localStorageLevelDb, tempFolder.root, "lookoutside-ls")!!
        val count = probeLevelDb(ls)
        // the PC LS leveldb is empty (0-byte .log, no .ldb); 0 keys is ground truth, not a parse failure
        assertTrue("expected ≥ 0 iterable keys in lookOutside localStorage (C3 uses IDB, LS is empty), got $count", count >= 0)
    }

    @Test
    fun probe_lookOutside_indexedDB_leveldb_parses_with_idb_cmp1() {
        val fixture = SaveFixtureHarness.loadLookOutside()
        assumeNotNull("lookOutside fixture absent", fixture)
        assumeNotNull("lookOutside IndexedDB leveldb dir absent", fixture!!.indexedDbLevelDb)
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tempFolder.root, "lookoutside-idb")!!

        val count = probeLevelDb(idb, useIdb1 = true)
        assertTrue("expected >= 1 iterable key in lookOutside IDB, got $count", count >= 1)
    }
}
