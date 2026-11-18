package app.gamenative.gamefixes

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CabArchiveNormalizeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Entry(val name: String, val offset: Int, val size: Int, val folder: Int = 0)

    @Test
    fun collapsesAliasedEntriesOfAReserveFlaggedCabinet() {
        val cabinet = file(
            cabinet(
                listOf(
                    Entry("atl90.dll.21022.08.Microsoft_VC90_ATL_x86.RTM", 0, 100),
                    Entry("catalog.21022.08.Microsoft_VC90_CRT_x86.RTM", 100, 20),
                    Entry("msvcr90.dll.21022.08.Microsoft_VC90_CRT_x86.RTM", 120, 300),
                    Entry("nosxs_atl90.dll", 0, 100),
                    Entry("nosxs_msvcr90.dll", 120, 300),
                    Entry("ul_atl90.dll.21022.08.Microsoft_VC90_ATL_x86.RTM", 0, 100),
                ),
                reserve = 20,
            ),
        )
        val before = cabinet.readBytes()

        assertTrue(CabArchive.normalizeFileTable(cabinet))

        val after = cabinet.readBytes()
        assertEquals(before.size, after.size)
        assertEquals(3, u16(after, 28))
        assertEquals(u16(before, 30), u16(after, 30))
        assertArrayEquals(before.copyOfRange(0, 28), after.copyOfRange(0, 28))
        val tableStart = u32(after, 16).toInt()
        val entries = readEntries(after, tableStart, 3)
        assertEquals(
            listOf("nosxs_atl90.dll" to 0L, "catalog.21022.08.Microsoft_VC90_CRT_x86.RTM" to 100L, "nosxs_msvcr90.dll" to 120L),
            entries.map { it.first to it.second },
        )
        val tableEnd = tableStart + tableBytes(before, tableStart, 6)
        val keptEnd = tableStart + tableBytes(after, tableStart, 3)
        assertTrue(after.copyOfRange(keptEnd, tableEnd).all { it == 0.toByte() })
        assertArrayEquals(before.copyOfRange(tableEnd, before.size), after.copyOfRange(tableEnd, after.size))
        assertEquals(1, CabArchive.locateAll(cabinet).size)
        assertEquals(3, CabArchive.locateAll(cabinet).single().entryNames.size)
    }

    @Test
    fun leavesContiguousCabinetsUntouched() {
        val bytes = cabinet(listOf(Entry("a.dll", 0, 10), Entry("b.dll", 10, 5), Entry("c.dll", 0, 7, folder = 1)), folders = 2)
        val cabinet = file(bytes)

        assertFalse(CabArchive.normalizeFileTable(cabinet))
        assertArrayEquals(bytes, cabinet.readBytes())
    }

    @Test
    fun refusesTablesItCannotMakeContiguous() {
        val overlapping = cabinet(listOf(Entry("a.dll", 0, 10), Entry("b.dll", 0, 12), Entry("c.dll", 10, 5)))
        val gap = cabinet(listOf(Entry("a.dll", 0, 10), Entry("b.dll", 20, 5), Entry("c.dll", 0, 10)))

        for (bytes in listOf(overlapping, gap)) {
            val cabinet = file(bytes)
            assertFalse(CabArchive.normalizeFileTable(cabinet))
            assertArrayEquals(bytes, cabinet.readBytes())
        }
    }

    @Test
    fun ignoresDataThatIsNotACabinet() {
        val notCabinet = file("not a cabinet at all, just text that is long enough".toByteArray())

        assertFalse(CabArchive.normalizeFileTable(notCabinet))
    }

    private fun cabinet(entries: List<Entry>, reserve: Int = 0, folders: Int = 1): ByteArray {
        val reserveBytes = if (reserve > 0) 4 + reserve else 0
        val tableOffset = 36 + reserveBytes + folders * 8
        val table = ByteArrayOutputStream()
        for (entry in entries) {
            table.write(le32(entry.size))
            table.write(le32(entry.offset))
            table.write(le16(entry.folder))
            table.write(ByteArray(6))
            table.write(entry.name.toByteArray(Charsets.US_ASCII))
            table.write(0)
        }
        val tableBytes = table.toByteArray()
        val dataOffset = tableOffset + tableBytes.size
        val payload = ByteArray(64) { 0x11 }
        val total = dataOffset + payload.size

        val out = ByteArrayOutputStream()
        val header = ByteArray(36)
        "MSCF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        le32(total).copyInto(header, 8)
        le32(tableOffset).copyInto(header, 16)
        header[24] = 3
        header[25] = 1
        le16(folders).copyInto(header, 26)
        le16(entries.size).copyInto(header, 28)
        le16(if (reserve > 0) 4 else 0).copyInto(header, 30)
        out.write(header)
        if (reserve > 0) {
            out.write(le16(reserve))
            out.write(0)
            out.write(0)
            out.write(ByteArray(reserve) { 0x7F })
        }
        repeat(folders) { index ->
            out.write(le32(dataOffset + index * 32))
            out.write(le16(1))
            out.write(le16(0x1503))
        }
        out.write(tableBytes)
        out.write(payload)
        return out.toByteArray()
    }

    private fun readEntries(bytes: ByteArray, start: Int, count: Int): List<Pair<String, Long>> {
        val result = ArrayList<Pair<String, Long>>()
        var cursor = start
        repeat(count) {
            var end = cursor + 16
            while (bytes[end] != 0.toByte()) end++
            result += String(bytes, cursor + 16, end - cursor - 16, Charsets.US_ASCII) to u32(bytes, cursor + 4)
            cursor = end + 1
        }
        return result
    }

    private fun tableBytes(bytes: ByteArray, start: Int, count: Int): Int {
        var cursor = start
        repeat(count) {
            var end = cursor + 16
            while (bytes[end] != 0.toByte()) end++
            cursor = end + 1
        }
        return cursor - start
    }

    private fun file(bytes: ByteArray): File = temporary.newFile().apply { writeBytes(bytes) }

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

    private fun le32(value: Int) =
        byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun u16(bytes: ByteArray, index: Int) = (bytes[index].toInt() and 0xFF) or ((bytes[index + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, index: Int): Long =
        (bytes[index].toLong() and 0xFF) or ((bytes[index + 1].toLong() and 0xFF) shl 8) or
            ((bytes[index + 2].toLong() and 0xFF) shl 16) or ((bytes[index + 3].toLong() and 0xFF) shl 24)
}
