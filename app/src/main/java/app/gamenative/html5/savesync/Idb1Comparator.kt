// idb_cmp1 comparator -- ports chromium's IndexedDB-specific leveldb key comparator to kotlin.
// required so iq80 (pure-java leveldb) can open chromium IDB databases.

// port source (authoritative, DFIR-hardened):
// https://github.com/cclgroupltd/ccl_chromium_reader (python, battle-tested)
// NOTE: ccl reader decodes keys but does NOT ship a comparator (readers don't need one).
// comparator logic derived directly from chromium c++ source -- BSD 3-clause licensed.

// normative spec (tie-breaker for ambiguity):
// https://chromium.googlesource.com/chromium/src.git/+/62.0.3178.1/content/browser/indexed_db/leveldb_coding_scheme.md
// https://source.chromium.org/chromium/chromium/src/+/main:content/browser/indexed_db/indexed_db_leveldb_coding.cc
// (Compare, KeyPrefix::Decode, CompareEncodedIDBKeys, KeyTypeByteToKeyType)
// https://source.chromium.org/chromium/chromium/src/+/main:components/services/storage/indexed_db/scopes/varint_coding.cc
// (DecodeVarInt -- leb128)

// if chromium ever ships idb_cmp2: diff upstream ccl, translate deltas. this file is the single seam.

// BSD 3-clause attribution: logic derived from Chromium (Copyright 2013 The Chromium Authors).

package app.gamenative.html5.savesync

import org.iq80.leveldb.DBComparator

/**
 * chromium IndexedDB leveldb comparator. name() must byte-identical match the MANIFEST-embedded
 * comparator string "idb_cmp1" -- iq80 rejects the DB open otherwise.
 *
 * the compare path mirrors chromium's four-stage algorithm:
 *   1. decode the variable-width KeyPrefix (db_id, obj_store_id, index_id) from both keys.
 *   2. compare prefixes lexicographically by (db_id, obj_store_id, index_id).
 *   3. dispatch on KeyPrefix.Type():
 *        GLOBAL_METADATA / DATABASE_METADATA → compare one type-byte, then lexicographic fallback
 *          (chromium runs per-class Compare<T> here -- for our read/round-trip use case, lexicographic
 *          fallback after the type-byte matches produces a stable total ordering; SSTables on disk
 *          are already sorted by chromium's writer, and we don't write metadata in tests).
 *        OBJECT_STORE_DATA / EXISTS_ENTRY / BLOB_ENTRY → CompareEncodedIDBKeys on the suffix.
 *        INDEX_DATA → CompareEncodedIDBKeys on primary then secondary IDBKey + sequence number.
 *   4. on any decode failure, fall back to bytewise compare of the entire slices (chromium returns
 *      0 + ok=false, but iq80's Comparator contract requires a total order -- bytewise is the safest
 *      tiebreaker that still satisfies reflexivity + transitivity).
 */
class Idb1Comparator : DBComparator {

    override fun name(): String = "idb_cmp1"

    override fun compare(a: ByteArray, b: ByteArray): Int {
        return compareKeys(a, b)
    }

    // leveldb optimization hooks. chromium's c++ comparator supplies trimmed separators to shrink
    // the index footer in SSTables; iq80 calls these during compaction. returning the unchanged
    // inputs is ALWAYS correct -- the downside is slightly larger SSTable index blocks. for our
    // read-heavy use case this is a non-issue.
    override fun findShortestSeparator(start: ByteArray, limit: ByteArray): ByteArray = start

    override fun findShortSuccessor(key: ByteArray): ByteArray = key

    // --- impl ---

