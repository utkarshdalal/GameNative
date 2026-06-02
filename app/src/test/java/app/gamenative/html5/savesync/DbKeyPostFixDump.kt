// one-shot dump: open /tmp/wayward-post-fix webview leveldb, count DBKeys + dump
package app.gamenative.html5.savesync
import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Test

class DbKeyPostFixDump {
    private val paths = listOf(
        "/tmp/wayward-after-save/app_webview/Default/IndexedDB/https_game-steam_379210_0.indexeddb.leveldb",
    )
    @Test fun dump() {
        val opts = Options().apply {
            createIfMissing(false); paranoidChecks(false)
            compressionType(CompressionType.SNAPPY); comparator(Idb1Comparator())
        }
        val out = StringBuilder()
        for (path in paths) {
            val dir = File(path)
            if (!dir.isDirectory) { out.appendLine("SKIP (no dir): $path"); continue }
            dir.listFiles { _, n -> n.endsWith(".ldb") }.orEmpty().forEach { it.renameTo(File(it.parentFile, it.nameWithoutExtension + ".sst")) }
            out.appendLine("=== $path ===")
            try {
                Iq80DBFactory.factory.open(dir, opts).use { db ->
                    var n = 0; var dbKeys = 0; var verKey = 0; var osData = 0; val dbIdOsDataCount = mutableMapOf<Int, Int>()
                    db.iterator().use { iter ->
                        iter.seekToFirst()
                        while (iter.hasNext()) {
                            val e = iter.next(); val k = e.key; n++
                            val ascii = k.map { val b = it.toInt() and 0xFF; if (b in 0x20..0x7E) b.toChar() else '.' }.joinToString("")
                            val hex8 = k.take(8).joinToString("") { "%02x".format(it) }
                            out.appendLine("  [$n] len=${k.size} hex8=$hex8 ascii='$ascii'")
                            if (k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() && k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9) {
                                dbKeys++
                            }
                            if (k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() && k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0x00) {
                                val vASCII = e.value.map { val b = it.toInt() and 0xFF; if (b in 0x20..0x7E) b.toChar() else '.' }.joinToString("")
                                out.appendLine("  MaxDbIdKey? key=${k.joinToString("") { "%02x".format(it) }} value=${e.value.joinToString("") { "%02x".format(it) }}")
                            }
                            if (k.size >= 5 && k[0] == 0.toByte() && k[3] == 0x01.toByte()) {
                                osData++
                                val dbId = k[1].toInt() and 0xFF
                                dbIdOsDataCount[dbId] = (dbIdOsDataCount[dbId] ?: 0) + 1
                            }
                        }
                    }
                    out.appendLine("  total=$n dbKeys=$dbKeys osData=$osData per-dbId=$dbIdOsDataCount")
                }
            } catch (t: Throwable) { out.appendLine("ERROR: ${t.message}") }
        }
        File("/tmp/dbkey-post-fix-dump.txt").writeText(out.toString())
        println(out.toString())
    }
}
