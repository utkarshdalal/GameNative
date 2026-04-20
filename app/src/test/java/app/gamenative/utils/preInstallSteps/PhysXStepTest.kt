package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.enums.Marker
import com.winlator.container.Container
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class PhysXStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "physx-step-test").toFile()
    }

    @Test
    fun appliesTo_respectsMarkerPresence() {
        assertTrue(PhysXStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
        MarkerUtils.addMarker(gameDir.absolutePath, Marker.PHYSX_INSTALLED)
        assertFalse(PhysXStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_detectsMsiAndExeInstallers() {
        val msi = File(gameDir, "_CommonRedist/PhysX/PhysX_legacy.msi")
        val exe = File(gameDir, "_CommonRedist/PhysX/PhysX_setup.exe")
        msi.parentFile?.mkdirs()
        exe.parentFile?.mkdirs()
        msi.writeText("x")
        exe.writeText("x")

        val cmd = PhysXStep.buildCommand(
            container = container,
            appId = "STEAM_1",
            gameSource = GameSource.STEAM,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertNotNull(cmd)
        val expectedParts = listOf(
            "msiexec /i A:\\_CommonRedist\\PhysX\\PhysX_legacy.msi /quiet /norestart",
            "A:\\_CommonRedist\\PhysX\\PhysX_setup.exe /quiet /norestart",
        ).sorted()
        val actualParts = cmd!!.split(" & ").sorted()
        assertEquals(expectedParts, actualParts)
    }
}
