package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.gog.GOGBonusDlcInstaller
import com.winlator.container.Container
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class GogBonusInstallerStepTest {
    private lateinit var container: Container
    private lateinit var gameDir: File
    private lateinit var dlcDir: File

    @Before
    fun setUp() {
        container = mockk(relaxed = true)
        gameDir = createTempDirectory(prefix = "gog-bonus-step-test").toFile()
        dlcDir = File(File(gameDir, GOGBonusDlcInstaller.BONUS_DIR), "123").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        File(dlcDir, "rootdir").delete()
        gameDir.deleteRecursively()
    }

    @Test
    fun appliesTo_requiresGogAndPendingInstallerMarker() {
        assertFalse(GogBonusInstallerStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))

        File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE).writeText(File(dlcDir, "setup_dlc.exe").absolutePath)
        assertTrue(GogBonusInstallerStep.appliesTo(container, GameSource.GOG, gameDir.absolutePath))
        assertFalse(GogBonusInstallerStep.appliesTo(container, GameSource.STEAM, gameDir.absolutePath))
    }

    @Test
    fun buildCommand_returnsNullAndDropsMarker_whenInstallerExeMissing() {
        val marker = File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE)
        marker.writeText(File(dlcDir, "setup_dlc.exe").absolutePath)

        val cmd = GogBonusInstallerStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertNull(cmd)
        assertFalse(marker.exists())
    }

    @Test
    fun buildCommand_retriesInstaller_afterFailedRun() {
        File(dlcDir, "setup_dlc.exe").writeText("exe")
        File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE).writeText(File(dlcDir, "setup_dlc.exe").absolutePath)
        val failed = File(dlcDir, GOGBonusDlcInstaller.INSTALLER_FAILED_FILE).apply { writeText("failed") }
        Files.createSymbolicLink(File(dlcDir, "rootdir").toPath(), gameDir.toPath())

        val cmd = GogBonusInstallerStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val base = "A:\\_gog_bonus\\123"
        assertEquals(
            "$base\\setup_dlc.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /NOCANCEL /DIR=$base\\rootdir" +
                " && echo done> $base\\setup_dlc.exe.installed" +
                " && del /Q $base\\pending_installer.txt || echo failed> $base\\installer_failed.txt",
            cmd,
        )
        assertFalse(failed.exists())
    }

    @Test
    fun buildCommand_runsEveryPendingInstallerBeforeDroppingMarker() {
        File(dlcDir, "setup_a.exe").writeText("exe")
        File(dlcDir, "setup_b.exe").writeText("exe")
        File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE).writeText(
            listOf(File(dlcDir, "setup_a.exe"), File(dlcDir, "setup_b.exe")).joinToString("\n") { it.absolutePath },
        )
        Files.createSymbolicLink(File(dlcDir, "rootdir").toPath(), gameDir.toPath())

        val cmd = GogBonusInstallerStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val base = "A:\\_gog_bonus\\123"
        val args = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /NOCANCEL /DIR=$base\\rootdir"
        assertEquals(
            "$base\\setup_a.exe $args && echo done> $base\\setup_a.exe.installed" +
                " && $base\\setup_b.exe $args && echo done> $base\\setup_b.exe.installed" +
                " && del /Q $base\\pending_installer.txt || echo failed> $base\\installer_failed.txt",
            cmd,
        )
    }

    @Test
    fun buildCommand_skipsInstallersThatSucceededOnAnEarlierRun() {
        File(dlcDir, "setup_a.exe").writeText("exe")
        File(dlcDir, "setup_b.exe").writeText("exe")
        File(dlcDir, "setup_a.exe.installed").writeText("done")
        File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE).writeText(
            listOf(File(dlcDir, "setup_a.exe"), File(dlcDir, "setup_b.exe")).joinToString("\n") { it.absolutePath },
        )
        Files.createSymbolicLink(File(dlcDir, "rootdir").toPath(), gameDir.toPath())

        val cmd = GogBonusInstallerStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        val base = "A:\\_gog_bonus\\123"
        val args = "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /NOCANCEL /DIR=$base\\rootdir"
        assertEquals(
            "$base\\setup_b.exe $args && echo done> $base\\setup_b.exe.installed" +
                " && del /Q $base\\pending_installer.txt || echo failed> $base\\installer_failed.txt",
            cmd,
        )
    }

    @Test
    fun buildCommand_keepsMarker_whenSomeInstallerExesAreMissing() {
        File(dlcDir, "setup_a.exe").writeText("exe")
        val marker = File(dlcDir, GOGBonusDlcInstaller.PENDING_INSTALLER_FILE)
        marker.writeText(
            listOf(File(dlcDir, "setup_a.exe"), File(dlcDir, "setup_b.exe")).joinToString("\n") { it.absolutePath },
        )

        val cmd = GogBonusInstallerStep.buildCommand(
            container = container,
            appId = "GOG_1",
            gameSource = GameSource.GOG,
            gameDir = gameDir,
            gameDirPath = gameDir.absolutePath,
        )

        assertNull(cmd)
        assertTrue(marker.exists())
    }
}
