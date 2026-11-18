// port of chromium's IndexedDB leveldb key comparator, so iq80 can open chromium IDB databases.
// key decoding cross-checked against https://github.com/cclgroupltd/ccl_chromium_reader (which ships
// no comparator); compare logic follows the chromium source:
// https://chromium.googlesource.com/chromium/src.git/+/62.0.3178.1/content/browser/indexed_db/leveldb_coding_scheme.md
// https://source.chromium.org/chromium/chromium/src/+/main:content/browser/indexed_db/indexed_db_leveldb_coding.cc
// (Compare, KeyPrefix::Decode, CompareEncodedIDBKeys, KeyTypeByteToKeyType)
// https://source.chromium.org/chromium/chromium/src/+/main:components/services/storage/indexed_db/scopes/varint_coding.cc
// (DecodeVarInt -- leb128)

// BSD 3-clause attribution: logic derived from Chromium (Copyright 2013 The Chromium Authors).

package app.gamenative.html5.savesync

import org.iq80.leveldb.DBComparator

/**
 * name() must match the MANIFEST's comparator string "idb_cmp1" byte for byte, or iq80 refuses the open.
 *
 * mirrors chromium's algorithm:
 *   1. decode the variable-width KeyPrefix (db_id, obj_store_id, index_id) from both keys.
 *   2. compare prefixes by (db_id, obj_store_id, index_id).
 *   3. dispatch on KeyPrefix type:
 *        GLOBAL_METADATA / DATABASE_METADATA -> type byte, then chromium's per-key-class compare.
 *        OBJECT_STORE_DATA / EXISTS_ENTRY / BLOB_ENTRY -> CompareEncodedIDBKeys on the suffix.
 *        INDEX_DATA -> CompareEncodedIDBKeys on primary then secondary IDBKey + sequence number.
 *   4. on any decode failure, bytewise-compare the whole keys: chromium returns 0 + ok=false, but
 *      iq80 needs a total order.
 */
class Idb1Comparator : DBComparator {

    override fun name(): String = "idb_cmp1"

    override fun compare(a: ByteArray, b: ByteArray): Int {
        return compareKeys(a, b)
    }

    // returning inputs unchanged is ALWAYS correct; it only costs slightly larger SST index blocks.
    override fun findShortestSeparator(start: ByteArray, limit: ByteArray): ByteArray = start

    override fun findShortSuccessor(key: ByteArray): ByteArray = key

    private companion object {
        const val KEY_TYPE_NULL: Int = 0
        const val KEY_TYPE_STRING: Int = 1
        const val KEY_TYPE_DATE: Int = 2
        const val KEY_TYPE_NUMBER: Int = 3
        const val KEY_TYPE_ARRAY: Int = 4
        const val KEY_TYPE_MIN_KEY: Int = 5
        const val KEY_TYPE_BINARY: Int = 6

        // IDBKeyType ordinals in indexeddb.mojom declaration order; type order compares on these, not
        // on the on-disk type byte.
        const val MOJOM_INVALID = 0
        const val MOJOM_ARRAY = 1
        const val MOJOM_BINARY = 2
        const val MOJOM_STRING = 3
        const val MOJOM_DATE = 4
        const val MOJOM_NUMBER = 5
        const val MOJOM_MIN = 7

        const val OBJECT_STORE_DATA_INDEX_ID = 1L
        const val EXISTS_ENTRY_INDEX_ID = 2L
        const val BLOB_ENTRY_INDEX_ID = 3L
        const val MINIMUM_INDEX_ID = 4L
    }

    private enum class PrefixType { GLOBAL_METADATA, DATABASE_METADATA, OBJECT_STORE_DATA, EXISTS_ENTRY, BLOB_ENTRY, INDEX_DATA, INVALID }

    private class Slice(val data: ByteArray, var pos: Int = 0) {
        fun remaining(): Int = data.size - pos
        fun isEmpty(): Boolean = pos >= data.size
        fun readByte(): Int = (data[pos++].toInt() and 0xFF)
        fun peekByte(): Int = (data[pos].toInt() and 0xFF)
        fun advance(n: Int) { pos += n }
    }

