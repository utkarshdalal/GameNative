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

// -2 — LEVELDB PARSE PROBE.
// answers does iq80 pure-java leveldb parse chromium's leveldb flavor?

// chromium's leveldb is the open-source leveldb with snappy compression and
// a custom BytewiseComparator. picked iq80 pure-java over leveldbjni-all:1.8
// post-— leveldbjni:1.8 is a 2013 artifact, ships no osx-arm64 and no
// android-arm64-v8a natives, so it can't run on this host OR the target device.
// iq80 is pure-java, universal ABI. snappy-java is a separate JNI that DOES
// ship android arm64 natives. future migration candidate if perf matters:
// https://jitpack.io/p/jacek-marchwicki/leveldb-jni

// test asserts ONLY: (a) DB opens without crash, (b) at least one key
// iterates successfully. no deeper semantic assertions here — those live
// in plan implementation tests.

// fixtures come from SaveFixtureHarness; missing fixture → assumeNotNull skip.
class LevelDbParseProbeTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun probeLevelDb(levelDbDir: File, useIdb1: Boolean = false): Int {
        val options = Options().apply {
            createIfMissing(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) {
                // — register the kotlin-ported idb_cmp1 so iq80's MANIFEST check
                // passes for chromium IndexedDB leveldb databases.
                comparator(Idb1Comparator())
            }
        }
        // pure-java factory — no JNI load; close cleanly via use{}.
        return Iq80DBFactory.factory.open(levelDbDir, options).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                var count = 0
                while (iter.hasNext() && count < 10) {
                    val entry = iter.next()
                    // just read; don't interpret. proves the binary-level format is parseable.
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

        // CLOSED the gap: idb_cmp1 ported to kotlin, registered via
        // Options.comparator(Idb1Comparator()). iq80 now opens chromium IDB databases cleanly.
        val count = probeLevelDb(idb, useIdb1 = true)
        assertTrue("expected >= 1 iterable key in solcesto IDB, got $count", count >= 1)

        // origin prefix shape check (feeds 
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
        // localStorage may be effectively empty (0 keys) if C3 only uses IndexedDB.
        // we only assert that it OPENS — count == 0 is acceptable.
        assertTrue("expected ≥ 0 iterable keys in solcesto localStorage, got $count", count >= 0)
    }

    @Test
    fun probe_lookOutside_localStorage_leveldb_parses() {
        val fixture = SaveFixtureHarness.loadLookOutside()
        assumeNotNull("lookOutside fixture absent", fixture)
        assumeNotNull("lookOutside Local Storage leveldb dir absent", fixture!!.localStorageLevelDb)
        val ls = SaveFixtureHarness.snapshotDir(fixture.localStorageLevelDb, tempFolder.root, "lookoutside-ls")!!
        val count = probeLevelDb(ls)
        // lookOutside + solcesto both ship empty Local Storage/leveldb/ on PC (0-byte .log, tiny MANIFEST,
        // no .ldb SST). C3 games use IndexedDB only— C3 localStorage plugin is really IDB).
        // 0 iterable keys is GROUND TRUTH, not a parse failure — we just want "db opens without crash".
        assertTrue("expected ≥ 0 iterable keys in lookOutside localStorage (C3 uses IDB, LS is empty), got $count", count >= 0)
    }

    @Test
    fun probe_lookOutside_indexedDB_leveldb_parses_with_idb_cmp1() {
        val fixture = SaveFixtureHarness.loadLookOutside()
        assumeNotNull("lookOutside fixture absent", fixture)
        assumeNotNull("lookOutside IndexedDB leveldb dir absent", fixture!!.indexedDbLevelDb)
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tempFolder.root, "lookoutside-idb")!!

        // — idb_cmp1 kotlin port makes real round-trip possible.
        val count = probeLevelDb(idb, useIdb1 = true)
        assertTrue("expected >= 1 iterable key in lookOutside IDB, got $count", count >= 1)
    }
}
