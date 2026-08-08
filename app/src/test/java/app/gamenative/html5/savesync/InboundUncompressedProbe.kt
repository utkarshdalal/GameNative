package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.Ignore
import org.junit.Test

// scratch: rewrite src keys (origin rewrite only, no inline/decompress) into a dst
// leveldb with CompressionType.NONE so output SST is grep-able. proves whether the
// wayward_2.11 DBKey survives a simple origin-rewrite write.
@Ignore("one-shot diagnostic — set srcLdb to a local leveldb pull to run manually")
class InboundUncompressedProbe {

    private val srcLdb = "/tmp/wayward-idb/file__0.indexeddb.leveldb"
    private val dstDir = "/tmp/wayward-inbound-nocompress"

    @Test
    fun rewriteAndGrep() {
        val dst = File(dstDir)
        if (dst.exists()) dst.deleteRecursively()
        dst.mkdirs()

        val src = File(srcLdb)
        // ensure src files have .sst extension for iq80
        src.listFiles { _, name -> name.endsWith(".ldb") }.orEmpty().forEach { ldb ->
            ldb.renameTo(File(ldb.parentFile, ldb.nameWithoutExtension + ".sst"))
        }

        val fromBytes = OriginCodec.utf16BePrefixBytes("file__0")
        val toBytes = OriginCodec.utf16BePrefixBytes("https_game-steam_379210_0")

        val readOpts = Options().apply {
            createIfMissing(false); paranoidChecks(false)
            compressionType(CompressionType.SNAPPY); comparator(Idb1Comparator())
        }
        val writeOpts = Options().apply {
            createIfMissing(true); errorIfExists(false); paranoidChecks(false)
            compressionType(CompressionType.NONE); comparator(Idb1Comparator())
        }

        var written = 0; var dbKeysWritten = 0; var waywardWritten = false
        val waywardUtf16Be = "wayward_2.11".toCharArray().flatMap { c ->
            listOf((c.code ushr 8).toByte(), (c.code and 0xFF).toByte())
        }.toByteArray()

        Iq80DBFactory.factory.open(src, readOpts).use { srcDb ->
            Iq80DBFactory.factory.open(dst, writeOpts).use { dstDb ->
                srcDb.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        val e = iter.next()
                        val newKey = LevelDbRewriter.rewriteIdbDatabaseNameKey(e.key, fromBytes, toBytes)
                        val outKey = newKey ?: e.key
                        dstDb.put(outKey, e.value)
                        written++
                        val isDbName = outKey.size >= 5 && outKey[0] == 0.toByte() &&
                            outKey[1] == 0.toByte() && outKey[2] == 0.toByte() &&
                            outKey[3] == 0.toByte() && (outKey[4].toInt() and 0xFF) == 0xC9
                        if (isDbName) dbKeysWritten++
                        if (indexOf(outKey, waywardUtf16Be) >= 0) waywardWritten = true
                    }
                }
            }
        }
        println("[write] written=$written dbKeys=$dbKeysWritten waywardWritten=$waywardWritten")

        val waywardHex = waywardUtf16Be.joinToString("") { "%02x".format(it) }
        val grepFindings = dst.listFiles().orEmpty().filter { it.isFile }.map { f ->
            val hex = f.readBytes().joinToString("") { "%02x".format(it) }
            f.name to hex.contains(waywardHex)
        }
        println("[grep-output] wayward_2.11 bytes per-file: $grepFindings")
    }

    private fun indexOf(h: ByteArray, n: ByteArray): Int {
        for (i in 0..h.size - n.size) {
            var m = true
            for (j in n.indices) if (h[i + j] != n[j]) { m = false; break }
            if (m) return i
        }
        return -1
    }
}
