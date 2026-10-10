package app.gamenative.ui.screen.xr.windows

import android.app.Application
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WindowsVrPayloadManagerTest {
    private lateinit var context: Application
    private lateinit var containerRoot: File
    private lateinit var gameRoot: File
    private lateinit var container: Container
    private lateinit var manager: WindowsVrPayloadManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        containerRoot = File(context.filesDir, "home/xuser-TEST").apply { mkdirs() }
        gameRoot = File(context.filesDir, "game").apply { mkdirs() }
        container = mockk(relaxed = true)
        every { container.rootDir } returns containerRoot
        every { container.drives } returns "A:${gameRoot.path}"
        manager = WindowsVrPayloadManager(context, WindowsVrDiagnostics(context)) { name ->
            "fake $name".toByteArray()
        }
    }

    @Test
    fun installOpenComposite_reusesCachedDirectoriesUntilTheyGoStale() {
        val first = File(gameRoot, "first").apply { mkdirs() }
        val firstDll = File(first, "openvr_api.dll").apply { writeBytes(fakePe(0x8664)) }
        val original = firstDll.readBytes()

        manager.installOpenComposite(container)
        assertFalse(firstDll.readBytes().contentEquals(original))
        manager.restore()
        assertArrayEquals(original, firstDll.readBytes())
        assertTrue(File(containerRoot, ".wine/drive_c/gamenative-xr/opencomposite.cache").isFile)

        val second = File(gameRoot, "second").apply { mkdirs() }
        val secondDll = File(second, "openvr_api.dll").apply { writeBytes(fakePe(0x14c)) }
        manager.installOpenComposite(container)
        assertFalse(firstDll.readBytes().contentEquals(original))
        assertArrayEquals(fakePe(0x14c), secondDll.readBytes())
        manager.restore()

        firstDll.delete()
        manager.installOpenComposite(container)
        assertFalse(secondDll.readBytes().contentEquals(fakePe(0x14c)))
        manager.restore()
        assertArrayEquals(fakePe(0x14c), secondDll.readBytes())
    }

    private fun fakePe(machine: Int): ByteArray {
        val bytes = ByteArray(0x100)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3c] = 0x40
        bytes[0x40] = 'P'.code.toByte()
        bytes[0x41] = 'E'.code.toByte()
        bytes[0x44] = (machine and 0xff).toByte()
        bytes[0x45] = (machine shr 8).toByte()
        return bytes
    }
}
