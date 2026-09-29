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
        File(game, "doorstop_config.ini").writeText("test")
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
        File(game, "doorstop_config.ini").writeText("test")
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

    @Test fun previewResolvesSteamDefaultWithoutSavingAnExecutableChoice() {
        container.executablePath = ""
        container.saveData()
        val saved = container.configFile.readText()
        for (appId in listOf("STEAM_480", "STEAM_480(1)")) {
            val path = ModDllOverrideLauncher.resolvePreviewExecutable(container.executablePath, appId) {
                assertEquals(480, it)
                "game.exe"
            }
            val inspection = ModDllOverrides.inspect(path, ModDllOverrideLauncher.drives(container.drives))
            assertEquals(listOf("dinput8"), inspection.detected)
            assertEquals("icu=n;dinput8=n,b", inspection.merge(EnvVars(container.envVars).get("WINEDLLOVERRIDES")).value)
        }
        assertEquals("", container.executablePath)
        assertEquals(saved, container.configFile.readText())
    }

    @Test fun previewNeverReplacesExplicitExecutablePaths() {
        for (path in listOf("game.exe", "C:\\Games\\Other.exe", "launch.bat", " ")) {
            val resolved = ModDllOverrideLauncher.resolvePreviewExecutable(path, "STEAM_480") {
                error("An explicit executable must not trigger a Steam lookup")
            }
            assertEquals(path, resolved)
        }
    }

    @Test fun previewDoesNotGuessSteamForOtherContainerSources() {
        for (appId in listOf(null, "", "480", "CUSTOM_GAME_480", "GOG_480", "EPIC_480", "AMAZON_480", "UNKNOWN_480")) {
            val resolved = ModDllOverrideLauncher.resolvePreviewExecutable("", appId) {
                error("A non-Steam container must not trigger a Steam lookup")
            }
            assertEquals("", resolved)
        }
    }

    @Test fun previewWithoutSteamMetadataLeavesOverridesUnchanged() {
        val path = ModDllOverrideLauncher.resolvePreviewExecutable("", "STEAM_480") { "" }
        val inspection = ModDllOverrides.inspect(path, ModDllOverrideLauncher.drives(container.drives))
        assertNull(inspection.executable)
        assertTrue(inspection.detected.isEmpty())
        val result = inspection.merge("icu=n")
        assertEquals("icu=n", result.value)
        assertTrue(result.added.isEmpty())
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

    @Test fun retiredToggleDoesNotDisableAutomaticOverridesInExistingContainers() {
        container.putExtra("autoModDllOverrides", "false")
        container.saveData()
        val reloaded = Container(container.id).apply {
            setRootDir(container.rootDir)
            loadData(JSONObject(container.configFile.readText()))
        }
        val env = EnvVars(reloaded.envVars)
        ModDllOverrideLauncher.apply(reloaded, env, true)
        assertEquals("icu=n;dinput8=n,b", env.get("WINEDLLOVERRIDES"))
        assertEquals("WINEDLLOVERRIDES=icu=n", reloaded.envVars)
    }

    @Test fun removingLoaderEvidenceStopsAutomaticOverridesOnNextLaunch() {
        val first = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, first, true)
        assertEquals("icu=n;dinput8=n,b", first.get("WINEDLLOVERRIDES"))

        assertTrue(File(dll.parentFile, "doorstop_config.ini").delete())
        val second = EnvVars(container.envVars)
        ModDllOverrideLauncher.apply(container, second, true)
        assertEquals("icu=n", second.get("WINEDLLOVERRIDES"))
    }
}
