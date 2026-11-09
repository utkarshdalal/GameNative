package app.gamenative.utils.launchdependencies

import app.gamenative.data.GameSource
import com.winlator.container.Container
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class EpicSdkUpdateDependencyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeDll(name: String, major: Int, minor: Int, patch: Int, build: Int, padding: Int): File {
        val file = tempFolder.newFile(name)
        val info = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        info.putInt(0xFEEF04BD.toInt())
        info.putInt(0x00010000)
        info.putInt((major shl 16) or minor)
        info.putInt((patch shl 16) or build)
        info.putInt((major shl 16) or minor)
        info.putInt((patch shl 16) or build)
        file.writeBytes(ByteArray(padding) { 0x41 } + info.array() + ByteArray(64))
        return file
    }

    @Test
    fun appliesTo_onlyForEpic() {
        val container = mockk<Container>(relaxed = true)
        assertTrue(EpicSdkUpdateDependency.appliesTo(container, GameSource.EPIC, 1))
        assertFalse(EpicSdkUpdateDependency.appliesTo(container, GameSource.STEAM, 1))
        assertFalse(EpicSdkUpdateDependency.appliesTo(container, GameSource.GOG, 1))
    }

    @Test
    fun readProductVersion_parsesFixedFileInfo() {
        val dll = fakeDll("a.dll", 1, 12, 0, 0, padding = 100)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 12, 0, 0), EpicSdkUpdateDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_findsSignatureAcrossChunkBoundary() {
        val dll = fakeDll("b.dll", 1, 19, 2, 1, padding = (1 shl 20) - 2)
        assertEquals(EpicSdkUpdateDependency.SdkVersion(1, 19, 2, 1), EpicSdkUpdateDependency.readProductVersion(dll))
    }

    @Test
    fun readProductVersion_returnsNullWithoutSignature() {
        val file = tempFolder.newFile("c.dll")
        file.writeBytes(ByteArray(4096) { 0x41 })
        assertNull(EpicSdkUpdateDependency.readProductVersion(file))
    }
}
