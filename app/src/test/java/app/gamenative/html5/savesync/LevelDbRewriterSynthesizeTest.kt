package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// end-to-end test for synthesizeManifest. recreates the production failure mode (a
// chromium-leveldb dir whose MANIFEST references files not on disk) and asserts iq80 can
// open the post-synthesis state cleanly. drift-locks the entire cross-device-restore flow.
class LevelDbRewriterSynthesizeTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun synthesize_replacesBrokenManifest_iq80OpensAndIteratesCleanly() {
        val dir = tempFolder.newFolder("ldb")

        // step 1: build a real leveldb with known data. iq80's compactRange is
        // UnsupportedOperationException, so force memtable flush via writeBufferSize: small
        // buffer + bulky values → memtable spills repeatedly, each spill produces an SST.
        // a single sufficiently-bulky put can also force flush (buffer overflows on it).
        val padding = "x".repeat(2048) // each value is 2KB → small buffer fills fast
        val keys = (1..20).map { "key%02d".format(it) to "v$it-$padding" }
        run {
            val opts = Options().apply {
                createIfMissing(true)
                compressionType(CompressionType.SNAPPY) // chromium's default — drift-lock the real flow
                writeBufferSize(4096) // smaller than each value → spill on every put → many SSTs
                // compactionEnabled(false) => memtable flushes happen synchronously on the writer
                // thread. The background-flush path unlocks the mutex during the SST write
                // (writeLevel0Table), racing the ongoing writer and intermittently dropping one
                // SST's records — observed as a flaky "missing key" ~1/3 of full-suite runs. The
                // per-thread ThreadLocal scratch (iq80-leveldb fork) fixed the *decompression* race
                // but NOT this *flush* race; synchronous flush closes it. see iq80-leveldb/NOTICE.md.
                compactionEnabled(false)
            }
            val db = Iq80DBFactory.factory.open(dir, opts)
            for ((k, v) in keys) {
                db.put(k.toByteArray(Charsets.UTF_8), v.toByteArray(Charsets.UTF_8))
            }
            db.close()
        }

        // step 2: rename .ldb → .sst (mirrors withLdbAsSst that runs in production before
        // synthesizeManifest is called).
        dir.listFiles { _, name -> name.endsWith(".ldb") }?.forEach { ldb ->
            val sst = File(ldb.parentFile, ldb.nameWithoutExtension + ".sst")
            assertTrue("rename ${ldb.name} → ${sst.name}", ldb.renameTo(sst))
        }
        val sstCount = dir.listFiles { _, name -> name.endsWith(".sst") }?.size ?: 0
        assertTrue("test fixture must have at least 1 SST after compaction (have $sstCount)", sstCount >= 1)

        // step 3: corrupt the manifest. write a deliberately-broken MANIFEST that's
        // plausibly-formatted leveldb log records but references no existing files (and
        // contains nonsense for the rest). point CURRENT at it. simulates the production
        // case where Steam Cloud delivered a phantom-reference manifest.
        dir.listFiles { _, name -> name.startsWith("MANIFEST-") }?.forEach {
            assertTrue("delete original manifest", it.delete())
        }
        val brokenName = "MANIFEST-000099"
        File(dir, brokenName).writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0))
        File(dir, "CURRENT").writeText("$brokenName\n")

        // step 4: synthesize. should produce a fresh manifest referencing only on-disk SSTs.
        LeveldbManifestSynthesizer.synthesizeManifest(dir, useIdb1 = false)

        val manifests = dir.listFiles { _, name -> name.startsWith("MANIFEST-") }.orEmpty().toList()
        assertEquals("exactly one manifest should remain post-synthesize", 1, manifests.size)
        assertNotEquals("synthesized manifest must replace the broken one", brokenName, manifests[0].name)
        val current = File(dir, "CURRENT").readText().trim()
        assertEquals("CURRENT must point at the synthesized manifest", manifests[0].name, current)

        // step 5: iq80 must now be able to open and iterate. drift-lock — if the
        // synthesized wire format ever drifts, this fails noisily here, not silently in prod.
        val foundKeys = mutableSetOf<String>()
        run {
            val opts = Options().apply {
                createIfMissing(false)
                compressionType(CompressionType.SNAPPY)
                compactionEnabled(false) // read-only verify: no background compaction racing the iterator
            }
            val db = Iq80DBFactory.factory.open(dir, opts)
            db.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    foundKeys.add(String(iter.next().key, Charsets.UTF_8))
                }
            }
            db.close()
        }

        // every key we wrote pre-synthesize should be readable post-synthesize.
        for ((k, _) in keys) {
            assertTrue("expected $k in post-synthesize iter (got $foundKeys)", k in foundKeys)
        }
    }

    @Test fun synthesize_emptyDir_isNoop() {
        // no .sst files → synthesizeManifest must leave CURRENT alone (no fresh manifest
        // to write, but also no harm done).
        val dir = tempFolder.newFolder("ldb-empty")
        File(dir, "CURRENT").writeText("MANIFEST-000001\n")
        File(dir, "MANIFEST-000001").writeBytes(byteArrayOf(1, 2, 3))

        LeveldbManifestSynthesizer.synthesizeManifest(dir, useIdb1 = false)

        // unchanged
        assertEquals("MANIFEST-000001\n", File(dir, "CURRENT").readText())
        assertTrue(File(dir, "MANIFEST-000001").exists())
    }
}