    private class KeyPrefix(var databaseId: Long = 0, var objectStoreId: Long = 0, var indexId: Long = 0) {
        fun canBeValid(): Boolean = databaseId >= 0 && objectStoreId >= 0 && indexId >= 0

        fun maybeType(): PrefixType {
            if (!canBeValid()) return PrefixType.INVALID
            if (databaseId == 0L) return PrefixType.GLOBAL_METADATA
            if (objectStoreId == 0L) return PrefixType.DATABASE_METADATA
            return when (indexId) {
                OBJECT_STORE_DATA_INDEX_ID -> PrefixType.OBJECT_STORE_DATA
                EXISTS_ENTRY_INDEX_ID -> PrefixType.EXISTS_ENTRY
                BLOB_ENTRY_INDEX_ID -> PrefixType.BLOB_ENTRY
                else -> if (indexId >= MINIMUM_INDEX_ID) PrefixType.INDEX_DATA else PrefixType.INVALID
            }
        }

        fun compareTo(other: KeyPrefix): Int {
            if (databaseId != other.databaseId) return if (databaseId < other.databaseId) -1 else 1
            if (objectStoreId != other.objectStoreId) return if (objectStoreId < other.objectStoreId) -1 else 1
            if (indexId != other.indexId) return if (indexId < other.indexId) -1 else 1
            return 0
        }
    }

    // KeyPrefix: one byte packs three widths. bits:
    // [7..5] db_id byte-count minus 1
    // [4..2] obj_store_id byte-count minus 1
    // [1..0] index_id byte-count minus 1
    // each id follows as little-endian variable-width bytes (1..8 for db/obj, 1..4 for index).
    private fun decodePrefix(s: Slice, out: KeyPrefix): Boolean {
        if (s.isEmpty()) return false
        val first = s.readByte()
        val dbBytes = ((first ushr 5) and 0x7) + 1
        val osBytes = ((first ushr 2) and 0x7) + 1
        val idxBytes = (first and 0x3) + 1
        if (dbBytes + osBytes + idxBytes > s.remaining()) return false
        out.databaseId = readLeInt(s, dbBytes)
        out.objectStoreId = readLeInt(s, osBytes)
        out.indexId = readLeInt(s, idxBytes)
        return true
    }

    private fun readLeInt(s: Slice, nBytes: Int): Long {
        var value = 0L
        var shift = 0
        for (i in 0 until nBytes) {
            value = value or (s.readByte().toLong() shl shift)
            shift += 8
        }
        return value
    }

    // chromium DecodeVarInt (leb128); null where chromium returns false.
    private fun decodeVarInt(s: Slice): Long? {
        var shift = 0
        var ret = 0L
        while (true) {
            if (s.isEmpty() || shift >= 64) return null
            val c = s.peekByte()
            // chromium rejects a 0x00 continuation byte mid-varint.
            if (shift != 0 && c == 0) return null
            val preShift = (c and 0x7f).toLong()
            val shifted = preShift shl shift
            if ((shifted ushr shift) != preShift) return null
            ret = ret or shifted
            shift += 7
            val b = s.readByte()
            if ((b and 0x80) == 0) break
        }
        return ret
    }

    private fun decodeDouble(s: Slice): Double? {
        if (s.remaining() < 8) return null
        var bits = 0L
        for (i in 0 until 8) {
            bits = bits or (s.readByte().toLong() shl (i * 8))
        }
        return Double.fromBits(bits)
    }

    private fun keyTypeByteToMojom(t: Int): Int? = when (t) {
        KEY_TYPE_NULL -> MOJOM_INVALID
        KEY_TYPE_ARRAY -> MOJOM_ARRAY
        KEY_TYPE_BINARY -> MOJOM_BINARY
        KEY_TYPE_STRING -> MOJOM_STRING
        KEY_TYPE_DATE -> MOJOM_DATE
        KEY_TYPE_NUMBER -> MOJOM_NUMBER
        KEY_TYPE_MIN_KEY -> MOJOM_MIN
        else -> null
    }

