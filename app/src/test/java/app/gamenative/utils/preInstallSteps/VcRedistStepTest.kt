package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
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
class VcRedistStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "vcredist-step-test").toFile()
        rootDir = createTempDirectory(prefix = "vcredist-step-root").toFile()
        every { container.rootDir } returns rootDir
    }

    private fun build(): String? = VcRedistStep.buildCommand(
        container = container,
        appId = "STEAM_1",
        gameSource = GameSource.STEAM,
        gameDir = gameDir,
        gameDirPath = gameDir.absolutePath,
    )

    private fun addInstaller(relativePath: String) {
        val installer = File(gameDir, relativePath)
        installer.parentFile?.mkdirs()
        installer.writeText("dummy")
    }

    @Test
    fun appliesTo_returnsTrue_whenMarkerMissing() {
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertTrue(applies)
    }

    @Test
    fun appliesTo_returnsFalse_whenMarkerExists() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.VCREDIST_INSTALLED)
        val applies = VcRedistStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath)
        assertFalse(applies)
    }

    @Test
    fun buildCommand_returnsCommand_forDetectedInstaller() {
        val installer = File(gameDir, "_CommonRedist/MSVC2017/VC_redist.x86.exe")
        installer.parentFile?.mkdirs()
        installer.writeText("dummy")

        val cmd = VcRedistStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val expected = "A:\\_CommonRedist\\MSVC2017\\VC_redist.x86.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(cmd))
    }

    @Test
    fun buildCommand_queues2022Installer() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")

        val expected = "A:\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe /install /passive /norestart"
        assertEquals(expected, checkNotNull(build()))
    }

    @Test
    fun buildCommand_writesV140Overrides_forVcRedistInstaller() {
        addInstaller("_CommonRedist/vcredist/2022/VC_redist.x64.exe")

        build()

        val userReg = File(rootDir, ".wine/user.reg").readText()
        assertTrue(userReg.contains("\"ucrtbase\"=\"builtin\""))
        assertTrue(userReg.contains("\"msvcp140\"=\"native,builtin\""))
        assertTrue(userReg.contains("\"vcruntime140\"=\"native,builtin\""))
    }

    @Test
    fun buildCommand_skipsOverrides_forPreV140Installer() {
        addInstaller("_CommonRedist/vcredist/2013/vcredist_x86.exe")

        build()

        assertFalse(File(rootDir, ".wine/user.reg").exists())
    }
}
