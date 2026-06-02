package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// debugging tool: walks an IDB leveldb, dumps every key and value (hex + first ~256 bytes)
// to find which record carries the slot-0 save body whose SSV envelope WebView 109 chokes on.

// usage: temporarily un-@Ignore + run:
// ./gradlew :app:testDebugUnitTest --tests 'app.gamenative.html5.savesync.IdbRecordDump'
// reads from PATH below, writes to /tmp/wayward-idb-dump.txt.
@Ignore("debug tool — un-ignore on demand")
class IdbRecordDump {

    private val ldbPath = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    private val outPath = "/tmp/wayward-idb-dump.txt"

    @Test
    fun dump() {
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        val out = StringBuilder()
        Iq80DBFactory.factory.open(File(ldbPath), opts).use { db ->
            db.iterator().use { it ->
                it.seekToFirst()
                var n = 0
                while (it.hasNext()) {
                    val entry = it.next()
                    val k = entry.key
                    val v = entry.value
                    out.appendLine("--- record #$n keyLen=${k.size} valueLen=${v.size}")
                    out.appendLine("key  hex: ${hex(k, 64)}")
                    out.appendLine("key ascii: ${ascii(k, 64)}")
                    out.appendLine("val hex: ${hex(v, 256)}")
                    out.appendLine()
                    n++
                }
                out.appendLine("=== total: $n records")
            }
        }
        File(outPath).writeText(out.toString())
        println("Wrote ${out.length} chars to $outPath")
    }

    private fun hex(b: ByteArray, max: Int): String {
        val n = minOf(b.size, max)
        return (0 until n).joinToString(" ") { "%02x".format(b[it]) } +
            if (b.size > max) " ...(${b.size - max} more)" else ""
    }

    private fun ascii(b: ByteArray, max: Int): String {
        val n = minOf(b.size, max)
        return (0 until n).joinToString("") {
            val c = b[it].toInt() and 0xFF
            if (c in 0x20..0x7E) c.toChar().toString() else "."
        } + if (b.size > max) "...(${b.size - max} more)" else ""
    }
}
