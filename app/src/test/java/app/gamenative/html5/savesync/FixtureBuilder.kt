package app.gamenative.html5.savesync

import java.io.File
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.DB
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.DbImpl
import org.iq80.leveldb.impl.Iq80DBFactory

// synthesizes chromium-valid IDB + LS leveldbs for unit tests using iq80 + Idb1Comparator.

// WHY synth over snapshots: per, committed binary fixtures hit 49MB+ (real Wayward IDB).
// synthesized fixtures are pure-JVM reproducible, <50KB, PII-free, and encode only the
// structural invariants tests need to assert. snapshotDir pattern (SaveFixtureHarness) stays
// available for on-disk validation via env-var fixture stash.

// origin encoding:
// IDB DatabaseNameKey -- UTF-16BE (per Chromium spec "StringWithLength" in idb comparator).
// origin is the FILENAME-FORM string (e.g. "https_game-steam_379210_0"), NOT the URL.
// NOTE: Idb1ComparatorTest uses UTF-16LE for IDBKey payload bytes (key values); this is
// DIFFERENT. DatabaseNameKey origin slice is UTF-16BE per Chromium spec + 
// LS keys -- ASCII (US_ASCII). URL-form origin (e.g. "https://game-steam_379210").
object FixtureBuilder {

    // write a chromium-valid IDB leveldb containing:
    // - one DatabaseNameKey entry with the given origin (filename-form) + database name
    // - one internal db-id key (non-origin-bearing, passes through verbatim on rewrite)
    // idb_cmp1 comparator + snappy (matches real chromium IDB on-disk format).
    // result dir contains .ldb files (sst to ldb rename applied after close).
    fun idbWithDatabaseName(
        dir: File,
        originFilename: String,
        databaseName: String,
        partitionSuffix: String = "",
    ) {
        dir.mkdirs()
        openDb(dir, useIdb1 = true).use { db ->
            // DatabaseNameKey byte layout per chromium leveldb_coding_scheme.md + real Chromium
            // desktop probe (WaywardDesktopProbe captured ground truth):
            // [0x00, 0x00, 0x00, 0x00, 0xC9] -- 5-byte KeyPrefix:
            // byte 0: length-packed byte (db/obj/idx sizes each -1, zero for metadata keys)
            // bytes 1-3: varints db_id=0, obj_store=0, idx=0
            // byte 4: type byte 0xC9 = DatabaseNameKey (201)
            // varint(code unit count of origin) -- LEB128
            // UTF-16BE bytes of origin (2 * code unit count)
            // varint(code unit count of database name)
            // UTF-16BE bytes of database name

            // partitionSuffix: optional `@<n>` storage-partitioning tail (Chromium 105+).
            // appended to originFilename verbatim; caller supplies "@1", "@2", etc.
            // NOT validated — test fixture only.
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

            // internal db-id key -- non-origin-bearing; rewriter must pass through verbatim.
            // key prefix byte 0x01 = db_id=1 1-byte, obj_store=42 1-byte, idx=1 1-byte.
            db.put(byteArrayOf(1, 42, 0, 1, 0, 0, 0, 1), byteArrayOf(1, 2, 3))

            // force memtable flush so iq80 writes .sst files before close.
            // without this, tiny DBs stay in the WAL (.log) and sstRenameToLdb finds nothing.
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    // write a chromium-valid LS leveldb for one or more origins.
    // for each (url, keyValues) pair:
    // - writes "META:<url>" key (ASCII) -- single-byte value
    // - for each (k, v) in keyValues: writes "_<url><NUL><k>" (ASCII, NUL byte separator) -- v
    // bytewise comparator (NOT idb_cmp1; LS uses lexicographic byte order).
    // result dir contains .ldb files (sst to ldb rename applied after close).
    fun lsWithOrigins(
        dir: File,
        vararg originAndKV: Pair<String, Map<String, ByteArray>>,
    ) {
        dir.mkdirs()
        openDb(dir, useIdb1 = false).use { db ->
            for ((url, keyValues) in originAndKV) {
                // META:<url> -- shape 2 (origin at offset 5 after "META:")
                val metaKey = "META:$url".toByteArray(Charsets.US_ASCII)
                db.put(metaKey, byteArrayOf(1))

                // _<url><NUL><user-key> -- shape 1 (origin at offset 1 after '_', NUL separator)
                for ((k, v) in keyValues) {
                    val lsKey = byteArrayOf('_'.code.toByte()) +
                        url.toByteArray(Charsets.US_ASCII) +
                        byteArrayOf(0) +
                        k.toByteArray(Charsets.US_ASCII)
                    db.put(lsKey, v)
                }
            }
            // force memtable flush so .sst files exist for sstRenameToLdb
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    // append a single raw key/value to an existing LS leveldb (bytewise comparator).
    // used in tests that need non-origin-bearing keys alongside origin keys.
    fun putRaw(dir: File, key: ByteArray, value: ByteArray) {
        openDb(dir, useIdb1 = false).use { db ->
            db.put(key, value)
            flushMemTable(db)
        }
        sstRenameToLdb(dir)
    }

    // --- private ---

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

    // DB.compactRange(byte[], byte[]) throws UnsupportedOperationException in iq80 0.12.
    // DbImpl.flushMemTable() is the concrete public method that writes the WAL to SSTable.
    private fun flushMemTable(db: DB) {
        (db as? DbImpl)?.flushMemTable()
    }

    // chromium names SSTables .ldb (post-2014); iq80 writes .sst. rename to match chromium's
    // on-disk convention so the synthesized fixture behaves identically to a real chromium DB.
    private fun sstRenameToLdb(dir: File) {
        dir.listFiles { _, name -> name.endsWith(".sst") }?.forEach { sst ->
            sst.renameTo(File(sst.parentFile, sst.nameWithoutExtension + ".ldb"))
        }
    }

    // canonical LEB128 encoder per chromium varint_coding.cc (cited in Idb1Comparator.kt:13).
    // while value >= 0x80: emit (value & 0x7f) | 0x80, shift right 7; then emit final (value & 0x7f).
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
