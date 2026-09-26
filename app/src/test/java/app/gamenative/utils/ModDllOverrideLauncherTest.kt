package app.gamenative.utils

import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModDllOverrideLauncherTest {
    @get:Rule val temp = TemporaryFolder(File("."))
    private lateinit var container: Container
    private lateinit var dll: File

    @Before fun setUp() {
        val game = temp.newFolder("Game")
        File(game, "game.exe").writeText("test")
        dll = File(game, "dinput8.dll").apply { writeText("test") }
        container = Container("CUSTOM_GAME_1").apply {
            setRootDir(temp.newFolder("container"))
            // Relative host paths also work on Windows test hosts, where ':' separates drive entries.
            drives = "A:${game.canonicalFile.relativeTo(File(".").canonicalFile).path}"
            executablePath = "game.exe"
            envVars = "WINEDLLOVERRIDES=icu=n"
        }
    }

    @Test fun changesOnlyLaunchEnvironmentAndRecomputesAfterRemoval() {
        val first = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, first, true)
        assertEquals("icu=n;dinput8=n,b", first.get("WINEDLLOVERRIDES"))
        assertEquals("WINEDLLOVERRIDES=icu=n", container.envVars)
        assertFalse(container.configFile.exists())
        assertTrue(dll.delete())
        val second = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, second, true)
        assertEquals("icu=n", second.get("WINEDLLOVERRIDES"))
    }

    @Test fun preservesLaunchTimeOverridesFromOtherFeatures() {
        val env = EnvVars("WINEDLLOVERRIDES=icu=n;gameoverlayrenderer=n;dinput8=b")
        ModDllOverrideLauncher.apply(container, env, true)
        assertEquals("icu=n;gameoverlayrenderer=n;dinput8=b", env.get("WINEDLLOVERRIDES"))
    }

    @Test fun skipsDesktopAndSetupLaunches() {
        val env = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, env, false)
        assertEquals("icu=n", env.get("WINEDLLOVERRIDES"))
    }

    @Test fun respectsDisabledSettingAcrossContainerReload() {
        container.putExtra(ModDllOverrides.SETTING, "false")
        container.saveData()
        val reloaded = Container(container.id).apply {
            setRootDir(container.rootDir)
            loadData(JSONObject(container.configFile.readText()))
        }
        assertEquals("false", reloaded.getExtra(ModDllOverrides.SETTING))
        val env = EnvVars(reloaded.envVars)
        ModDllOverrideLauncher.apply(reloaded, env, true)
        assertEquals("icu=n", env.get("WINEDLLOVERRIDES"))
    }
}
