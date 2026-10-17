package app.gamenative.gamefixes

import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StardewValleyGameFixTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("stardew-game-fix-tests").toFile()
        baseDir.deleteOnExit()
    }

    @Test
    fun apply_addsIcuOverrideToBionicContainer() {
        val container = createContainer("bionic", Container.BIONIC, "WINEESYNC=1")

        assertTrue(applyFix(container))
        assertTrue(container.envVars.contains("WINEDLLOVERRIDES=icu=n"))
    }

    @Test
    fun apply_removesPreviouslyAddedIcuOverrideFromGlibcContainer() {
        val container = createContainer("glibc", "glibc", "WINEESYNC=1 WINEDLLOVERRIDES=icu=n")

        assertTrue(applyFix(container))
        assertTrue(container.envVars.contains("WINEESYNC=1"))
        assertFalse(container.envVars.contains("WINEDLLOVERRIDES"))
    }

    @Test
    fun apply_preservesUserDllOverridesOnGlibcContainer() {
        val container = createContainer("custom", "glibc", "WINEDLLOVERRIDES=quartz=n")

        assertTrue(applyFix(container))
        assertTrue(container.envVars.contains("WINEDLLOVERRIDES=quartz=n"))
    }

    private fun applyFix(container: Container): Boolean = STEAM_Fix_413150.apply(
        context = ApplicationProvider.getApplicationContext(),
        gameId = "413150",
        installPath = "",
        installPathWindows = "",
        container = container,
    )

    private fun createContainer(id: String, variant: String, envVars: String): Container {
        val rootDir = File(baseDir, id).apply { mkdirs() }
        return Container(id).apply {
            this.rootDir = rootDir
            this.containerVariant = variant
            this.envVars = envVars
        }
    }
}
