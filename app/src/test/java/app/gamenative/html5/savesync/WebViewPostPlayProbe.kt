package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// one-shot: dumps webview's post-play leveldb + counts DBKeys with "wayward" origin.
// answers: did webview retain wayward_2.11 DBKey through gameplay?
@Ignore("one-shot diagnostic — set ldbPath to a local leveldb pull to run manually")
class WebViewPostPlayProbe {

    private val ldbPath = "/tmp/wayward-webview-post-open/app_webview/Default/IndexedDB/https_game-steam_379210_0.indexeddb.leveldb"

    @Test
    fun dumpAndCount() {
        // iq80 wants .sst files, chromium writes .ldb — rename before open (matches
        // LevelDbRewriter.withLdbAsSst). no need to rename back, this is read-only.
        val dir = File(ldbPath)
        if (dir.isDirectory) {
            dir.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().forEach { ldb ->
                val sst = File(ldb.parentFile, ldb.nameWithoutExtension + ".sst")
                ldb.renameTo(sst)
            }
        }
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        val waywardUtf16Be = "wayward_2.11".toCharArray().flatMap { c ->
            listOf((c.code ushr 8).toByte(), (c.code and 0xFF).toByte())
        }.toByteArray()
        val out = StringBuilder()
        var n = 0; var dbKeys = 0; var waywardDbKey = false
        Iq80DBFactory.factory.open(File(ldbPath), opts).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    val e = iter.next()
                    val k = e.key
                    n++
                    val isDbName = k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
                        k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9
                    if (isDbName) {
                        dbKeys++
                        val ascii = asciiInterleave(k)
                        out.appendLine("DBKey #$dbKeys: $ascii")
                        if (indexOf(k, waywardUtf16Be) >= 0) waywardDbKey = true
                    }
                }
            }
        }
        File("/tmp/wayward-webview-postplay-dbkeys.txt").writeText(
            "total=$n dbKeys=$dbKeys waywardDbKey=$waywardDbKey\n\n$out"
        )
        println("total=$n dbKeys=$dbKeys waywardDbKey=$waywardDbKey")
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        for (i in 0..h.size - n.size) {
            var m = true
            for (j in n.indices) if (h[i + j] != n[j]) { m = false; break }
            if (m) return i
        }
        return -1
    }

    private fun asciiInterleave(b: ByteArray): String = buildString {
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
    }
}
