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

    @Test fun editorPreviewMatchesLaunchWithPrefixPathsAndRegistryOverrides() {
        val prefix = File(container.rootDir, ".wine")
        val game = File(prefix, "drive_c/Games/Muck").apply { mkdirs() }
        File(game, "Muck.exe").writeText("test")
        File(game, "winhttp.dll").writeText("test")
        File(game, "dinput8.dll").writeText("test")
        File(prefix, "user.reg").writeText("[Software\\\\Wine\\\\DllOverrides]\n\"dinput8\"=\"builtin,native\"\n")
        container.executablePath = "C:\\Games\\Muck\\Muck.exe"

        val inspection = ModDllOverrides.inspect(container.executablePath, ModDllOverrideLauncher.drives(container.drives), prefix)
        val preview = inspection.merge(EnvVars(container.envVars).get("WINEDLLOVERRIDES"))
        assertEquals(listOf("dinput8", "winhttp"), inspection.detected)
        assertEquals(listOf("dinput8"), preview.preserved)
        assertEquals(listOf("winhttp"), preview.added)
        assertEquals("icu=n;winhttp=n,b", preview.value)

        val env = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, env, true)
        assertEquals(preview.value, env.get("WINEDLLOVERRIDES"))
        assertEquals("WINEDLLOVERRIDES=icu=n", container.envVars)
        assertFalse(container.configFile.exists())
    }

    @Test fun editorPreviewReflectsUnsavedManualOverridesAndModRemoval() {
        val prefix = File(container.rootDir, ".wine")
        val drives = ModDllOverrideLauncher.drives(container.drives)
        val inspection = ModDllOverrides.inspect(container.executablePath, drives, prefix)
        assertEquals("icu=n;dinput8=n,b", inspection.merge("icu=n").value)
        val manual = inspection.merge("icu=n;dinput8=b")
        assertEquals("icu=n;dinput8=b", manual.value)
        assertTrue(manual.added.isEmpty())
        assertEquals(listOf("dinput8"), manual.preserved)
        assertTrue(dll.delete())
        val refreshed = ModDllOverrides.inspect(container.executablePath, drives, prefix).merge("icu=n")
        assertEquals("icu=n", refreshed.value)
        assertTrue(refreshed.added.isEmpty())
    }

    @Test fun skipsDesktopAndSetupLaunches() {
        val env = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, env, false)
        assertEquals("icu=n", env.get("WINEDLLOVERRIDES"))
    }

    @Test fun steamClientLaunchesPreserveManualEnvironmentWithoutAutomaticEntries() {
        container.isLaunchRealSteam = true
        val steamTypes = listOf(
            Container.STEAM_TYPE_NORMAL,
            Container.STEAM_TYPE_LIGHT,
            Container.STEAM_TYPE_ULTRALIGHT,
            Container.STEAM_TYPE_HEADLESS,
        )
        for (steamType in steamTypes) {
            container.steamType = steamType
            val env = EnvVars("WINEDLLOVERRIDES=icu=n;winhttp=b")
            ModDllOverrideLauncher.apply(container, env, true)
            assertEquals(steamType, "icu=n;winhttp=b", env.get("WINEDLLOVERRIDES"))
            assertEquals("WINEDLLOVERRIDES=icu=n", container.envVars)
            assertFalse(container.configFile.exists())
        }
    }

    @Test fun bionicSteamLaunchesStillGetAutomaticOverrides() {
        container.isLaunchBionicSteam = true
        for (realSteam in listOf(false, true)) {
            container.isLaunchRealSteam = realSteam
            container.steamType = Container.STEAM_TYPE_HEADLESS
            val env = EnvVars(container.envVars)
            ModDllOverrideLauncher.apply(container, env, true)
            assertEquals("icu=n;dinput8=n,b", env.get("WINEDLLOVERRIDES"))
        }
    }

    @Test fun switchingFromSteamClientToDirectLaunchReenablesAutomaticOverrides() {
        container.isLaunchRealSteam = true
        val steamEnv = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, steamEnv, true)
        assertEquals("icu=n", steamEnv.get("WINEDLLOVERRIDES"))

        container.isLaunchRealSteam = false
        val gameEnv = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, gameEnv, true)
        assertEquals("icu=n;dinput8=n,b", gameEnv.get("WINEDLLOVERRIDES"))
        assertEquals("WINEDLLOVERRIDES=icu=n", container.envVars)
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
