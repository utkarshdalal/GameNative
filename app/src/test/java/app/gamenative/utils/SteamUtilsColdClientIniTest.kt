package app.gamenative.utils

import app.gamenative.data.LaunchInfo
import app.gamenative.enums.OS
import app.gamenative.enums.OSArch
import app.gamenative.workshop.compatibility.SlayTheSpireModTheSpireCompatibility
import java.io.File
import java.util.EnumSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamUtilsColdClientIniTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun generate(
        gameName: String = "Batman Arkham Asylum GOTY",
        executablePath: String = "Binaries\\BmLauncher.exe",
        exeCommandLine: String = "",
        steamAppId: Int = 35140,
        workingDir: String? = null,
        isUnpackFiles: Boolean = false,
    ) = SteamUtils.generateColdClientIni(
        gameName = gameName,
        executablePath = executablePath,
        exeCommandLine = exeCommandLine,
        steamAppId = steamAppId,
        workingDir = workingDir,
        isUnpackFiles = isUnpackFiles,
    )

    private fun String.iniValue(key: String): String =
        lines().first { it.startsWith("$key=") }.removePrefix("$key=")

    @Test
    fun `ExeRunDir is exe directory when no workingDir`() {
        val ini = generate(gameName = "New Star GP", executablePath = "release/NSGP.exe", workingDir = null)
        assertEquals("steamapps\\common\\New Star GP\\release", ini.iniValue("ExeRunDir"))
    }

    @Test
    fun `ExeRunDir is blank when workingDir is set`() {
        // workingDir set: leave blank (legacy behaviour, same as master)
        val ini = generate(workingDir = "Binaries")
        assertEquals("", ini.iniValue("ExeRunDir"))
    }

    @Test
    fun `ExeRunDir is exe directory when workingDir is empty string`() {
        val ini = generate(gameName = "New Star GP", executablePath = "release/NSGP.exe", workingDir = "")
        assertEquals("steamapps\\common\\New Star GP\\release", ini.iniValue("ExeRunDir"))
    }

    private fun launchInfo(executable: String, arguments: String) = LaunchInfo(
        executable = executable,
        workingDir = "",
        description = "",
        type = "default",
        configOS = EnumSet.of(OS.windows),
        configArch = OSArch.Arch64,
        arguments = arguments,
    )

    private fun resolve(
        executablePath: String,
        userArgs: String = "",
        steamAppId: Int = 1150690,
        gameRootDir: File = tmpDir.newFolder(),
    ) = SteamUtils.resolveColdClientLaunchConfig(
        steamAppId = steamAppId,
        executablePath = executablePath,
        exeCommandLine = userArgs,
        gameRootDir = gameRootDir,
    )

    @Test
    fun `launch config arguments are used when the exe is the config exe`() {
        val info = launchInfo("OMORI.exe", " --6bdb2e585882fbd48826ef9cffd4c511 ")
        assertEquals("--6bdb2e585882fbd48826ef9cffd4c511", SteamUtils.launchConfigArguments(info, "OMORI.exe"))
    }

    @Test
    fun `exe comparison ignores slashes and case`() {
        val info = launchInfo("\\bin\\win64\\game.EXE", "-config")
        assertEquals("-config", SteamUtils.launchConfigArguments(info, "Bin/Win64/Game.exe"))
    }

    @Test
    fun `launch config arguments are dropped for a different exe`() {
        val info = launchInfo("OMORI.exe", "--6bdb2e585882fbd48826ef9cffd4c511")
        assertEquals("", SteamUtils.launchConfigArguments(info, "Other.exe"))
    }

    @Test
    fun `no launch config arguments without launch info or a config exe`() {
        assertEquals("", SteamUtils.launchConfigArguments(null, "OMORI.exe"))
        assertEquals("", SteamUtils.launchConfigArguments(launchInfo("OMORI.exe", ""), "OMORI.exe"))
        assertEquals("", SteamUtils.launchConfigArguments(launchInfo("", "-x"), "OMORI.exe"))
    }

    @Test
    fun `the container's arguments are the command line`() {
        val config = resolve(executablePath = "OMORI.exe", userArgs = " --6bdb2e585882fbd48826ef9cffd4c511 -windowed ")
        assertEquals("--6bdb2e585882fbd48826ef9cffd4c511 -windowed", config.exeCommandLine)
        assertEquals("", resolve(executablePath = "OMORI.exe").exeCommandLine)
    }

    @Test
    fun `Slay the Spire without ModTheSpire keeps the plain launch`() {
        val config = resolve(
            executablePath = "SlayTheSpire.exe",
            userArgs = "-config -windowed",
            steamAppId = SlayTheSpireModTheSpireCompatibility.APP_ID,
        )
        assertEquals("SlayTheSpire.exe", config.executablePath)
        assertEquals("-config -windowed", config.exeCommandLine)
    }

    @Test
    fun `Slay the Spire ModTheSpire launch appends the combined command line`() {
        val gameRoot = tmpDir.newFolder("SlayTheSpire")
        File(gameRoot, "jre/bin").mkdirs()
        File(gameRoot, "jre/bin/java.exe").writeText("")
        File(gameRoot, SlayTheSpireModTheSpireCompatibility.HEADLESS_LAUNCHER_JAR).writeText("")
        File(gameRoot, "ModTheSpire.jar").writeText("")
        val modsDir = File(gameRoot, "mods").apply { mkdirs() }
        ZipOutputStream(File(modsDir, "basemod.jar").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("ModTheSpire.json"))
            zip.write("""{"modid":"basemod"}""".toByteArray())
            zip.closeEntry()
        }
        File(modsDir, ".gamenative_workshop_jars").writeText("basemod.jar\n")

        val config = resolve(
            executablePath = "SlayTheSpire.exe",
            userArgs = "-config -windowed",
            steamAppId = SlayTheSpireModTheSpireCompatibility.APP_ID,
            gameRootDir = gameRoot,
        )
        assertEquals("jre/bin/java.exe", config.executablePath)
        assertTrue(config.exeCommandLine, config.exeCommandLine.contains("--mods basemod"))
        assertTrue(config.exeCommandLine, config.exeCommandLine.endsWith(" -config -windowed"))
    }
}
