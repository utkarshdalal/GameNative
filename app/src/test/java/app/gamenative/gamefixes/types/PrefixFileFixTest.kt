package app.gamenative.gamefixes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefixFileFixTest {

    private lateinit var context: Context
    private lateinit var tempDir: File
    private lateinit var container: Container

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempDir = Files.createTempDirectory("prefix_file_fix_test_").toFile()
        container = mockk(relaxed = true)
        every { container.rootDir } returns tempDir
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun apply_createsMissingDirectoriesAndWritesContent() {
        val fix = PrefixFileFix(
            driveCRelativePath = "users/xuser/AppData/Local/Subnautica2/Saved/Config/Windows/Engine.ini",
            content = "[Section]\nKey=Value\n",
        )

        val changed = fix.apply(
            context = context,
            gameId = "1962700",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertTrue(changed)
        val target = File(
            tempDir,
            ".wine/drive_c/users/xuser/AppData/Local/Subnautica2/Saved/Config/Windows/Engine.ini",
        )
        assertEquals("[Section]\nKey=Value\n", target.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun apply_returnsFalse_whenContentAlreadyMatches() {
        val relativePath = "users/xuser/AppData/Local/Game/Engine.ini"
        val content = "[Section]\nKey=Value\n"
        val target = File(tempDir, ".wine/drive_c/$relativePath")
        target.parentFile?.mkdirs()
        target.writeText(content, StandardCharsets.UTF_8)

        val fix = PrefixFileFix(driveCRelativePath = relativePath, content = content)

        val changed = fix.apply(
            context = context,
            gameId = "1962700",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertFalse(changed)
    }

    @Test
    fun apply_overwritesDifferingContent() {
        val relativePath = "users/xuser/AppData/Local/Game/Engine.ini"
        val target = File(tempDir, ".wine/drive_c/$relativePath")
        target.parentFile?.mkdirs()
        target.writeText("old\n", StandardCharsets.UTF_8)

        val fix = PrefixFileFix(driveCRelativePath = relativePath, content = "new\n")

        val changed = fix.apply(
            context = context,
            gameId = "1962700",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertTrue(changed)
        assertEquals("new\n", target.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun steamFix1962700_writesEngineIniToSubnauticaConfigPath() {
        val changed = STEAM_Fix_1962700.apply(
            context = context,
            gameId = "1962700",
            installPath = tempDir.absolutePath,
            installPathWindows = "A:\\",
            container = container,
        )

        assertTrue(changed)
        val target = File(
            tempDir,
            ".wine/drive_c/users/${ImageFs.USER}/AppData/Local/Subnautica2/Saved/Config/Windows/Engine.ini",
        )
        val written = target.readText(StandardCharsets.UTF_8)
        assertTrue(written.contains("[/Script/Engine.RendererSettings]"))
        assertTrue(written.contains("r.Nanite=0"))
        assertTrue(written.contains("d3d12.MaxDescriptorHeaps=4096"))
    }
}
