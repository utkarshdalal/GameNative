package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// diff: what keys exist in post-open webview that WEREN'T in our inbound write?
// replays inbound against /tmp/wayward-idb (pre-inbound wine source), collects the
// key set we intended to write, then diffs against post-open webview state.
// outputs added keys (Chromium injected) and removed keys (Chromium deleted).
@Ignore("one-shot diagnostic — set srcLdb + postOpenLdb to local pulls to run manually")
class PostOpenDiffProbe {

    private val srcLdb = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    private val blobDir = "/tmp/wayward-original-blob"
    private val postOpenLdb =
        "/tmp/wayward-webview-post-open/app_webview/Default/IndexedDB/https_game-steam_379210_0.indexeddb.leveldb"
    private val diffOutPath = "/tmp/wayward-post-open-diff.txt"

    @Test
    fun diff() {
        // rename .ldb → .sst on post-open (chromium writes .ldb)
        File(postOpenLdb).listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().forEach { ldb ->
            ldb.renameTo(File(ldb.parentFile, ldb.nameWithoutExtension + ".sst"))
        }

        // 1. compute "our expected write set" by replaying inbound logic in memory (not writing)
        val fromBytes = OriginCodec.utf16BePrefixBytes("file__0")
        val toBytes = OriginCodec.utf16BePrefixBytes("https_game-steam_379210_0")
        val expected = mutableSetOf<KeyWrapper>()
        val opts = Options().apply {
            createIfMissing(false); paranoidChecks(false)
            compressionType(CompressionType.SNAPPY); comparator(Idb1Comparator())
        }
        Iq80DBFactory.factory.open(File(srcLdb), opts).use { src ->
            // mirror our blob_info skip logic
            val blobInfoKeys = mutableSetOf<KeyWrapper>()
            src.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    val k = iter.next().key
                    if (k.size >= 4 && k[0] == 0.toByte() && k[3] == 0x03.toByte()) {
                        blobInfoKeys += KeyWrapper(k)
                    }
                }
            }
            src.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    val e = iter.next()
                    if (KeyWrapper(e.key) in blobInfoKeys) continue
                    val newKey = LevelDbRewriter.rewriteIdbDatabaseNameKey(e.key, fromBytes, toBytes)
                    expected += KeyWrapper(newKey ?: e.key)
                }
            }
        }
        println("expected write set size: ${expected.size}")

        // 2. read post-open keys
        val actual = mutableSetOf<KeyWrapper>()
        Iq80DBFactory.factory.open(File(postOpenLdb), opts).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) actual += KeyWrapper(iter.next().key)
            }
        }
        println("actual post-open set size: ${actual.size}")

        // 3. diff
        val added = actual - expected
        val removed = expected - actual
        val out = StringBuilder()
        out.appendLine("expected=${expected.size} actual=${actual.size} added=${added.size} removed=${removed.size}")
        out.appendLine("\n=== ADDED by Chromium (in post-open, not in our write) ===")
        for (k in added) {
            out.appendLine("keyLen=${k.bytes.size}")
            out.appendLine("  hex: ${hex(k.bytes, 80)}")
            out.appendLine("  ascii: ${ascii(k.bytes, 120)}")
        }
        out.appendLine("\n=== REMOVED by Chromium (in our write, not in post-open) ===")
        for (k in removed) {
            out.appendLine("keyLen=${k.bytes.size}")
            out.appendLine("  hex: ${hex(k.bytes, 80)}")
            out.appendLine("  ascii: ${ascii(k.bytes, 120)}")
        }
        File(diffOutPath).writeText(out.toString())
        println("wrote ${out.length} chars to $diffOutPath")
    }

    private data class KeyWrapper(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is KeyWrapper && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
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
