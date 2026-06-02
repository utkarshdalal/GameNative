package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// reproduces inbound origin-rewrite against the pre-inbound wine leveldb
// to verify all 3 DatabaseNameKeys survive. iq80-only — no device, no webview.
// probes two passes:
// (1) without blob inlining (isolates DBKey behavior)
// (2) with blob inlining pointed at reconstructed blob dir
@Ignore("one-shot diagnostic — set srcLdb + blobDir to local pulls to run manually")
class DbNameKeyLossProbe {

    private val srcLdb = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    private val blobDir = "/tmp/wayward-original-blob"

    @Test
    fun rewriteWithoutInline_preservesAllDBKeys() {
        val dst = File("/tmp/wayward-rewrite-noinline")
        if (dst.exists()) dst.deleteRecursively()
        LevelDbRewriter.rewriteIdbOrigin(
            src = File(srcLdb),
            dst = dst,
            fromOriginFilename = "file__0",
            toOriginFilename = "https_game-steam_379210_0",
            inlineBlobsFromDir = null,
        )
        val (total, dbKeys, waywardSeen) = countKeys(dst)
        println("[noinline] total=$total dbKeys=$dbKeys waywardSeen=$waywardSeen")
    }

    @Test
    fun rewriteWithInline_preservesAllDBKeys() {
        val dst = File("/tmp/wayward-rewrite-inline")
        if (dst.exists()) dst.deleteRecursively()
        LevelDbRewriter.rewriteIdbOrigin(
            src = File(srcLdb),
            dst = dst,
            fromOriginFilename = "file__0",
            toOriginFilename = "https_game-steam_379210_0",
            inlineBlobsFromDir = File(blobDir),
        )
        // try to reopen — may fail w/ iq80 strictness on the multi-MB output. fall back
        // to counting via direct scan of the src leveldb during a DRY-RUN rewrite.
        val reopenResult = runCatching { countKeys(dst) }
        println("[inline-reopen] ${reopenResult.getOrNull() ?: "FAILED: ${reopenResult.exceptionOrNull()?.message}"}")
    }

    // DRY RUN — iterate src, apply the same logic as rewriteIdbOrigin's inline branch,
    // but count key classifications instead of writing. isolates whether our rewriter
    // drops or corrupts the wayward_2.11 DBKey during the transform pipeline.
    @Test
    fun dryRunInlinePath_trackAllDBKeys() {
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        val fromBytes = OriginCodec.utf16BePrefixBytes("file__0")
        val toBytes = OriginCodec.utf16BePrefixBytes("https_game-steam_379210_0")
        val waywardUtf16Be = "wayward_2.11".toCharArray().flatMap { c ->
            listOf((c.code ushr 8).toByte(), (c.code and 0xFF).toByte())
        }.toByteArray()
        var seen = 0; var written = 0; var dbKeys = 0; var waywardRewritten = 0
        var waywardPassthrough = 0; var dbKeyNullRewrites = 0
        Iq80DBFactory.factory.open(File(srcLdb), opts).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                while (iter.hasNext()) {
                    val e = iter.next()
                    val k = e.key
                    seen++
                    val isDbName = k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
                        k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9
                    val hasWayward = indexOf(k, waywardUtf16Be) >= 0
                    if (isDbName) dbKeys++
                    val newKey = LevelDbRewriter.rewriteIdbDatabaseNameKey(k, fromBytes, toBytes)
                    if (isDbName && newKey == null) dbKeyNullRewrites++
                    if (hasWayward && newKey != null) waywardRewritten++
                    if (hasWayward && newKey == null) waywardPassthrough++
                    written++
                }
            }
        }
        println("[dry] seen=$seen written=$written dbKeysSrc=$dbKeys " +
            "dbKeyNullRewrites=$dbKeyNullRewrites " +
            "waywardRewritten=$waywardRewritten waywardPassthrough=$waywardPassthrough")
    }

    private fun countKeys(dir: File): Triple<Int, Int, Boolean> {
        val opts = Options().apply {
            createIfMissing(false)
            paranoidChecks(false)
            compressionType(CompressionType.SNAPPY)
            comparator(Idb1Comparator())
        }
        var total = 0
        var dbKeys = 0
        var waywardSeen = false
        val waywardUtf16Be = "wayward_2.11".toCharArray().flatMap { c ->
            listOf((c.code ushr 8).toByte(), (c.code and 0xFF).toByte())
        }.toByteArray()
        Iq80DBFactory.factory.open(dir, opts).use { db ->
            db.iterator().use { it ->
                it.seekToFirst()
                while (it.hasNext()) {
                    val e = it.next()
                    val k = e.key
                    total++
                    val isDbName = k.size >= 5 && k[0] == 0.toByte() && k[1] == 0.toByte() &&
                        k[2] == 0.toByte() && k[3] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9
                    if (isDbName) dbKeys++
                    if (indexOf(k, waywardUtf16Be) >= 0) waywardSeen = true
                }
            }
        }
        return Triple(total, dbKeys, waywardSeen)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (i in 0..haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) { match = false; break }
            }
            if (match) return i
        }
        return -1
    }
}