    private companion object {
        // key type bytes for IDBKey-encoded values (CompareEncodedIDBKeys switch)
        const val KEY_TYPE_NULL: Int = 0
        const val KEY_TYPE_STRING: Int = 1
        const val KEY_TYPE_DATE: Int = 2
        const val KEY_TYPE_NUMBER: Int = 3
        const val KEY_TYPE_ARRAY: Int = 4
        const val KEY_TYPE_MIN_KEY: Int = 5
        const val KEY_TYPE_BINARY: Int = 6

        // IDBKeyType mojom enum ordinal -- declaration order in indexeddb.mojom:
        // Invalid(0), Array(1), Binary(2), String(3), Date(4), Number(5), None(6), Min(7)
        // chromium's CompareTypes does `(int)b - (int)a` (DESCENDING by ordinal on sort).
        const val MOJOM_INVALID = 0
        const val MOJOM_ARRAY = 1
        const val MOJOM_BINARY = 2
        const val MOJOM_STRING = 3
        const val MOJOM_DATE = 4
        const val MOJOM_NUMBER = 5
        const val MOJOM_MIN = 7

        // special index_id sentinels on KeyPrefix (drive Type() dispatch)
        const val OBJECT_STORE_DATA_INDEX_ID = 1L
        const val EXISTS_ENTRY_INDEX_ID = 2L
        const val BLOB_ENTRY_INDEX_ID = 3L
        const val MINIMUM_INDEX_ID = 4L
    }

    private enum class PrefixType { GLOBAL_METADATA, DATABASE_METADATA, OBJECT_STORE_DATA, EXISTS_ENTRY, BLOB_ENTRY, INDEX_DATA, INVALID }

    // cursor over a ByteArray -- tracks position without re-wrapping. matches chromium's
    // std::string_view slice + remove_prefix pattern.
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

    // KeyPrefix layout: one byte packs three widths. bits:
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

    // little-endian variable-width signed int. chromium reads bytes as unsigned and shifts by 8 
    // byte; with 1..8 bytes this fits in int64 (widths capped by decodePrefix's 3-bit slot -- max 8).
    private fun readLeInt(s: Slice, nBytes: Int): Long {
        var value = 0L
        var shift = 0
        for (i in 0 until nBytes) {
            value = value or (s.readByte().toLong() shl shift)
            shift += 8
        }
        return value
    }

