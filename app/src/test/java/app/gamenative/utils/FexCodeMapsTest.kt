package app.gamenative.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `series builds from 2611-gn-6 support code maps`() {
        assertTrue(FexCodeMaps.supportsCodeMaps("2611-gn-6"))
        assertTrue(FexCodeMaps.supportsCodeMaps("2611-gn-7"))
        assertTrue(FexCodeMaps.supportsCodeMaps("2612-gn-1"))
        assertFalse(FexCodeMaps.supportsCodeMaps("2611-gn-5"))
        assertFalse(FexCodeMaps.supportsCodeMaps("2610-gn-5"))
        assertFalse(FexCodeMaps.supportsCodeMaps("2611"))
        assertFalse(FexCodeMaps.supportsCodeMaps("2601-217d039-0"))
        assertFalse(FexCodeMaps.supportsCodeMaps(null))
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
}
