package app.gamenative.service.ubisoft

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class NsisReaderTest {
    @Test fun listsFilesWithTheirOutputFolders() {
        val one = ByteArray(5000) { (it % 7).toByte() }
        val two = "stored".toByteArray()
        val installer = UbisoftNsisFixture.Builder()
            .outPath().file("a.dll", one)
            .outPath("sub").file("b.dll", two, compress = false)
            .outPath().file("c.txt", two, compress = false)
            .build(stubSize = 2048)
        val reader = NsisReader(ByteArraySource(installer))
        val archive = reader.open()
        assertEquals(2048L, archive.firstHeaderOffset)
        assertTrue(archive.unicode)
        assertEquals(listOf("a.dll", "b.dll", "c.txt"), archive.entries.map { it.name })
        assertEquals(listOf(true, false, true), archive.entries.map { it.isRootLevel })
        assertArrayEquals(one, reader.extract(archive, archive.entries[0]))
        assertArrayEquals(two, reader.extract(archive, archive.entries[1]))
        assertArrayEquals(two, reader.extract(archive, archive.entries[2]))
    }

    @Test fun findsTheHeaderOnlyAtSectorBoundaries() {
        val installer = UbisoftNsisFixture.Builder().outPath().file("a.dll", ByteArray(10)).build(stubSize = 512 * 300)
        assertEquals(512L * 300, NsisReader(ByteArraySource(installer)).open().firstHeaderOffset)
    }

    @Test fun refusesAFileWithoutAnInstallerHeader() {
        assertThrows(IllegalStateException::class.java) { NsisReader(ByteArraySource(ByteArray(4096))).open() }
    }

    @Test fun refusesATruncatedEntry() {
        val installer = UbisoftNsisFixture.Builder().outPath().file("a.dll", ByteArray(20000) { it.toByte() }).build()
        val cut = installer.copyOf(installer.size - 100)
        val reader = NsisReader(ByteArraySource(cut))
        val archive = reader.open()
        assertThrows(java.io.EOFException::class.java) { reader.extract(archive, archive.entries[0]) }
    }

    @Test fun inflateHonorsItsLimit() {
        val packed = UbisoftNsisFixture.deflate(ByteArray(100_000))
        assertThrows(IllegalStateException::class.java) { NsisReader.inflate(packed, 1000) }
        assertEquals(100_000, NsisReader.inflate(packed, 200_000).size)
    }

    @Test fun readsPeMachineAndExportName() {
        val dll = PeImage.parse(UbisoftNsisFixture.pe(PeImage.MACHINE_X86, "uplay_r1.dll"))
        assertEquals(PeImage.MACHINE_X86, dll.machine)
        assertTrue(dll.isDll)
        assertEquals("uplay_r1.dll", dll.exportName)

        val exe = PeImage.parse(UbisoftNsisFixture.pe(PeImage.MACHINE_X64, null))
        assertEquals(PeImage.MACHINE_X64, exe.machine)
        assertFalse(exe.isDll)
        assertNull(exe.exportName)

        assertThrows(IllegalStateException::class.java) { PeImage.parse(ByteArray(200)) }
    }
}
