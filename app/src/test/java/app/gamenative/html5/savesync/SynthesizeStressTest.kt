package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// Regression guard for the iq80 synchronous-flush fix (Options.compactionEnabled(false)).
// Before the fix, building a multi-SST snappy leveldb let the background-flush thread race the
// writer and intermittently drop one SST's records — a flaky "missing key after synthesize"
// (~1/3 of full-suite runs, but only ~1/600 in isolation). 100 serial iterations with forced GC
// reliably caught it (failed at iter 1-52). The build/read opens MUST keep compactionEnabled(false);
// if synchronous flush regresses, this fails fast. ~6s — kept lean (no concurrent variant).
class SynthesizeStressTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val padding = "x".repeat(2048)
    private val keys = (1..20).map { "key%02d".format(it) to "v$it-$padding" }

    // one full scenario: build a multi-SST snappy leveldb, break its manifest, synthesize, reopen,
    // return the set of keys readable post-synthesize. label is for the temp dir uniqueness.
    private fun runScenario(label: String): Set<String> {
        val dir = tempFolder.newFolder("ldb-$label")
        run {
            val opts = Options().apply {
                createIfMissing(true)
                compressionType(CompressionType.SNAPPY)
                writeBufferSize(4096) // spill on every 2KB put → ~20 SSTs
                compactionEnabled(false) // synchronous flush — no background-flush vs writer race
            }
            val db = Iq80DBFactory.factory.open(dir, opts)
            for ((k, v) in keys) {
                db.put(k.toByteArray(Charsets.UTF_8), v.toByteArray(Charsets.UTF_8))
            }
            db.close()
        }
        dir.listFiles { _, name -> name.endsWith(".ldb") }?.forEach { ldb ->
            ldb.renameTo(File(ldb.parentFile, ldb.nameWithoutExtension + ".sst"))
        }
        dir.listFiles { _, name -> name.startsWith("MANIFEST-") }?.forEach { it.delete() }
        File(dir, "MANIFEST-000099").writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0))
        File(dir, "CURRENT").writeText("MANIFEST-000099\n")

        LeveldbManifestSynthesizer.synthesizeManifest(dir, useIdb1 = false)

        val found = mutableSetOf<String>()
        val opts = Options().apply {
            createIfMissing(false)
            compressionType(CompressionType.SNAPPY)
            compactionEnabled(false) // read-only verify: no racing background compaction
        }
        val db = Iq80DBFactory.factory.open(dir, opts)
        db.iterator().use { iter ->
            iter.seekToFirst()
            while (iter.hasNext()) {
                found.add(String(iter.next().key, Charsets.UTF_8))
            }
        }
        db.close()
        return found
    }

    private fun assertAllKeys(iter: Int, found: Set<String>) {
        val missing = keys.map { it.first }.filter { it !in found }
        if (missing.isNotEmpty()) {
            fail("iter $iter: MISSING ${missing.size} key(s) $missing — found ${found.size}/${keys.size} ($found)")
        }
    }

    @Test
    fun stress_serial_withGc() {
        val n = 100
        for (i in 1..n) {
            assertAllKeys(i, runScenario("serial-$i"))
            if (i % 5 == 0) {
                System.gc()
                System.runFinalization()
            }
        }
    }
}