    // leb128 varint per chromium's DecodeVarInt. returns the decoded value, advances slice. returns
    // null on decode failure (matches chromium's `return false` semantics).
    private fun decodeVarInt(s: Slice): Long? {
        var shift = 0
        var ret = 0L
        while (true) {
            if (s.isEmpty() || shift >= 64) return null
            val c = s.peekByte()
            // chromium rejects a continuation byte of 0x00 mid-varint (invalid leb128 canon).
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

    // IEEE-754 double, little-endian 8 bytes
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

    // CompareEncodedIDBKeys -- consumes one IDBKey from each slice, returns sort order.
    // returns an Int; writes true/false into ok[0] for downstream short-circuiting.
    private fun compareEncodedIdbKeys(a: Slice, b: Slice, ok: BooleanArray): Int {
        if (a.isEmpty() || b.isEmpty()) { ok[0] = false; return 0 }
        val typeA = a.readByte()
        val typeB = b.readByte()
        val mojomA = keyTypeByteToMojom(typeA)
        val mojomB = keyTypeByteToMojom(typeB)
        if (mojomA == null || mojomB == null) { ok[0] = false; return 0 }
        // chromium's CompareTypes: `(int)b - (int)a` -- higher mojom ordinal sorts earlier.
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

    // strings in chromium IDB leveldb are utf-16 encoded -- payload length is 2 * varint-encoded
    // character count. we bytewise-compare the utf-16 byte sequence, which matches chromium's
    // string_view::compare semantics on the raw char16_t payload.
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

    // chromium-parity DATABASE_METADATA Compare<T> helpers (type byte already consumed).
    // decode failure falls back to bytewise-of-full-keys for total-order safety.

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
    // chromium compares name as UTF-16; bytewise on UTF-16BE matches for ASCII/BMP chars.
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
    // <origin:UTF-16BE> <name_len:varint> <name:UTF-16BE>. chromium 109
    // DatabaseNameKey::Compare (indexed_db_leveldb_coding.cc:1584-1588) decodes origin
    // and name as std::u16string and compares via std::u16string::compare -- u16 code-unit
    // unsigned compare. bytewise over UTF-16BE of each length-delimited slice matches this
    // semantic exactly (high byte stored first = MSB of u16).
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

    // unsigned lexicographic compare between two [data, offset, length) views.
    private fun bytewiseCompare(aData: ByteArray, aOff: Int, aLen: Int, bData: ByteArray, bOff: Int, bLen: Int): Int {
        val common = minOf(aLen, bLen)
        for (i in 0 until common) {
            val av = aData[aOff + i].toInt() and 0xFF
            val bv = bData[bOff + i].toInt() and 0xFF
            if (av != bv) return if (av < bv) -1 else 1
        }
        return aLen.compareTo(bLen)
    }

    // full-slice bytewise compare (degenerate fallback + default case for equal-prefix metadata)
    private fun bytewiseCompare(a: ByteArray, b: ByteArray): Int =
        bytewiseCompare(a, 0, a.size, b, 0, b.size)

    // top-level dispatch. on any decode failure falls back to bytewise compare of the full inputs --
    // chromium's internal compare returns (0, ok=false), but iq80 requires a total order for
    // correctness. bytewise is reflexive + transitive + antisymmetric, which is enough.
    private fun compareKeys(a: ByteArray, b: ByteArray): Int {
        // empty keys: bytewise (degenerate -- leveldb shouldn't emit these, but define the contract).
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
                // GLOBAL metadata (db_id=0). chromium 109 idb_cmp1 dispatch 
                // indexed_db_leveldb_coding.cc:853-889:
                // type < kMaxSimpleGlobalMetaDataTypeByte (7): simple scalar, return 0
                // type == kScopesPrefixByte (0x32): bytewise suffix compare
                // type == kDatabaseFreeListTypeByte (0x64): Compare<DatabaseFreeListKey>
                // type == kDatabaseNameTypeByte (0xC9): Compare<DatabaseNameKey> -- decode
                // origin + name as std::u16string, compare via .compare() (u16 code-unit unsigned).
                // since on-disk origin/name are UTF-16BE, bytewise-of-UTF16BE-bytes equals u16
                // code-unit compare -- but ONLY after controlling for length. bytewise-of-full-suffix
                // orders by varint(origin_len) first, which diverges from chromium when names have
                // different lengths.
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
                // DATABASE metadata (db_id>0, os_id=0). chromium 109 idb_cmp1 dispatches 
                // type byte (indexed_db_leveldb_coding.cc:892-942):
                // type < 6 (MAX_SIMPLE_METADATA_TYPE): simple scalar, return 0
                // type 50 (0x32) ObjectStoreMetaDataKey: Compare by (os_id, meta_type)
                // type 100 (0x64) IndexMetaDataKey: Compare by (os_id, index_id, meta_type)
                // type 200 (0xC8) ObjectStoreNamesKey: Compare by name (UTF-16 bytewise)
                // type 201 (0xC9) IndexNamesKey: Compare by (os_id, name)
                // bytewise on our specific data (all 1-byte varints) agrees semantically, but an
                // SST written under bytewise can still desync from chromium's readers at block
                // boundaries when separators decode mismatch -- matching semantic compare here
                // closes that gap.
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
                // primary IDBKey
                val ok = BooleanArray(1)
                val primaryCmp = compareEncodedIdbKeys(sliceA, sliceB, ok)
                if (!ok[0]) return bytewiseCompare(a, b)
                if (primaryCmp != 0) return primaryCmp
                // sequence_number -- varint. chromium: if slice empty after primary, return 0.
                if (sliceA.isEmpty() || sliceB.isEmpty()) {
                    return sliceA.remaining().compareTo(sliceB.remaining())
                }
                val seqA = decodeVarInt(sliceA) ?: return bytewiseCompare(a, b)
                val seqB = decodeVarInt(sliceB) ?: return bytewiseCompare(a, b)
                if (sliceA.isEmpty() || sliceB.isEmpty()) {
                    return sliceA.remaining().compareTo(sliceB.remaining())
                }
                // secondary (index) IDBKey
                val secCmp = compareEncodedIdbKeys(sliceA, sliceB, ok)
                if (!ok[0]) return bytewiseCompare(a, b)
                if (secCmp != 0) return secCmp
                seqA.compareTo(seqB)
            }
            PrefixType.INVALID -> bytewiseCompare(a, b)
        }
    }
}
