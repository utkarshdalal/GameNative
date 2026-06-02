package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// one-shot: walks wine's post-outbound leveldb and flags records we'd expect desktop
// chromium to read. checks for DatabaseNameKey with "file__0" origin, and lists
// every ObjectStore-data record's key + value-head.
@Ignore("one-shot diagnostic — set ldbPath to a local leveldb pull to run manually")
class PostOutboundProbe {

    private val ldbPath = "/tmp/wayward-wine-verify/Steam/steamapps/common/Wayward/save/IndexedDB/file__0.indexeddb.leveldb"
    private val outPath = "/tmp/wayward-wine-verify-dump.txt"

    @Test
    fun dump() {
        // rename .ldb → .sst for iq80 compat (chromium-written dirs use .ldb)
        File(ldbPath).listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().forEach { ldb ->
            ldb.renameTo(File(ldb.parentFile, ldb.nameWithoutExtension + ".sst"))
        }
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
                var dbNameKeyHits = 0
                var dataRecordHits = 0
                while (it.hasNext()) {
                    val entry = it.next()
                    val k = entry.key
                    val v = entry.value
                    val isDbNameKey = k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
                        k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9
                    val isObjectStoreDataKey = k.size >= 5 && k[0] == 0.toByte() && k[3] == 0x01.toByte()
                    if (isDbNameKey) dbNameKeyHits++
                    if (isObjectStoreDataKey) dataRecordHits++
                    out.appendLine("--- record #$n keyLen=${k.size} valueLen=${v.size} dbName=$isDbNameKey osData=$isObjectStoreDataKey")
                    out.appendLine("key  hex: ${hex(k, 64)}")
                    out.appendLine("key ascii: ${ascii(k, 120)}")
                    out.appendLine("val hex: ${hex(v, 80)}")
                    out.appendLine()
                    n++
                }
                out.appendLine("=== total: $n records, DatabaseNameKey hits=$dbNameKeyHits, ObjectStoreData hits=$dataRecordHits")
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
