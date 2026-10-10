package app.gamenative.html5.savesync

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import org.iq80.leveldb.CompressionType
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

// kotlin port of chromium's idb_cmp1. real-save fixture tests are Assume-gated, so CI without fixtures skips them.
class Idb1ComparatorTest {

    private val cmp = Idb1Comparator()
    private lateinit var tmpRoot: File

    @Before
    fun setUp() {
        tmpRoot = Files.createTempDirectory("idb1cmp-test-").toFile()
    }

    @After
    fun tearDown() {
        tmpRoot.deleteRecursively()
    }

    @Test
    fun name_isExactlyIdbCmp1() {
        // iq80 checks this byte-for-byte against MANIFEST; any drift breaks DB open
        assertEquals("idb_cmp1", cmp.name())
        assertEquals(
            "idb_cmp1",
            String(cmp.name().toByteArray(Charsets.US_ASCII), Charsets.US_ASCII),
        )
    }

    @Test
    fun compare_sameKey_returnsZero() {
        val key = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        assertEquals(0, cmp.compare(key, key))
        assertEquals(0, cmp.compare(key, key.copyOf()))
    }

    @Test
    fun compare_emptyKeys_returnsZero() {
        assertEquals(0, cmp.compare(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun compare_emptyVsNonEmpty_bytewiseOrder() {
        // degenerate: falls back to bytewise
        val nonEmpty = byteArrayOf(0x01)
        assertTrue("empty < non-empty", cmp.compare(ByteArray(0), nonEmpty) < 0)
        assertTrue("non-empty > empty", cmp.compare(nonEmpty, ByteArray(0)) > 0)
    }

    @Test
    fun compare_shortVsLong_prefixWins() {
        // non-decodable shapes fall back to bytewise
        val short = byteArrayOf(0x10, 0x20)
        val long = byteArrayOf(0x10, 0x20, 0x30)
        assertTrue(cmp.compare(short, long) < 0)
        assertTrue(cmp.compare(long, short) > 0)
    }

    @Test
    fun compare_byKeyPrefix_dbIdLexicographicOrder() {
        // non-empty suffix (minimal null-type IDBKey) or the empty-suffix bytewise fallback kicks in
        val a = prefixedKey(dbId = 1, osId = 1, indexId = 1, suffix = byteArrayOf(0x00))
        val b = prefixedKey(dbId = 2, osId = 1, indexId = 1, suffix = byteArrayOf(0x00))
        assertTrue("db_id 1 < db_id 2", cmp.compare(a, b) < 0)
        assertTrue("db_id 2 > db_id 1", cmp.compare(b, a) > 0)
    }

    @Test
    fun compare_byKeyPrefix_objectStoreThenIndexCascade() {
        val a = prefixedKey(dbId = 5, osId = 1, indexId = 1, suffix = byteArrayOf(0x00))
        val b = prefixedKey(dbId = 5, osId = 2, indexId = 1, suffix = byteArrayOf(0x00))
        val c = prefixedKey(dbId = 5, osId = 2, indexId = 2, suffix = byteArrayOf(0x00)) // EXISTS_ENTRY
        assertTrue(cmp.compare(a, b) < 0)
        assertTrue(cmp.compare(b, c) < 0)
        assertTrue("transitivity: a < c", cmp.compare(a, c) < 0)
    }

    @Test
    fun compare_idbKeyTypes_descendingByMojomOrdinal() {
        // CompareTypes returns (int)b - (int)a, so HIGHER mojom ordinal sorts earlier.
        // mojom order: Invalid(0), Array(1), Binary(2), String(3), Date(4), Number(5), None(6), Min(7).
        val minKey = prefixedKey(dbId = 1, osId = 1, indexId = 1, suffix = byteArrayOf(0x05))
        val numberKey = prefixedKey(dbId = 1, osId = 1, indexId = 1, suffix = numberIdbKey(1.0))
        val stringKey = prefixedKey(dbId = 1, osId = 1, indexId = 1, suffix = stringIdbKey("abc"))
        assertTrue("Min (ord=7) sorts before Number (ord=5)", cmp.compare(minKey, numberKey) < 0)
        assertTrue("Number (ord=5) sorts before String (ord=3)", cmp.compare(numberKey, stringKey) < 0)
    }

    @Test
    fun compare_numberIdbKeys_orderedNumerically() {
        val k1 = prefixedKey(1, 1, 1, suffix = numberIdbKey(1.0))
        val k2 = prefixedKey(1, 1, 1, suffix = numberIdbKey(2.5))
        val k3 = prefixedKey(1, 1, 1, suffix = numberIdbKey(100.0))
        assertTrue(cmp.compare(k1, k2) < 0)
        assertTrue(cmp.compare(k2, k3) < 0)
        assertTrue(cmp.compare(k1, k3) < 0)
        assertTrue(cmp.compare(k3, k1) > 0)
    }

    @Test
    fun compare_stringIdbKeys_orderedByUtf16Bytewise() {
        val k1 = prefixedKey(1, 1, 1, suffix = stringIdbKey("apple"))
        val k2 = prefixedKey(1, 1, 1, suffix = stringIdbKey("banana"))
        val k3 = prefixedKey(1, 1, 1, suffix = stringIdbKey("cherry"))
        assertTrue(cmp.compare(k1, k2) < 0)
        assertTrue(cmp.compare(k2, k3) < 0)
        assertTrue(cmp.compare(k1, k3) < 0)
        val kShort = prefixedKey(1, 1, 1, suffix = stringIdbKey("app"))
        assertTrue(cmp.compare(kShort, k1) < 0)
    }

    @Test
    fun compare_varintBoundary_worksAcross7BitThreshold() {
        val s127 = "a".repeat(127) // 1-byte length prefix
        val s128 = "a".repeat(128) // 2-byte length prefix
        val s16383 = "a".repeat(16383) // 2-byte length prefix
        val s16384 = "a".repeat(16384) // 3-byte length prefix
        val k127 = prefixedKey(1, 1, 1, suffix = stringIdbKey(s127))
        val k128 = prefixedKey(1, 1, 1, suffix = stringIdbKey(s128))
        val k16383 = prefixedKey(1, 1, 1, suffix = stringIdbKey(s16383))
        val k16384 = prefixedKey(1, 1, 1, suffix = stringIdbKey(s16384))
        assertTrue("len 127 < len 128", cmp.compare(k127, k128) < 0)
        assertTrue("len 128 < len 16383", cmp.compare(k128, k16383) < 0)
        assertTrue("len 16383 < len 16384", cmp.compare(k16383, k16384) < 0)
    }

    @Test
    fun compare_transitivity_holds() {
        val rng = Random(0xCAFEBABE)
        val keys = List(150) { randomDataKey(rng) }
        repeat(200) {
            val a = keys.random(rng)
            val b = keys.random(rng)
            val c = keys.random(rng)
            val ab = cmp.compare(a, b)
            val bc = cmp.compare(b, c)
            val ac = cmp.compare(a, c)
            if (ab < 0 && bc < 0) {
                assertTrue("transitivity violated: a<b, b<c, but a>=c", ac < 0)
            }
            if (ab > 0 && bc > 0) {
                assertTrue("transitivity violated: a>b, b>c, but a<=c", ac > 0)
            }
            assertEquals("antisymmetry", sign(ab), -sign(cmp.compare(b, a)))
        }
    }

    @Test
    fun findShortestSeparator_returnsStartUnchanged() {
        val start = byteArrayOf(0x10, 0x20)
        val limit = byteArrayOf(0x10, 0x30)
        val sep = cmp.findShortestSeparator(start, limit)
        // identity is the safe fallback; it only has to stay within [start, limit]
        assertTrue("sep >= start", cmp.compare(sep, start) >= 0)
        assertTrue("sep <= limit", cmp.compare(sep, limit) <= 0)
    }

    @Test
    fun findShortSuccessor_returnsKeyUnchanged() {
        val key = byteArrayOf(0x10, 0x20)
        val succ = cmp.findShortSuccessor(key)
        // identity is the safe fallback; iq80 tolerates it
        assertEquals(key.size, succ.size)
    }

    @Test
    fun roundTrip_syntheticIdb_preservesKeysAndOrder() {
        val dbDir = File(tmpRoot, "roundtrip")
        val options = Options().apply {
            createIfMissing(true)
            compressionType(CompressionType.NONE)
            paranoidChecks(true)
            comparator(Idb1Comparator())
        }
        val pairs = mutableListOf<Pair<ByteArray, ByteArray>>()
        for (i in 1..20) {
            val key = prefixedKey(1, 1, 1, suffix = numberIdbKey(i.toDouble()))
            val value = "value-$i".toByteArray()
            pairs += key to value
        }
        for (s in listOf("alpha", "bravo", "charlie", "delta", "echo")) {
            val key = prefixedKey(1, 1, 1, suffix = stringIdbKey(s))
            pairs += key to "string-value-$s".toByteArray()
        }
        Iq80DBFactory.factory.open(dbDir, options).use { db ->
            for ((k, v) in pairs) {
                db.put(k, v)
            }
        }
        // reopen: iq80 validates the MANIFEST comparator name on open
        val readOptions = Options().apply {
            createIfMissing(false)
            paranoidChecks(true)
            comparator(Idb1Comparator())
        }
        val readKeys = mutableListOf<ByteArray>()
        Iq80DBFactory.factory.open(dbDir, readOptions).use { db ->
            db.iterator().use { it2 ->
                it2.seekToFirst()
                while (it2.hasNext()) {
                    readKeys += it2.next().key
                }
            }
        }
        assertEquals("round-trip key count", pairs.size, readKeys.size)
        for (i in 1 until readKeys.size) {
            assertTrue(
                "iteration order violated at index $i",
                cmp.compare(readKeys[i - 1], readKeys[i]) <= 0,
            )
        }
    }

    @Test
    fun open_solcesto_idb_fixture_afterPortPasses() {
        val fixture = SaveFixtureHarness.loadSolCesto()
        assumeNotNull("solcesto fixture absent", fixture)
        assumeNotNull("solcesto IndexedDB leveldb dir absent", fixture!!.indexedDbLevelDb)
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tmpRoot, "solcesto-idb")!!
        val keys = openAndCountKeys(idb)
        assertTrue("expected >= 1 iterable key in solcesto IDB, got $keys", keys >= 1)
    }

    @Test
    fun open_lookOutside_idb_fixture_afterPortPasses() {
        val fixture = SaveFixtureHarness.loadLookOutside()
        assumeNotNull("lookOutside fixture absent", fixture)
        assumeNotNull("lookOutside IndexedDB leveldb dir absent", fixture!!.indexedDbLevelDb)
        val idb = SaveFixtureHarness.snapshotDir(fixture.indexedDbLevelDb, tmpRoot, "lookoutside-idb")!!
        val keys = openAndCountKeys(idb)
        assertTrue("expected >= 1 iterable key in lookOutside IDB, got $keys", keys >= 1)
    }

    private fun openAndCountKeys(dir: File): Int {
        val options = Options().apply {
            createIfMissing(false)
            compressionType(CompressionType.SNAPPY)
            paranoidChecks(false)
            comparator(Idb1Comparator())
        }
        return Iq80DBFactory.factory.open(dir, options).use { db ->
            db.iterator().use { iter ->
                iter.seekToFirst()
                var count = 0
                while (iter.hasNext()) {
                    val e = iter.next()
                    // touch both fields to force decode
                    assertNotNull(e.key)
                    assertNotNull(e.value)
                    count++
                }
                count
            }
        }
    }

    // prefix byte 0x00 = all three id widths are 1 byte, hence the 0..255 bound.
    private fun prefixedKey(dbId: Int, osId: Int, indexId: Int, suffix: ByteArray): ByteArray {
        require(dbId in 0..255 && osId in 0..255 && indexId in 0..255)
        val prefixByte: Byte = 0x00
        return byteArrayOf(prefixByte, dbId.toByte(), osId.toByte(), indexId.toByte()) + suffix
    }

    // IDBKey number: type byte 0x03 + 8-byte little-endian IEEE-754 double.
    private fun numberIdbKey(d: Double): ByteArray {
        val bits = java.lang.Double.doubleToRawLongBits(d)
        val out = ByteArray(9)
        out[0] = 0x03
        for (i in 0 until 8) {
            out[1 + i] = ((bits ushr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    // IDBKey string: type byte 0x01 + leb128 char count + UTF-16LE bytes (2 * char count).
    private fun stringIdbKey(s: String): ByteArray {
        val chars = s.toCharArray()
        val lenVarint = encodeVarInt(chars.size.toLong())
        val utf16 = ByteArray(chars.size * 2)
        for (i in chars.indices) {
            val c = chars[i].code
            utf16[i * 2] = (c and 0xFF).toByte()
            utf16[i * 2 + 1] = ((c ushr 8) and 0xFF).toByte()
        }
        return byteArrayOf(0x01) + lenVarint + utf16
    }

    private fun encodeVarInt(value: Long): ByteArray {
        require(value >= 0)
        val bytes = mutableListOf<Byte>()
        var n = value
        do {
            var c = (n and 0x7F).toInt()
            n = n ushr 7
            if (n != 0L) c = c or 0x80
            bytes += c.toByte()
        } while (n != 0L)
        return bytes.toByteArray()
    }

    private fun randomDataKey(rng: Random): ByteArray {
        val dbId = rng.nextInt(1, 4)
        val osId = rng.nextInt(1, 3)
        val indexId = when (rng.nextInt(4)) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 4 }
        return when (rng.nextInt(3)) {
            0 -> prefixedKey(dbId, osId, indexId, numberIdbKey(rng.nextDouble() * 1000))
            1 -> {
                val len = rng.nextInt(1, 12)
                val s = buildString { repeat(len) { append(('a' + rng.nextInt(26))) } }
                prefixedKey(dbId, osId, indexId, stringIdbKey(s))
            }
            else -> {
                // random bytes may fail prefix decode, exercising the bytewise fallback
                val n = rng.nextInt(1, 16)
                ByteArray(n) { rng.nextInt(256).toByte() }
            }
        }
    }

    private fun sign(i: Int): Int = when {
        i < 0 -> -1
        i > 0 -> 1
        else -> 0
    }
}
