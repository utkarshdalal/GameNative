package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PsPcSdkRuntimeStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var prefixDir: File

    @Before
    fun setUp() {
        gameDir = createTempDirectory(prefix = "pspc-step-test").toFile()
        prefixDir = createTempDirectory(prefix = "pspc-prefix-test").toFile()
        container = mockk(relaxed = true)
        every { container.rootDir } returns prefixDir
    }

    private fun build(): String? = PsPcSdkRuntimeStep.buildCommand(
        container = container,
        appId = "STEAM_3489700",
        gameSource = GameSource.STEAM,
        gameDir = gameDir,
        gameDirPath = gameDir.absolutePath,
    )

    private fun script(relative: String): File =
        File(gameDir, relative).apply {
            parentFile?.mkdirs()
            writeText("@echo off\r\nmsiexec /i \"%~dp0PsPcSdkRuntimeInstaller.msi\" /qn\r\n")
        }

    @Test
    fun appliesTo_isFalse_whenMarkerAndPrefixStampBothExist() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)
        File(prefixDir, ".wine").mkdirs()
        File(prefixDir, ".wine/${Marker.PSPC_SDK_RUNTIME_INSTALLED.fileName}").writeText("")

        assertFalse(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun appliesTo_isTrue_whenARecreatedPrefixLostTheStamp() {
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PSPC_SDK_RUNTIME_INSTALLED)

        assertTrue(PsPcSdkRuntimeStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNull_whenTheGameShipsNoScript() {
        assertNull(build())
    }

    @Test
    fun buildCommand_runsTheShippingSdkScriptAtItsKnownPath() {
        script("SB/Binaries/Win64/install_pspc_sdk_runtime.bat")

        // The shipped script's own quiet switch, not a bare invocation: without it
        // both MSIs open a UI and the script ends on a `pause`.
        assertEquals("A:\\SB\\Binaries\\Win64\\install_pspc_sdk_runtime.bat quiet < nul", build())
    }

    @Test
    fun buildCommand_findsTheScriptThreeDirectoriesDown() {
        // <game>/a/b/c/script — the shape of <game>/SB/Binaries/Win64/script.
        script("a/b/c/install_pspc_sdk_runtime.bat")

        val cmd = build()
        assertNotNull(cmd)
        assertTrue(cmd!!, cmd.startsWith("A:\\a\\b\\c\\install_pspc_sdk_runtime.bat quiet"))
    }

    @Test
    fun buildCommand_ignoresTheScriptFourDirectoriesDown() {
        script("a/b/c/d/install_pspc_sdk_runtime.bat")

        assertNull(build())
    }

    @Test
    fun buildCommand_refusesAPathThatNeedsQuoting() {
        script("SB with space/Binaries/Win64/install_pspc_sdk_runtime.bat")

        // The framework wraps the command in `cmd /c "…"`, so a space cannot be
        // escaped here; refusing beats running half a command line.
        assertNull(build())
    }
}
