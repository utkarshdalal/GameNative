package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// one-shot: scans /tmp/wayward-idb/... for the single FF 11 02 record
// (kCompressedWithSnappy) and writes its raw value bytes to the test resources dir.
// un-ignore, run once, re-ignore — the captured fixture feeds LevelDbRewriterSnappyValueTest.
@Ignore("one-shot — un-ignore to regenerate wayward-ff1102-thumbnail.bin")
class Ff1102FixtureExtractor {

    private val ldbPath = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    // cwd is the module dir (`app/`) when run via gradle; skip the leading `app/`.
    private val dst = File("src/test/resources/html5-saves/wayward-ff1102-thumbnail.bin")

    @Test
    fun extract() {
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        dst.parentFile?.mkdirs()
        var written = 0
        Iq80DBFactory.factory.open(File(ldbPath), opts).use { db ->
            db.iterator().use { it ->
                it.seekToFirst()
                while (it.hasNext()) {
                    val e = it.next()
                    val v = e.value
                    if (v.size < 4) continue
                    // after leading varint, match FF 11 02
                    val (_, after) = LevelDbRewriter.decodeLeb128At(v, 0) ?: continue
                    if (v.size < after + 3) continue
                    if ((v[after].toInt() and 0xFF) == 0xFF &&
                        (v[after + 1].toInt() and 0xFF) == 0x11 &&
                        (v[after + 2].toInt() and 0xFF) == 0x02
                    ) {
                        dst.writeBytes(v)
                        written++
                        println("wrote ${v.size} bytes to ${dst.absolutePath}")
                        break
                    }
                }
            }
        }
        if (written == 0) error("no FF 11 02 record found in $ldbPath")
    }
}
