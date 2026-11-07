package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.DB
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.DbImpl
import org.iq80.leveldb.impl.Iq80DBFactory

// synthesizes chromium-valid IDB + LS leveldbs. synthesized over committed snapshots because real IDB
// fixtures run to tens of MB; these are reproducible, tiny, PII-free, and encode only the structural
// invariants tests assert. SaveFixtureHarness still covers on-disk validation via an env-var stash.

// origin encoding:
// IDB DatabaseNameKey -- UTF-16BE ("StringWithLength"), FILENAME-form origin (e.g. "https_game-steam_379210_0").
// NOT the UTF-16LE that Idb1ComparatorTest uses for IDBKey payload bytes.
// LS keys -- US_ASCII, URL-form origin (e.g. "https://game-steam_379210").
object FixtureBuilder {

    // one DatabaseNameKey (origin-bearing) + one internal db-id key (must pass through rewrite verbatim).
    fun idbWithDatabaseName(
        dir: File,
        originFilename: String,
        databaseName: String,
        partitionSuffix: String = "",
    ) {
        dir.mkdirs()
        openDb(dir, useIdb1 = true).use { db ->
            // DatabaseNameKey layout per chromium leveldb_coding_scheme.md, confirmed against a desktop probe:
            // [0x00, 0x00, 0x00, 0x00, 0xC9] -- 5-byte KeyPrefix:
            // byte 0: length-packed byte (db/obj/idx sizes each -1, zero for metadata keys)
            // bytes 1-3: varints db_id=0, obj_store=0, idx=0
            // byte 4: type byte 0xC9 = DatabaseNameKey (201)
            // varint(code unit count of origin) -- LEB128
            // UTF-16BE bytes of origin (2 * code unit count)
            // varint(code unit count of database name)
            // UTF-16BE bytes of database name

            // partitionSuffix: optional `@<n>` storage-partitioning tail (Chromium 105+), appended verbatim.
            val fullOrigin = originFilename + partitionSuffix
            val originUtf16Be = fullOrigin.toByteArray(Charsets.UTF_16BE)
            val dbNameUtf16Be = databaseName.toByteArray(Charsets.UTF_16BE)
            val databaseNameKey =
                byteArrayOf(0, 0, 0, 0, 0xC9.toByte()) +
                    encodeLeb128((originUtf16Be.size / 2).toLong()) +
                    originUtf16Be +
                    encodeLeb128((dbNameUtf16Be.size / 2).toLong()) +
                    dbNameUtf16Be
            db.put(databaseNameKey, byteArrayOf(42))

            // key prefix byte 0x01 = db_id=1 1-byte, obj_store=42 1-byte, idx=1 1-byte.
            db.put(byteArrayOf(1, 42, 0, 1, 0, 0, 0, 1), byteArrayOf(1, 2, 3))

            // without a flush, tiny DBs stay in the WAL (.log) and sstRenameToLdb finds nothing.
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    // bytewise comparator, NOT idb_cmp1 -- LS uses lexicographic byte order.
    fun lsWithOrigins(
        dir: File,
        vararg originAndKV: Pair<String, Map<String, ByteArray>>,
    ) {
        dir.mkdirs()
        openDb(dir, useIdb1 = false).use { db ->
            for ((url, keyValues) in originAndKV) {
                // origin at offset 5 after "META:"
                val metaKey = "META:$url".toByteArray(Charsets.US_ASCII)
                db.put(metaKey, byteArrayOf(1))

                // _<url><NUL><user-key>: origin at offset 1, NUL-terminated
                for ((k, v) in keyValues) {
                    val lsKey = byteArrayOf('_'.code.toByte()) +
                        url.toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0) +
                        k.toByteArray(Charsets.US_ASCII)
                    db.put(lsKey, v)
                }
            }
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    // for tests that need non-origin-bearing keys alongside origin keys.
    fun putRaw(dir: File, key: ByteArray, value: ByteArray) {
        openDb(dir, useIdb1 = false).use { db ->
            db.put(key, value)
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    private fun openDb(dir: File, useIdb1: Boolean): DB {
        val options = Options().apply {
            createIfMissing(true)
            errorIfExists(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            if (useIdb1) comparator(Idb1Comparator())
        }
        return Iq80DBFactory.factory.open(dir, options)
    }

    // DB.compactRange throws UnsupportedOperationException in iq80 0.12; DbImpl.flushMemTable is the way in.
    private fun flushMemTable(db: DB) {
        (db as? DbImpl)?.flushMemTable()
    }

    // chromium names SSTables .ldb; iq80 writes .sst. rename so the fixture looks like a real chromium DB.
    private fun sstRenameToLdb(dir: File) {
        dir.listFiles { _, name -> name.endsWith(".sst") }?.forEach { sst ->
            sst.renameTo(File(sst.parentFile, sst.nameWithoutExtension + ".ldb"))
        }
    }

    // canonical LEB128 per chromium varint_coding.cc.
    private fun encodeLeb128(value: Long): ByteArray {
        require(value >= 0) { "LEB128 encoder does not handle negative" }
        val out = mutableListOf<Byte>()
        var v = value
        while ((v and 0x7fL.inv()) != 0L) {
            out += ((v and 0x7f) or 0x80).toByte()
            v = v ushr 7
        }
        out += (v and 0x7f).toByte()
        return out.toByteArray()
    }
}
