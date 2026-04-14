package app.gamenative.gamefixes

import androidx.test.core.app.ApplicationProvider
import com.winlator.container.Container
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WineEnvVarFixTest {
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = Files.createTempDirectory("wine-env-fix-tests").toFile()
        baseDir.deleteOnExit()
    }

    @Test
    fun apply_addsMissingEnvVarAndSavesContainer_whenEnvVarIsMissing() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = createContainer("c1", "WINEESYNC=1")
        val configFile = container.configFile
        if (configFile.exists()) {
            configFile.delete()
        }

        val fix = WineEnvVarFix(
            envVarsToSet = mapOf("WINEDLLOVERRIDES" to "icu=n"),
        )

        val result = fix.apply(
            context = context,
            gameId = "413150",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertTrue(result)
        assertTrue(container.envVars.contains("WINEESYNC=1"))
        assertTrue(container.envVars.contains("WINEDLLOVERRIDES=icu=n"))
        assertTrue(configFile.exists())
    }

    @Test
    fun apply_keepsExistingEnvVarAndDoesNotSave_whenEnvVarAlreadyExists() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = createContainer("c2", "WINEESYNC=1 WINEDLLOVERRIDES=quartz=n")
        val configFile = container.configFile
        if (configFile.exists()) {
            configFile.delete()
        }

        val fix = WineEnvVarFix(
            envVarsToSet = mapOf("WINEDLLOVERRIDES" to "icu=n"),
        )

        val result = fix.apply(
            context = context,
            gameId = "413150",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertTrue(result)
        assertTrue(container.envVars.contains("WINEDLLOVERRIDES=quartz=n"))
        assertTrue(!container.envVars.contains("WINEDLLOVERRIDES=icu=n"))
        assertTrue(!configFile.exists())
    }

    @Test
    fun apply_addsOnlyMissingVars_whenMultipleDefaultsProvided() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val container = createContainer("c3", "WINEESYNC=1")
        val configFile = container.configFile
        if (configFile.exists()) {
            configFile.delete()
        }

        val fix = WineEnvVarFix(
            envVarsToSet = linkedMapOf(
                "WINEDLLOVERRIDES" to "icu=n",
                "WINEESYNC" to "0",
            ),
        )

        val result = fix.apply(
            context = context,
            gameId = "413150",
            installPath = "",
            installPathWindows = "",
            container = container,
        )

        assertTrue(result)
        assertTrue(container.envVars.contains("WINEESYNC=1"))
        assertTrue(container.envVars.contains("WINEDLLOVERRIDES=icu=n"))
        assertTrue(configFile.exists())
    }

    private fun createContainer(id: String, envVars: String): Container {
        val rootDir = File(baseDir, id).apply { mkdirs() }
        return Container(id).apply {
            this.rootDir = rootDir
            this.envVars = envVars
        }
    }
}
