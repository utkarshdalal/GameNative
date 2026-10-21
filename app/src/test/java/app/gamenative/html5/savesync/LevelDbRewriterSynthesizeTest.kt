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

// recreates a cloud-restored leveldb whose MANIFEST references files not on disk; iq80 must open the
// post-synthesis state cleanly.
class LevelDbRewriterSynthesizeTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test fun synthesize_replacesBrokenManifest_iq80OpensAndIteratesCleanly() {
        val dir = tempFolder.newFolder("ldb")

        // iq80's compactRange is unsupported, so force SSTs via a buffer smaller than each value
        val padding = "x".repeat(2048)
        val keys = (1..20).map { "key%02d".format(it) to "v$it-$padding" }
        run {
            val opts = Options().apply {
                createIfMissing(true)
                compressionType(CompressionType.SNAPPY) // chromium's default
                writeBufferSize(4096)
                // synchronous flush: the background path unlocks the mutex during writeLevel0Table, racing the
                // writer and intermittently dropping one SST's records (a flaky "missing key"). the iq80 fork's
                // ThreadLocal scratch fixed the decompression race, NOT this one. see iq80-leveldb/NOTICE.md.
                compactionEnabled(false)
            }
            val db = Iq80DBFactory.factory.open(dir, opts)
            for ((k, v) in keys) {
                db.put(k.toByteArray(Charsets.UTF_8), v.toByteArray(Charsets.UTF_8))
            }
            db.close()
        }

        // production runs synthesizeManifest inside withLdbAsSst
        dir.listFiles { _, name -> name.endsWith(".ldb") }?.forEach { ldb ->
            val sst = File(ldb.parentFile, ldb.nameWithoutExtension + ".sst")
            assertTrue("rename ${ldb.name} → ${sst.name}", ldb.renameTo(sst))
        }
        val sstCount = dir.listFiles { _, name -> name.endsWith(".sst") }?.size ?: 0
        assertTrue("test fixture must have at least 1 SST after compaction (have $sstCount)", sstCount >= 1)

        // stands in for a cloud-delivered manifest that references no files on disk
        dir.listFiles { _, name -> name.startsWith("MANIFEST-") }?.forEach {
            assertTrue("delete original manifest", it.delete())
        }
        val brokenName = "MANIFEST-000099"
        File(dir, brokenName).writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0))
        File(dir, "CURRENT").writeText("$brokenName\n")

        LeveldbManifestSynthesizer.synthesizeManifest(dir, useIdb1 = false)

        val manifests = dir.listFiles { _, name -> name.startsWith("MANIFEST-") }.orEmpty().toList()
        assertEquals("exactly one manifest should remain post-synthesize", 1, manifests.size)
        assertNotEquals("synthesized manifest must replace the broken one", brokenName, manifests[0].name)
        val current = File(dir, "CURRENT").readText().trim()
        assertEquals("CURRENT must point at the synthesized manifest", manifests[0].name, current)

        val foundKeys = mutableSetOf<String>()
        run {
            val opts = Options().apply {
                createIfMissing(false)
                compressionType(CompressionType.SNAPPY)
                compactionEnabled(false) // no background compaction racing the iterator
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

        for ((k, _) in keys) {
            assertTrue("expected $k in post-synthesize iter (got $foundKeys)", k in foundKeys)
        }
    }

    @Test fun synthesize_emptyDir_isNoop() {
        val dir = tempFolder.newFolder("ldb-empty")
        File(dir, "CURRENT").writeText("MANIFEST-000001\n")
        File(dir, "MANIFEST-000001").writeBytes(byteArrayOf(1, 2, 3))

        LeveldbManifestSynthesizer.synthesizeManifest(dir, useIdb1 = false)

        assertEquals("MANIFEST-000001\n", File(dir, "CURRENT").readText())
        assertTrue(File(dir, "MANIFEST-000001").exists())
    }
}
