package app.gamenative.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FexCodeMapsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Written by FEX 2611-gn-6 on a Retroid Pocket 6 for Steam's loader: 318 blocks, 4 libraries.
    private val fixtureName = "steamclient_loader_x64.exe-74ec33596a84c560.134384505043741006.bin"

    private fun fixture(): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fexcodemaps/$fixtureName")!!.use { it.readBytes() }
        return tmp.newFile(fixtureName).apply { writeBytes(bytes) }
    }

    @Test
    fun `a pack supports caches when it ships the offline compiler`() {
        val pack = tmp.newFolder("2611-gn-6")
        assertFalse(FexCodeMaps.packSupportsCodeCaches(pack))
        assertFalse(FexCodeMaps.packSupportsCodeCaches(null))
        File(pack, "system32").mkdirs()
        File(pack, "system32/FEXOfflineCompiler64.exe").writeBytes(ByteArray(16))
        assertTrue(FexCodeMaps.packSupportsCodeCaches(pack))
    }

    @Test
    fun `reads a real map written by FEX`() {
        val info = FexCodeMaps.readInfo(fixture())!!
        assertEquals("steamclient_loader_x64.exe", info.mainExe)
        assertEquals("74ec33596a84c560", info.mainFileId)
        assertEquals(64, info.bitness)
        assertEquals(318, info.entries)
        assertEquals(4, info.libraries)
    }

    @Test
    fun `rejects an empty or truncated map`() {
        assertNull(FexCodeMaps.readInfo(tmp.newFile("winhandler.exe-48ac2ba1b68866ae.1.bin")))
        val full = fixture()
        val truncated = tmp.newFile("truncated.exe-74ec33596a84c560.2.bin")
        truncated.writeBytes(full.readBytes().copyOf(full.length().toInt() - 5))
        assertNull(FexCodeMaps.readInfo(truncated))
    }

    @Test
    fun `rejects a map without a main executable record`() {
        val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(1L).putInt(0x1000).putLong(1L).putInt(0x2000)
        val file = tmp.newFile("blocks.exe-0000000000000001.3.bin").apply { writeBytes(buf.array()) }
        assertNull(FexCodeMaps.readInfo(file))
    }

    @Test
    fun `a delta keeps the records and only the unsent blocks`() {
        val full = fixture()
        val first = tmp.newFile("first.exe-74ec33596a84c560.4.bin")
        assertEquals(318, FexCodeMaps.writeDelta(full, first, emptySet()))
        assertEquals(full.length(), first.length())

        // Pretend every block of the first map was sent: the next delta has the records but no blocks.
        val sent = HashSet<Long>()
        val buf = java.nio.ByteBuffer.wrap(full.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 12) {
            val fileId = buf.long
            val off = buf.int
            if (fileId == -1L) {
                buf.long
                if (off == -1) {
                    var end = buf.position()
                    while (buf.get(end) != 0.toByte()) end++
                    buf.position((end + 1 + 3) and 3.inv())
                }
            } else {
                sent.add(fileId * 31 + off.toLong().and(0xffffffffL))
            }
        }
        val second = tmp.newFile("second.exe-74ec33596a84c560.5.bin")
        assertEquals(0, FexCodeMaps.writeDelta(full, second, sent))
        assertEquals(full.length() - 318L * 12, second.length()) // records kept, every block gone
        assertNull(FexCodeMaps.readInfo(second)) // no blocks: not worth sending
    }

    @Test
    fun `blocks sent by one map are not sent again by an overlapping map in the same pass`() {
        val first = fixture()
        val sent = HashSet<Long>()
        val firstDelta = tmp.newFile("first.exe-74ec33596a84c560.6.bin")
        assertEquals(318, FexCodeMaps.writeDelta(first, firstDelta, sent))
        sent.addAll(FexCodeMaps.blockKeysOf(firstDelta)) // what a successful upload records

        // A second map from another process of the same session covering the same blocks.
        val overlapping = tmp.newFile("second.exe-74ec33596a84c560.7.bin").apply { writeBytes(first.readBytes()) }
        val secondDelta = tmp.newFile("second.delta")
        assertEquals(0, FexCodeMaps.writeDelta(overlapping, secondDelta, sent))
    }

    private fun mapWithPairs(vararg pairs: Pair<Int, Int>): ByteArray {
        val path = "C:\\game\\game.exe".toByteArray()
        val libraryBytes = (12 + 8 + path.size + 1 + 3) and 3.inv()
        val buf = ByteBuffer.allocate(20 + libraryBytes + 12 + pairs.size * 36).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(-1L).putInt(-17).putLong(7L)
        val start = buf.position()
        buf.putLong(-1L).putInt(-1).putLong(7L).put(path).put(0)
        buf.position(start + libraryBytes)
        buf.putLong(7L).putInt(0x1000)
        for ((site, target) in pairs) buf.putLong(-1L).putInt(-35).putLong(7L).putInt(site).putLong(7L).putInt(target)
        return buf.array()
    }

    @Test
    fun `reads indirect branch records`() {
        val file = tmp.newFile("game.exe-0000000000000007.8.bin").apply { writeBytes(mapWithPairs(0x1010 to 0x2000, 0x1010 to 0x3000)) }
        val info = FexCodeMaps.readInfo(file)!!
        assertEquals(1, info.entries)
        assertEquals(1, info.libraries)
        assertEquals(2, info.indirectBranches)
        assertEquals(2, FexCodeMaps.pairKeysOf(file).size)
    }

    @Test
    fun `a delta keeps only unsent pairs, and none while the server does not take them`() {
        val full = tmp.newFile("game.exe-0000000000000007.9.bin").apply { writeBytes(mapWithPairs(0x1010 to 0x2000, 0x1010 to 0x3000)) }

        val withoutPairs = tmp.newFile("without.delta")
        assertEquals(1, FexCodeMaps.writeDelta(full, withoutPairs, emptySet(), emptySet(), keepPairs = false))
        assertEquals(0, FexCodeMaps.readInfo(withoutPairs)!!.indirectBranches)

        val first = tmp.newFile("first.delta")
        FexCodeMaps.writeDelta(full, first, emptySet(), emptySet(), keepPairs = true)
        val sentPairs = FexCodeMaps.pairKeysOf(first)
        val sentBlocks = FexCodeMaps.blockKeysOf(first)

        val next = tmp.newFile("game.exe-0000000000000007.10.bin").apply { writeBytes(mapWithPairs(0x1010 to 0x2000, 0x1020 to 0x2000)) }
        val second = tmp.newFile("second.delta")
        assertEquals(0, FexCodeMaps.writeDelta(next, second, sentBlocks, sentPairs, keepPairs = true))
        assertEquals(1, FexCodeMaps.readInfo(second)!!.indirectBranches)

        val again = tmp.newFile("third.delta")
        FexCodeMaps.writeDelta(next, again, sentBlocks, sentPairs + FexCodeMaps.pairKeysOf(second), keepPairs = true)
        assertNull(FexCodeMaps.readInfo(again))
    }

    // Written by FEX 2611.1-gn9 on an AYN Odin 3 for the Castlevania Belmont's Curse demo with indirect branch
    // recording on, cut to its first 300 blocks and 700 indirect branch records (all 33 library records kept).
    private val indirectFixtureName = "castlevania_demo.exe-dc4dfc928a9853a9.134401664583785092.bin"

    private fun indirectFixture(): File {
        val bytes = javaClass.classLoader!!.getResourceAsStream("fexcodemaps/$indirectFixtureName")!!.use { it.readBytes() }
        return tmp.newFile(indirectFixtureName).apply { writeBytes(bytes) }
    }

    @Test
    fun `reads a real map with indirect branch records`() {
        val map = indirectFixture()
        val info = FexCodeMaps.readInfo(map)!!
        assertEquals("castlevania_demo.exe", info.mainExe)
        assertEquals("dc4dfc928a9853a9", info.mainFileId)
        assertEquals(64, info.bitness)
        assertEquals(300, info.entries)
        assertEquals(33, info.libraries)
        assertEquals(700, info.indirectBranches)
        assertEquals(700, FexCodeMaps.pairKeysOf(map).size)
    }

    @Test
    fun `a real map's pairs go up once`() {
        val map = indirectFixture()
        val first = tmp.newFile("first-real.delta")
        assertEquals(300, FexCodeMaps.writeDelta(map, first, emptySet(), emptySet(), keepPairs = true))
        assertEquals(map.length(), first.length())

        val second = tmp.newFile("second-real.delta")
        assertEquals(0, FexCodeMaps.writeDelta(map, second, FexCodeMaps.blockKeysOf(first), FexCodeMaps.pairKeysOf(first), keepPairs = true))
        assertNull(FexCodeMaps.readInfo(second))

        val withoutPairs = tmp.newFile("without-real.delta")
        assertEquals(300, FexCodeMaps.writeDelta(map, withoutPairs, emptySet(), emptySet(), keepPairs = false))
        assertEquals(map.length() - 700L * 36, withoutPairs.length())
    }

    @Test
    fun `a map without indirect branch records is unchanged by the pair handling`() {
        val full = fixture()
        val delta = tmp.newFile("old-format.delta")
        assertEquals(318, FexCodeMaps.writeDelta(full, delta, emptySet(), emptySet(), keepPairs = true))
        assertTrue(full.readBytes().contentEquals(delta.readBytes()))
        assertEquals(0, FexCodeMaps.readInfo(full)!!.indirectBranches)
    }

    private fun mapStartedAt(ms: Long) = tmp.newFile("game.exe-0000000000000007.${ms * 10_000 + 116444736000000000L}.bin")

    @Test
    fun `a queued map keeps its own launch's options when a later launch has others`() {
        val sessions = tmp.newFolder("sessions")
        val tA = 1_795_692_858_000L
        val tB = tA + 3_600_000L
        val intermediate = mapOf("TSOEnabled" to "1", "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0", "HalfBarrierTSOEnabled" to "1", "Multiblock" to "1")
        val performance = mapOf("TSOEnabled" to "0", "VectorTSOEnabled" to "0", "MemcpySetTSOEnabled" to "0", "HalfBarrierTSOEnabled" to "0", "Multiblock" to "1")

        // Launch A writes a map whose upload fails, so it is still queued when launch B starts with other options.
        FexCodeMaps.writeSessionOptions(sessions, tA, "INTERMEDIATE", intermediate)
        val mapA = mapStartedAt(tA + 5_000)
        FexCodeMaps.writeSessionOptions(sessions, tB, "PERFORMANCE", performance)
        val mapB = mapStartedAt(tB + 5_000)
        val older = mapStartedAt(tA - 60_000)

        val metaA = JSONObject().also { FexCodeMaps.putSessionOptions(it, FexCodeMaps.sessionOptionsFor(sessions, mapA)) }
        assertEquals("INTERMEDIATE", metaA.getString("fexPreset"))
        assertEquals(true, metaA.get("fexTsoEnabled"))
        assertEquals(false, metaA.get("fexVectorTsoEnabled"))
        assertEquals(true, metaA.get("fexHalfBarrierTsoEnabled"))

        val metaB = JSONObject().also { FexCodeMaps.putSessionOptions(it, FexCodeMaps.sessionOptionsFor(sessions, mapB)) }
        assertEquals("PERFORMANCE", metaB.getString("fexPreset"))
        assertEquals(false, metaB.get("fexTsoEnabled"))
        assertEquals(false, metaB.get("fexHalfBarrierTsoEnabled"))
        assertEquals(true, metaB.get("fexMultiblock"))

        val metaOlder = JSONObject().also { FexCodeMaps.putSessionOptions(it, FexCodeMaps.sessionOptionsFor(sessions, older)) }
        assertTrue(metaOlder.isNull("fexPreset"))
        assertTrue(metaOlder.isNull("fexTsoEnabled"))
    }

    @Test
    fun `a map whose launch was pruned gets no options rather than a newer launch's`() {
        val sessions = tmp.newFolder("pruned")
        val t0 = 1_795_692_858_000L
        val map = mapStartedAt(t0 + 1_000)
        for (i in 0..10) FexCodeMaps.writeSessionOptions(sessions, t0 + i * 60_000L, "P$i", mapOf("TSOEnabled" to "1"))
        assertEquals(10, sessions.listFiles()!!.size)
        assertNull(FexCodeMaps.sessionOptionsFor(sessions, map))
        assertEquals("P10", FexCodeMaps.sessionOptionsFor(sessions, mapStartedAt(t0 + 11 * 60_000L))!!.getString("preset"))
    }
}