    // chromium CompareEncodedIDBKeys; consumes one IDBKey from each slice. decode success lands in ok[0].
    private fun compareEncodedIdbKeys(a: Slice, b: Slice, ok: BooleanArray): Int {
        if (a.isEmpty() || b.isEmpty()) { ok[0] = false; return 0 }
        val typeA = a.readByte()
        val typeB = b.readByte()
        val mojomA = keyTypeByteToMojom(typeA)
        val mojomB = keyTypeByteToMojom(typeB)
        if (mojomA == null || mojomB == null) { ok[0] = false; return 0 }
        // chromium's CompareTypes is `(int)b - (int)a` -- higher mojom ordinal sorts EARLIER.
        val typeCmp = mojomB - mojomA
        if (typeCmp != 0) { ok[0] = true; return typeCmp }

        return when (typeA) {
            KEY_TYPE_NULL, KEY_TYPE_MIN_KEY -> { ok[0] = true; 0 }
            KEY_TYPE_ARRAY -> {
                val lenA = decodeVarInt(a)
                val lenB = decodeVarInt(b)
                if (lenA == null || lenB == null || lenA < 0 || lenB < 0) { ok[0] = false; return 0 }
                var i = 0L
                while (i < lenA && i < lenB) {
                    val sub = compareEncodedIdbKeys(a, b, ok)
                    if (!ok[0] || sub != 0) return sub
                    i++
                }
                ok[0] = true
                when {
                    lenA < lenB -> -1
                    lenA > lenB -> 1
                    else -> 0
                }
            }
            KEY_TYPE_BINARY -> compareEncodedBinary(a, b, ok)
            KEY_TYPE_STRING -> compareEncodedStringWithLength(a, b, ok)
            KEY_TYPE_DATE, KEY_TYPE_NUMBER -> {
                val da = decodeDouble(a)
                val db = decodeDouble(b)
                if (da == null || db == null) { ok[0] = false; return 0 }
                ok[0] = true
                da.compareTo(db)
            }
            else -> { ok[0] = false; 0 }
        }
    }

    private fun compareEncodedBinary(a: Slice, b: Slice, ok: BooleanArray): Int {
        val lenA = decodeVarInt(a)
        val lenB = decodeVarInt(b)
        if (lenA == null || lenB == null || lenA < 0 || lenB < 0) { ok[0] = false; return 0 }
        val sizeA = lenA.toInt()
        val sizeB = lenB.toInt()
        if (a.remaining() < sizeA || b.remaining() < sizeB) { ok[0] = false; return 0 }
        val cmp = bytewiseCompare(a.data, a.pos, sizeA, b.data, b.pos, sizeB)
        a.advance(sizeA)
        b.advance(sizeB)
        ok[0] = true
        return cmp
    }

    // payload is 2 * varint char count of UTF-16 bytes; bytewise matches chromium's char16_t compare.
    private fun compareEncodedStringWithLength(a: Slice, b: Slice, ok: BooleanArray): Int {
        val lenA = decodeVarInt(a)
        val lenB = decodeVarInt(b)
        if (lenA == null || lenB == null || lenA < 0 || lenB < 0) { ok[0] = false; return 0 }
        val sizeA = (lenA * 2L).toInt()
        val sizeB = (lenB * 2L).toInt()
        if (a.remaining() < sizeA || b.remaining() < sizeB) { ok[0] = false; return 0 }
        val cmp = bytewiseCompare(a.data, a.pos, sizeA, b.data, b.pos, sizeB)
        a.advance(sizeA)
        b.advance(sizeB)
        ok[0] = true
        return cmp
    }

    // chromium's per-key-class metadata compares (type byte already consumed). decode failure falls
    // back to bytewise over the full keys to keep a total order.

    // ObjectStoreMetaDataKey -- suffix: <os_id:varint> <meta_type:byte>
    private fun compareObjectStoreMetaDataKey(a: Slice, b: Slice, rawA: ByteArray, rawB: ByteArray): Int {
        val osIdA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val osIdB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        if (osIdA != osIdB) return if (osIdA < osIdB) -1 else 1
        if (a.isEmpty() || b.isEmpty()) return a.remaining().compareTo(b.remaining())
        return a.readByte() - b.readByte()
    }

    // IndexMetaDataKey -- suffix: <os_id:varint> <index_id:varint> <meta_type:byte>
    private fun compareIndexMetaDataKey(a: Slice, b: Slice, rawA: ByteArray, rawB: ByteArray): Int {
        val osIdA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val osIdB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        if (osIdA != osIdB) return if (osIdA < osIdB) -1 else 1
        val idxIdA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val idxIdB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        if (idxIdA != idxIdB) return if (idxIdA < idxIdB) -1 else 1
        if (a.isEmpty() || b.isEmpty()) return a.remaining().compareTo(b.remaining())
        return a.readByte() - b.readByte()
    }

