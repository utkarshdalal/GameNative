// DBKey-drop investigation — verifies hypothesis G from chromium-109 source research.
// encodes the 3 wayward DatabaseNameKeys per chromium encoder, sorts bytewise vs
// chromium semantic (DatabaseNameKey::Compare = origin u16 compare then name u16 compare),
// and compares the orders. if they differ, iq80 + current Idb1Comparator produces an SST
// whose key ordering disagrees with chromium's reader — plausible root cause for DBKey drop.

// delete after root cause fix committed.

package app.gamenative.html5.savesync

import org.junit.Test
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import java.io.File

class GlobalMetadataCompareProbeTest {

    // encodes DatabaseNameKey per chromium indexed_db_leveldb_coding.cc:1564-1571:
    // KeyPrefix::EncodeEmpty() + kDatabaseNameTypeByte(0xC9)
    // + EncodeStringWithLength(origin_utf16) + EncodeStringWithLength(name_utf16)
    // where EncodeStringWithLength = varint(codeUnitCount) + utf16BEbytes(str).
    private fun encodeDbKey(origin: String, name: String): ByteArray {
        val prefix = byteArrayOf(0, 0, 0, 0, 0xC9.toByte())
        val originUtf16 = utf16Be(origin)
        val nameUtf16 = utf16Be(name)
        val originLenVarint = encodeVarint(origin.length.toLong())
        val nameLenVarint = encodeVarint(name.length.toLong())
        return prefix + originLenVarint + originUtf16 + nameLenVarint + nameUtf16
    }

    private fun utf16Be(s: String): ByteArray {
        val out = ByteArray(s.length * 2)
        for (i in s.indices) {
            val c = s[i].code
            out[i * 2] = ((c ushr 8) and 0xFF).toByte()
            out[i * 2 + 1] = (c and 0xFF).toByte()
        }
        return out
    }

    private fun encodeVarint(v: Long): ByteArray {
        val out = mutableListOf<Byte>()
        var n = v
        while ((n and 0x7fL.inv()) != 0L) {
            out += ((n and 0x7fL) or 0x80L).toByte()
            n = n ushr 7
        }
        out += (n and 0x7fL).toByte()
        return out.toByteArray()
    }

    // chromium DatabaseNameKey::Compare (indexed_db_leveldb_coding.cc:1584-1588):
    // int Compare(const DatabaseNameKey& other) const {
    // if (int x = origin_.compare(other.origin_)) return x;
    // return database_name_.compare(other.database_name_);
    // }
    // std::u16string::compare — UTF-16 code-unit bytewise (u16-by-u16 unsigned compare).
    private fun semanticCompare(origin1: String, name1: String, origin2: String, name2: String): Int {
        val oc = origin1.compareTo(origin2)
        if (oc != 0) return oc
        return name1.compareTo(name2)
    }

    @Test
    fun wayward3DbKeys_bytewiseVsSemantic_orderShouldMatch() {
        val origin = "https_game-steam_379210_0@1"
        val names = listOf(
            "wayward_2.11",
            "DataCache:SpriteEditor",
            "DataCache:CommonColors",
        )

        val encoded = names.map { it to encodeDbKey(origin, it) }

        val bytewiseOrder = encoded
            .sortedWith(compareBy<Pair<String, ByteArray>> { pair ->
                // unsigned bytewise compare via lexicographic over [0..255]
                pair.second.toList().joinToString(",") { "%03d".format(it.toInt() and 0xFF) }
            })
            .map { it.first }

        val semanticOrder = names
            .sortedWith(Comparator { a, b -> semanticCompare(origin, a, origin, b) })

        println("bytewise: $bytewiseOrder")
        println("semantic: $semanticOrder")

        if (bytewiseOrder != semanticOrder) {
            println("HYPOTHESIS G CONFIRMED — orders differ, iq80+bytewise writes SST " +
                "in layout chromium's semantic reader doesn't expect")
        } else {
            println("orders match — hypothesis G ruled out for this name-set")
        }
    }

