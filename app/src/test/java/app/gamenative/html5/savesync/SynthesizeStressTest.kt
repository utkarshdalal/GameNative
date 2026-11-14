package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// iq80's background flush races the writer and intermittently drops one SST's records (a flaky "missing key
// after synthesize", rare in isolation). the build/read opens MUST keep compactionEnabled(false); 100 serial
// iterations with forced GC reliably expose a regression. kept lean (~6s, no concurrent variant).
class SynthesizeStressTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private val padding = "x".repeat(2048)
    private val keys = (1..20).map { "key%02d".format(it) to "v$it-$padding" }

    // build a multi-SST snappy leveldb, break its manifest, synthesize, reopen; returns the readable keys
    private fun runScenario(label: String): Set<String> {
        val dir = tempFolder.newFolder("ldb-$label")
        run {
            val opts = Options().apply {
                createIfMissing(true)
                compressionType(CompressionType.SNAPPY)
                writeBufferSize(4096) // spill on every 2KB put -> ~20 SSTs
                compactionEnabled(false) // synchronous flush: no background-flush vs writer race
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
            compactionEnabled(false) // no racing background compaction
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