    // ObjectStoreNamesKey -- suffix: <name_len:varint> <name: name_len*2 bytes UTF-16BE>.
    // bytewise on UTF-16BE matches chromium's UTF-16 compare for BMP chars.
    private fun compareObjectStoreNamesKey(a: Slice, b: Slice, rawA: ByteArray, rawB: ByteArray): Int {
        val nameLenA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val nameLenB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        val bytesA = (nameLenA * 2L).toInt()
        val bytesB = (nameLenB * 2L).toInt()
        if (nameLenA < 0 || nameLenB < 0 ||
            a.remaining() < bytesA || b.remaining() < bytesB
        ) return bytewiseCompare(rawA, rawB)
        return bytewiseCompare(a.data, a.pos, bytesA, b.data, b.pos, bytesB)
    }

    // DatabaseNameKey (GLOBAL_METADATA type 0xC9) -- suffix: <origin_len:varint>
    // <origin:UTF-16BE> <name_len:varint> <name:UTF-16BE>. chromium compares origin, then name, as
    // u16 code units; bytewise over each length-delimited UTF-16BE slice is exactly that.
    private fun compareDatabaseNameKey(a: Slice, b: Slice, rawA: ByteArray, rawB: ByteArray): Int {
        val originLenA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val originLenB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        val originBytesA = (originLenA * 2L).toInt()
        val originBytesB = (originLenB * 2L).toInt()
        if (originLenA < 0 || originLenB < 0 ||
            a.remaining() < originBytesA || b.remaining() < originBytesB
        ) return bytewiseCompare(rawA, rawB)
        val originCmp = bytewiseCompare(a.data, a.pos, originBytesA, b.data, b.pos, originBytesB)
        a.advance(originBytesA)
        b.advance(originBytesB)
        if (originCmp != 0) return originCmp
        val nameLenA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val nameLenB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        val nameBytesA = (nameLenA * 2L).toInt()
        val nameBytesB = (nameLenB * 2L).toInt()
        if (nameLenA < 0 || nameLenB < 0 ||
            a.remaining() < nameBytesA || b.remaining() < nameBytesB
        ) return bytewiseCompare(rawA, rawB)
        return bytewiseCompare(a.data, a.pos, nameBytesA, b.data, b.pos, nameBytesB)
    }

    // IndexNamesKey -- suffix: <os_id:varint> <name_len:varint> <name: UTF-16BE>
    private fun compareIndexNamesKey(a: Slice, b: Slice, rawA: ByteArray, rawB: ByteArray): Int {
        val osIdA = decodeVarInt(a) ?: return bytewiseCompare(rawA, rawB)
        val osIdB = decodeVarInt(b) ?: return bytewiseCompare(rawA, rawB)
        if (osIdA != osIdB) return if (osIdA < osIdB) -1 else 1
        return compareObjectStoreNamesKey(a, b, rawA, rawB)
    }

    private fun bytewiseCompare(aData: ByteArray, aOff: Int, aLen: Int, bData: ByteArray, bOff: Int, bLen: Int): Int {
        val common = minOf(aLen, bLen)
        for (i in 0 until common) {
            val av = aData[aOff + i].toInt() and 0xFF
            val bv = bData[bOff + i].toInt() and 0xFF
            if (av != bv) return if (av < bv) -1 else 1
        }
        return aLen.compareTo(bLen)
    }

    private fun bytewiseCompare(a: ByteArray, b: ByteArray): Int =
        bytewiseCompare(a, 0, a.size, b, 0, b.size)

