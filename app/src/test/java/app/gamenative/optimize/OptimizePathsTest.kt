package app.gamenative.optimize

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OptimizePathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var roots: PathRoots
    private lateinit var prefix: File
    private lateinit var home: File
    private lateinit var install: File

    private fun file(path: File): File = path.also {
        it.parentFile?.mkdirs()
        it.writeText("x")
    }

    @Before
    fun setUp() {
        prefix = File(tmp.root, "xuser-1/.wine").also { it.mkdirs() }
        home = File(prefix, "drive_c/users/xuser").also { it.mkdirs() }
        install = File(tmp.root, "games/Witcher").also { it.mkdirs() }
        roots = PathRoots(install, prefix, home, steamAccountId = 42L)
    }

    @Test
    fun documentsPathMatchesSegmentsCaseInsensitively() {
        val settings = file(File(home, "Documents/The Witcher 3/user.settings"))
        assertEquals(settings.canonicalPath, OptimizePaths.resolve(roots, "{documentsPath}/the witcher 3/USER.settings")?.canonicalPath)
    }

    @Test
    fun appDataPlaceholdersAndBackslashes() {
        val local = file(File(home, "AppData/Local/Game/Saved/Config/Engine.ini"))
        assertEquals(local.canonicalPath, OptimizePaths.resolve(roots, "{localAppDataPath}\\Game\\Saved\\Config\\Engine.ini")?.canonicalPath)
        val low = file(File(home, "AppData/LocalLow/Studio/prefs.json"))
        assertEquals(low.canonicalPath, OptimizePaths.resolve(roots, "{localLowAppDataPath}/Studio/prefs.json")?.canonicalPath)
    }

    @Test
    fun missingFileStillResolvesForCreation() {
        val target = OptimizePaths.resolve(roots, "{roamingAppDataPath}/Game/new.ini")
        assertEquals(File(home, "AppData/Roaming/Game/new.ini").canonicalPath, target?.canonicalPath)
    }

    @Test
    fun starPicksTheNewestSingleFolder() {
        val old = file(File(home, "Documents/Game/111/settings.ini"))
        val new = file(File(home, "Documents/Game/222/settings.ini"))
        old.parentFile!!.setLastModified(1_000_000L)
        new.parentFile!!.setLastModified(2_000_000L)
        assertEquals(new.canonicalPath, OptimizePaths.resolve(roots, "{documentsPath}/Game/*/settings.ini")?.canonicalPath)
    }

    @Test
    fun starSkipsNewerFoldersWithoutTheRestOfThePath() {
        val engine = file(File(home, "AppData/Local/LunaGame/Saved/Config/Windows/Engine.ini"))
        File(home, "AppData/Local/LunaGame").setLastModified(1_000_000L)
        File(home, "AppData/Local/Temp/junk").mkdirs()
        File(home, "AppData/Local/Temp").setLastModified(9_000_000L)
        assertEquals(engine.canonicalPath, OptimizePaths.resolve(roots, "{localAppDataPath}/*/Saved/Config/Windows/Engine.ini")?.canonicalPath)
        assertEquals(engine.canonicalPath, OptimizePaths.resolve(roots, "{localAppDataPath}/*/saved/config/windows/engine.ini")?.canonicalPath)
    }

    @Test
    fun twoStarsPickTheFolderPairThatHoldsTheFile() {
        val local = File(home, "AppData/Local")
        val noEditor = file(File(local, "LunaGame/Saved/Config/WindowsNoEditor/Engine.ini"))
        File(local, "LunaGame/Saved/Config/Windows").mkdirs()
        File(local, "LunaGame/Saved/Config/Windows").setLastModified(9_000_000L)
        noEditor.parentFile!!.setLastModified(1_000_000L)
        File(local, "Temp/Saved/Config/Windows").mkdirs()
        File(local, "Temp").setLastModified(9_000_000L)
        File(local, "LunaGame").setLastModified(1_000_000L)
        assertEquals(noEditor.canonicalPath, OptimizePaths.resolve(roots, "{localAppDataPath}/*/Saved/Config/*/Engine.ini")?.canonicalPath)

        val windows = file(File(local, "LunaGame/Saved/Config/Windows/Engine.ini"))
        File(local, "LunaGame/Saved/Config/Windows").setLastModified(9_000_000L)
        assertEquals(windows.canonicalPath, OptimizePaths.resolve(roots, "{localAppDataPath}/*/Saved/Config/*/Engine.ini")?.canonicalPath)
    }

    @Test
    fun starWithNoMatchResolvesToNullUnlessItMayCreateNextToAFolder() {
        val local = File(home, "AppData/Local")
        File(local, "Temp").mkdirs()
        File(local, "LunaGame/Saved/Config/Windows").mkdirs()
        File(local, "Temp").setLastModified(9_000_000L)
        File(local, "LunaGame").setLastModified(1_000_000L)
        val template = "{localAppDataPath}/*/Saved/Config/Windows/GameUserSettings.ini"
        assertNull(OptimizePaths.resolve(roots, template))
        assertEquals(
            File(local, "LunaGame/Saved/Config/Windows/GameUserSettings.ini").canonicalPath,
            OptimizePaths.resolve(roots, template, createIfMissing = true)?.canonicalPath,
        )
        assertNull(OptimizePaths.resolve(roots, "{localAppDataPath}/*/Nowhere/x.ini", createIfMissing = true))
    }

    @Test
    fun doubleStarPicksTheNewestMatchingFile() {
        val a = file(File(install, "a/b/config.cfg")).also { it.setLastModified(1_000_000L) }
        val b = file(File(install, "c/config.cfg")).also { it.setLastModified(3_000_000L) }
        assertEquals(b.canonicalPath, OptimizePaths.resolve(roots, "{gameInstallPath}/**/config.cfg")?.canonicalPath)
        a.setLastModified(5_000_000L)
        assertEquals(a.canonicalPath, OptimizePaths.resolve(roots, "{gameInstallPath}/**/config.cfg")?.canonicalPath)
    }

    @Test
    fun exeDirFindsTheFolderHoldingTheExe() {
        file(File(install, "bin/x64/witcher3.exe"))
        val ini = file(File(install, "bin/x64/dx12.ini"))
        assertEquals(ini.canonicalPath, OptimizePaths.resolve(roots, "{exeDir:witcher3.exe}/dx12.ini")?.canonicalPath)
    }

    @Test
    fun steamUserdataNeedsTheAccount() {
        val cfg = file(File(prefix, "drive_c/Program Files (x86)/Steam/userdata/42/123/remote/cfg.ini"))
        assertEquals(cfg.canonicalPath, OptimizePaths.resolve(roots, "{steamUserdataPath}/123/remote/cfg.ini")?.canonicalPath)
        assertNull(OptimizePaths.resolve(PathRoots(install, prefix, home, null), "{steamUserdataPath}/123/remote/cfg.ini"))
    }

    @Test
    fun systemPlaceholdersAndUserName() {
        assertEquals(
            File(prefix, "drive_c/ProgramData/Game/a.ini").canonicalPath,
            OptimizePaths.resolve(roots, "{programDataPath}/Game/a.ini")?.canonicalPath,
        )
        val f = file(File(home, "x.cfg"))
        assertEquals(f.canonicalPath, OptimizePaths.resolve(roots, "{systemDrive}/users/{userName}/x.cfg")?.canonicalPath)
    }

    @Test
    fun unknownPlaceholdersAndEscapesAreRefused() {
        assertNull(OptimizePaths.resolve(roots, "{runtimeFile:foo}/a.ini"))
        assertNull(OptimizePaths.resolve(roots, "{runtimeDir}/a.ini"))
        assertNull(OptimizePaths.resolve(roots, "{referenceFiles}"))
        assertNull(OptimizePaths.resolve(roots, "relative/a.ini"))
        assertNull(OptimizePaths.resolve(roots, "{documentsPath}/../../../../../etc/passwd"))
        assertNull(OptimizePaths.resolve(roots, "{documentsPath}/{runtimeDir}/a.ini"))
    }
}
