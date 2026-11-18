package app.gamenative.powercontrol.metrics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeverSignalsTest {

    private fun kinds(layout: List<CpuClusterKind>?) = layout?.map { it.cores to it.kind }

    @Test
    fun `three capacity groups are little, big and prime`() {
        // Snapdragon 8 Gen 2: 3 little, 4 big at two capacities, 1 prime.
        val caps = mapOf(0 to 325L, 1 to 325L, 2 to 325L, 3 to 828L, 4 to 828L, 5 to 828L, 6 to 828L, 7 to 1024L)
        assertEquals(
            listOf(listOf(0, 1, 2) to "little", listOf(3, 4, 5, 6) to "big", listOf(7) to "prime"),
            kinds(CpuClusterLayout.fromCapacities(caps)),
        )
    }

    @Test
    fun `two groups name the lower one little only when it is far below the top`() {
        // Snapdragon 8 Elite: 6 performance cores at 792 of 1024 are big, not little.
        val elite = (0..5).associateWith { 792L } + mapOf(6 to 1024L, 7 to 1024L)
        assertEquals(listOf((0..5).toList() to "big", listOf(6, 7) to "prime"), kinds(CpuClusterLayout.fromCapacities(elite)))
        val bigLittle = (0..3).associateWith { 400L } + (4..7).associateWith { 1024L }
        assertEquals(listOf((0..3).toList() to "little", (4..7).toList() to "prime"), kinds(CpuClusterLayout.fromCapacities(bigLittle)))
    }

    @Test
    fun `one capacity group is all big and no capacity is no layout`() {
        assertEquals(listOf((0..3).toList() to "big"), kinds(CpuClusterLayout.fromCapacities((0..3).associateWith { 1024L })))
        assertNull(CpuClusterLayout.fromCapacities(emptyMap()))
    }

    @Test
    fun `power control grouping maps efficiency, performance and prime`() {
        val layout = CpuClusterLayout.fromPowerControl(
            listOf("efficiency" to listOf(0, 1), "performance" to listOf(2, 3, 4), "prime" to listOf(5), "prime" to emptyList()),
        )
        assertEquals(listOf(listOf(0, 1) to "little", listOf(2, 3, 4) to "big", listOf(5) to "prime"), kinds(layout))
        assertNull(CpuClusterLayout.fromPowerControl(emptyList()))
    }

    @Test
    fun `core share counts busy seconds on known cores only`() {
        val layout = listOf(CpuClusterKind(listOf(0, 1), "little"), CpuClusterKind(listOf(2, 3), "big"), CpuClusterKind(listOf(4), "prime"))
        val samples = listOf(
            90 to 4, 95 to 4, 80 to 2, 70 to 0, // busy: prime, prime, big, little
            40 to 0, // not busy
            null to 4, 90 to null, // unknown
            90 to 9, // core outside the layout
        )
        val share = CpuClusterLayout.coreShare(samples, layout)!!
        assertEquals(0.25, share.getValue("little"), 1e-9)
        assertEquals(0.25, share.getValue("big"), 1e-9)
        assertEquals(0.5, share.getValue("prime"), 1e-9)
        assertNull(CpuClusterLayout.coreShare(listOf(30 to 4), layout))
        assertNull(CpuClusterLayout.coreShare(samples, null))
    }

    @Test
    fun `vmstat sums kswapd and direct scans and leaves throttle out`() {
        val text = """
            nr_free_pages 1000
            pgmajfault 5000
            pgscan_kswapd 700
            pgscan_direct 300
            pgscan_direct_throttle 99
            pgscan_khugepaged 50
        """.trimIndent()
        val r = VmStat.parse(text.lineSequence())!!
        assertEquals(5000L, r.majorFaults)
        assertEquals(1000L, r.reclaimScanned)
        val old = VmStat.parse(sequenceOf("pgmajfault 1", "pgscan_kswapd_normal 10", "pgscan_kswapd_movable 5", "pgscan_direct_normal 2"))!!
        assertEquals(17L, old.reclaimScanned)
        assertNull(VmStat.parse(sequenceOf("nr_free_pages 1")))
    }

    @Test
    fun `window counter keeps per-window deltas aligned`() {
        val c = WindowCounter()
        c.add(0, 10)
        c.add(0, 5)
        c.add(2, 7)
        c.add(1, -3)
        c.touch(3)
        assertEquals(listOf(15L, 0L, 7L, 0L), c.toList())
    }

    private fun cache(version: Int, entries: List<Pair<Int, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(v: Long) = repeat(4) { out.write(((v ushr (8 * it)) and 0xFF).toInt()) }
        out.write("DXVK".toByteArray())
        le(version.toLong())
        le(0)
        for ((stages, size) in entries) {
            le(stages.toLong() or (size.toLong() shl 8))
            out.write(ByteArray(20))
            out.write(ByteArray(size))
        }
        return out.toByteArray()
    }

    @Test
    fun `state cache scan counts new entries and finds tessellation`() {
        val vsFs = 0x1 or 0x10
        val tess = 0x1 or 0x2 or 0x4 or 0x10
        val bytes = cache(16, listOf(vsFs to 100, vsFs to 100, tess to 200))
        val firstNew = 12L + (4 + 20 + 100) // the second entry starts here
        val scan = DxvkStateCache.scan(ByteArrayInputStream(bytes), firstNew) { false }!!
        assertEquals(2, scan.entries)
        assertEquals((4 + 20 + 100L) + (4 + 20 + 200L), scan.bytes)
        assertEquals(true, scan.tessellation)

        val none = DxvkStateCache.scan(ByteArrayInputStream(cache(16, listOf(vsFs to 50))), 0) { false }!!
        assertEquals(false, none.tessellation)

        // A truncated last entry (the game was writing it) is not counted.
        val cut = cache(16, listOf(vsFs to 50, vsFs to 50)).let { it.copyOf(it.size - 10) }
        assertEquals(1, DxvkStateCache.scan(ByteArrayInputStream(cut), 0) { false }!!.entries)
    }

    @Test
    fun `the running scan reads only what was appended and waits for a half-written entry`() {
        val vsFs = 0x1 or 0x10
        val tess = 0x1 or 0x2 or 0x4 or 0x10
        val file = java.io.File.createTempFile("game", ".dxvk-cache").apply { deleteOnExit() }
        val start = cache(16, listOf(vsFs to 100))
        file.writeBytes(start)
        val scanner = DxvkStateCache.Incremental(file, start.size.toLong())
        scanner.advance { false }
        assertEquals(0, scanner.latest!!.entries)
        assertEquals(false, scanner.latest!!.tessellation)
        assertTrue(scanner.caughtUp)

        val grown = cache(16, listOf(vsFs to 100, vsFs to 60, tess to 80))
        // The last entry is still being written: only the complete one counts for now.
        file.writeBytes(grown.copyOf(grown.size - 30))
        scanner.advance { false }
        assertEquals(1, scanner.latest!!.entries)
        assertEquals(false, scanner.latest!!.tessellation)

        file.writeBytes(grown)
        scanner.advance { false }
        assertEquals(2, scanner.latest!!.entries)
        assertEquals((4 + 20 + 60L) + (4 + 20 + 80L), scanner.latest!!.bytes)
        assertEquals(true, scanner.latest!!.tessellation)

        // Out of time before the end: no claim about tessellation, and the next pass carries on.
        val many = cache(16, List(100) { vsFs to 40 })
        file.writeBytes(many)
        val slow = DxvkStateCache.Incremental(file, 0)
        slow.advance { true }
        assertFalse(slow.caughtUp)
        assertNull(slow.latest!!.tessellation)
        slow.advance { false }
        assertEquals(100, slow.latest!!.entries)
        assertEquals(false, slow.latest!!.tessellation)

        file.writeBytes(cache(7, emptyList()))
        val old = DxvkStateCache.Incremental(file, 0)
        old.advance { false }
        assertNull(old.latest)
    }

    @Test
    fun `state cache scan that runs out of time does not claim no tessellation`() {
        val bytes = cache(16, List(600) { 0x11 to 40 })
        assertNull(DxvkStateCache.scan(ByteArrayInputStream(bytes), 0) { true }!!.tessellation)
    }

    @Test
    fun `old fixed-size caches are not scanned and use the header entry size`() {
        val header = DxvkStateCache.parseHeader(byteArrayOf('D'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte(), 'K'.code.toByte(), 7, 0, 0, 0, 0x90.toByte(), 1, 0, 0))!!
        assertFalse(header.variable)
        assertEquals(400, header.entrySize)
        assertNull(DxvkStateCache.scan(ByteArrayInputStream(cache(7, emptyList())), 0) { false })
        assertEquals(400.0, DxvkStateCache.entryBytes(header, null), 1e-9)
        assertNull(DxvkStateCache.parseHeader("NOPE00000000".toByteArray()))
    }

    @Test
    fun `growth per window turns into compiles`() {
        val scan = DxvkStateCache.Scan(entries = 10, bytes = 2000, tessellation = false)
        val perEntry = DxvkStateCache.entryBytes(DxvkStateCache.Header(16, 0), scan)
        assertEquals(200.0, perEntry, 1e-9)
        assertEquals(listOf(6, 0, 4), DxvkStateCache.compilesByWindow(listOf(1200L, 0L, 800L), perEntry))
        // Without a scan or a fixed size the documented constant stands in.
        assertEquals(DxvkStateCache.DEFAULT_ENTRY_BYTES.toDouble(), DxvkStateCache.entryBytes(DxvkStateCache.Header(16, 0), null), 1e-9)
        assertTrue(DxvkStateCache.compilesByWindow(listOf(512L), DxvkStateCache.DEFAULT_ENTRY_BYTES.toDouble()) == listOf(2))
    }
}