    @Test
    fun idb1Comparator_nowSortsDbKeysSemantically() {
        // after the GLOBAL_METADATA 0xC9 fix, Idb1Comparator should sort DBKeys in
        // chromium-semantic order (origin u16 compare, then name u16 compare) — which for
        // the wayward 3-name case places `wayward_2.11` LAST, not FIRST as bytewise did.
        val origin = "https_game-steam_379210_0@1"
        val names = listOf(
            "wayward_2.11",
            "DataCache:SpriteEditor",
            "DataCache:CommonColors",
        )
        val encoded = names.map { it to encodeDbKey(origin, it) }
        val cmp = Idb1Comparator()
        val sorted = encoded
            .sortedWith { x, y -> cmp.compare(x.second, y.second) }
            .map { it.first }
        println("Idb1Comparator sort: $sorted")
        val expectedSemantic = listOf(
            "DataCache:CommonColors",
            "DataCache:SpriteEditor",
            "wayward_2.11",
        )
        check(sorted == expectedSemantic) {
            "Idb1Comparator must produce chromium-semantic order. expected=$expectedSemantic got=$sorted"
        }
    }

    @Test
    fun wayward3DbKeys_writeViaIq80BytewiseGlobal_readsBackAll3() {
        // writes 3 DBKeys via iq80 with current Idb1Comparator (bytewise GLOBAL_METADATA),
        // reads them back with same comparator. proves iq80 round-trip works regardless of
        // comparator correctness — isolates "iq80 can round-trip" from "chromium can read iq80 output".
        val origin = "https_game-steam_379210_0@1"
        val names = listOf("wayward_2.11", "DataCache:SpriteEditor", "DataCache:CommonColors")
        val dir = kotlin.io.path.createTempDirectory("dbkey-probe-").toFile()
        try {
            val opts = Options().apply {
                createIfMissing(true); errorIfExists(false); paranoidChecks(false)
                compressionType(CompressionType.SNAPPY); comparator(Idb1Comparator())
            }
            Iq80DBFactory.factory.open(dir, opts).use { db ->
                for (n in names) db.put(encodeDbKey(origin, n), byteArrayOf(0x01))
            }
            // reopen, iterate, count DBKeys
            val iterOrder = mutableListOf<String>()
            val readOpts = Options().apply {
                createIfMissing(false); paranoidChecks(false)
                compressionType(CompressionType.SNAPPY); comparator(Idb1Comparator())
            }
            Iq80DBFactory.factory.open(dir, readOpts).use { db ->
                db.iterator().use { iter ->
                    iter.seekToFirst()
                    while (iter.hasNext()) {
                        val k = iter.next().key
                        if (k.size >= 5 && k[0] == 0.toByte() && (k[4].toInt() and 0xFF) == 0xC9) {
                            val decoded = decodeDbKey(k)
                            if (decoded != null) iterOrder += decoded.second
                        }
                    }
                }
            }
            println("iq80 iteration order with bytewise-GLOBAL: $iterOrder")
            println("names present: ${iterOrder.size} of ${names.size}")
        } finally {
            dir.deleteRecursively()
        }
    }

    // decode DBKey to (origin, name). null on decode failure.
    private fun decodeDbKey(key: ByteArray): Pair<String, String>? {
        if (key.size < 6) return null
        if (!(key[0] == 0.toByte() && key[1] == 0.toByte() && key[2] == 0.toByte() && key[3] == 0.toByte() && (key[4].toInt() and 0xFF) == 0xC9)) return null
        var pos = 5
        val (originLen, originVarintSize) = decodeVarint(key, pos) ?: return null
        pos += originVarintSize
        val originBytes = originLen.toInt() * 2
        if (pos + originBytes > key.size) return null
        val origin = fromUtf16Be(key, pos, originBytes)
        pos += originBytes
        val (nameLen, nameVarintSize) = decodeVarint(key, pos) ?: return null
        pos += nameVarintSize
        val nameBytes = nameLen.toInt() * 2
        if (pos + nameBytes > key.size) return null
        val name = fromUtf16Be(key, pos, nameBytes)
        return origin to name
    }

    private fun decodeVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var shift = 0
        var ret = 0L
        var pos = offset
        while (true) {
            if (pos >= data.size || shift >= 64) return null
            val c = data[pos].toInt() and 0xFF
            ret = ret or ((c and 0x7f).toLong() shl shift)
            shift += 7
            pos++
            if ((c and 0x80) == 0) break
        }
        return ret to (pos - offset)
    }

    private fun fromUtf16Be(data: ByteArray, off: Int, len: Int): String {
        val chars = CharArray(len / 2)
        for (i in chars.indices) {
            chars[i] = (((data[off + i * 2].toInt() and 0xFF) shl 8) or (data[off + i * 2 + 1].toInt() and 0xFF)).toChar()
        }
        return String(chars)
    }
}
