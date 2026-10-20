package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class OpenALRedistTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private val envVarsSlot = slot<String>()

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "openal-redist-test").toFile()
        every { container.drives } returns "A:${gameDir.absolutePath}"
        every { container.envVars = capture(envVarsSlot) } returns Unit
    }

    @After
    fun tearDown() {
        gameDir.deleteRecursively()
    }

    private fun touch(path: String) {
        val file = File(gameDir, path)
        file.parentFile?.mkdirs()
        file.writeText("x")
    }

    @Test
    fun hasInstaller_detectsOalinstInCommonRedist() {
        touch("_CommonRedist/OpenAL/2.0.7.0/oalinst.exe")
        assertTrue(OpenALRedist.hasInstaller(gameDir))
    }

    @Test
    fun hasInstaller_detectsOpenAlNamedExeInRedist() {
        touch("redist/OpenAL-Installer.exe")
        assertTrue(OpenALRedist.hasInstaller(gameDir))
    }

    @Test
    fun hasInstaller_ignoresUnrelatedFiles() {
        touch("_CommonRedist/OpenAL/readme.txt")
        touch("redist/vcredist_x86.exe")
        touch("oalinst.exe")
        assertFalse(OpenALRedist.hasInstaller(gameDir))
    }

    @Test
    fun mergeDllOverride_addsOverrideWhenMissing() {
        assertEquals(OpenALRedist.DLL_OVERRIDE, OpenALRedist.mergeDllOverride(""))
        assertEquals("dinput8=n,b;${OpenALRedist.DLL_OVERRIDE}", OpenALRedist.mergeDllOverride("dinput8=n,b"))
    }

    @Test
    fun mergeDllOverride_keepsExistingOpenAlOverride() {
        assertEquals("openal32=builtin", OpenALRedist.mergeDllOverride("openal32=builtin"))
        assertEquals("soft_oal=native", OpenALRedist.mergeDllOverride("soft_oal=native"))
    }

    @Test
    fun applyForLaunch_addsOverrideAndKeepsOtherEnvVars() {
        touch("_CommonRedist/OpenAL/oalinst.exe")
        every { container.envVars } returns "WINEESYNC=1 WINEDLLOVERRIDES=dinput8=n,b"

        OpenALRedist.applyForLaunch(container)

        val written = EnvVars(envVarsSlot.captured)
        assertEquals("1", written.get("WINEESYNC"))
        assertEquals("dinput8=n,b;${OpenALRedist.DLL_OVERRIDE}", written.get("WINEDLLOVERRIDES"))
        verify { container.saveData() }
    }

    @Test
    fun applyForLaunch_leavesContainerAloneWithoutInstaller() {
        every { container.envVars } returns "WINEESYNC=1"

        OpenALRedist.applyForLaunch(container)

        assertFalse(envVarsSlot.isCaptured)
        verify(exactly = 0) { container.saveData() }
    }

    @Test
    fun applyForLaunch_leavesExistingOverrideAlone() {
        touch("_CommonRedist/OpenAL/oalinst.exe")
        every { container.envVars } returns "WINEDLLOVERRIDES=openal32=native,builtin"

        OpenALRedist.applyForLaunch(container)

        assertFalse(envVarsSlot.isCaptured)
        verify(exactly = 0) { container.saveData() }
    }
}