    private fun compareKeys(a: ByteArray, b: ByteArray): Int {
        // leveldb shouldn't emit empty keys, but the order must still be total.
        if (a.isEmpty() || b.isEmpty()) return bytewiseCompare(a, b)

        val sliceA = Slice(a)
        val sliceB = Slice(b)
        val prefixA = KeyPrefix()
        val prefixB = KeyPrefix()
        val okA = decodePrefix(sliceA, prefixA)
        val okB = decodePrefix(sliceB, prefixB)
        if (!okA || !okB || !prefixA.canBeValid() || !prefixB.canBeValid()) {
            return bytewiseCompare(a, b)
        }
        val prefixCmp = prefixA.compareTo(prefixB)
        if (prefixCmp != 0) return prefixCmp

        val type = prefixA.maybeType()
        if (type == PrefixType.INVALID) return bytewiseCompare(a, b)
        if (sliceA.isEmpty() || sliceB.isEmpty()) return bytewiseCompare(a, b)

        return when (type) {
            PrefixType.GLOBAL_METADATA -> {
                // chromium 109 (indexed_db_leveldb_coding.cc:853-889):
                // type < 7: simple scalar, return 0
                // 0x32 (scopes): bytewise suffix compare
                // 0x64: Compare<DatabaseFreeListKey>
                // 0xC9: Compare<DatabaseNameKey>. NOT plain bytewise over the suffix: that orders by
                // varint(origin_len) first and diverges when names differ in length.
                val tA = sliceA.readByte()
                val tB = sliceB.readByte()
                if (tA != tB) return tA - tB
                when (tA) {
                    0xC9 -> compareDatabaseNameKey(sliceA, sliceB, a, b)
                    else -> bytewiseCompare(
                        sliceA.data, sliceA.pos, sliceA.remaining(),
                        sliceB.data, sliceB.pos, sliceB.remaining(),
                    )
                }
            }
            PrefixType.DATABASE_METADATA -> {
                // chromium 109 (indexed_db_leveldb_coding.cc:892-942):
                // type < 6: simple scalar, return 0
                // 0x32 ObjectStoreMetaDataKey: (os_id, meta_type)
                // 0x64 IndexMetaDataKey: (os_id, index_id, meta_type)
                // 0xC8 ObjectStoreNamesKey: name
                // 0xC9 IndexNamesKey: (os_id, name)
                // bytewise usually agrees, but an SST ordered bytewise can desync from chromium's
                // readers at block boundaries -- so match chromium exactly.
                val tA = sliceA.readByte()
                val tB = sliceB.readByte()
                if (tA != tB) return tA - tB
                when (tA) {
                    in 0..5 -> 0
                    0x32 -> compareObjectStoreMetaDataKey(sliceA, sliceB, a, b)
                    0x64 -> compareIndexMetaDataKey(sliceA, sliceB, a, b)
                    0xC8 -> compareObjectStoreNamesKey(sliceA, sliceB, a, b)
                    0xC9 -> compareIndexNamesKey(sliceA, sliceB, a, b)
                    else -> bytewiseCompare(sliceA.data, sliceA.pos, sliceA.remaining(), sliceB.data, sliceB.pos, sliceB.remaining())
                }
            }
            PrefixType.OBJECT_STORE_DATA, PrefixType.EXISTS_ENTRY, PrefixType.BLOB_ENTRY -> {
                val ok = BooleanArray(1)
                val result = compareEncodedIdbKeys(sliceA, sliceB, ok)
                if (!ok[0]) bytewiseCompare(a, b) else result
            }
            PrefixType.INDEX_DATA -> {
                val ok = BooleanArray(1)
                val primaryCmp = compareEncodedIdbKeys(sliceA, sliceB, ok)
                if (!ok[0]) return bytewiseCompare(a, b)
                if (primaryCmp != 0) return primaryCmp
                if (sliceA.isEmpty() || sliceB.isEmpty()) {
                    return sliceA.remaining().compareTo(sliceB.remaining())
                }
                val seqA = decodeVarInt(sliceA) ?: return bytewiseCompare(a, b)
                val seqB = decodeVarInt(sliceB) ?: return bytewiseCompare(a, b)
                if (sliceA.isEmpty() || sliceB.isEmpty()) {
                    return sliceA.remaining().compareTo(sliceB.remaining())
                }
                val secCmp = compareEncodedIdbKeys(sliceA, sliceB, ok)
                if (!ok[0]) return bytewiseCompare(a, b)
                if (secCmp != 0) return secCmp
                seqA.compareTo(seqB)
            }
            PrefixType.INVALID -> bytewiseCompare(a, b)
        }
    }
}
