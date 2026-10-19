package app.gamenative.service.gog

import com.winlator.core.WineRegistryEditor
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GogGalaxyServiceTest {
    private lateinit var prefixDir: File
    private lateinit var systemReg: File

    @Before
    fun setUp() {
        prefixDir = createTempDirectory(prefix = "gog-galaxy-prefix").toFile()
        systemReg = File(prefixDir, "system.reg")
    }

    @After
    fun tearDown() {
        prefixDir.deleteRecursively()
    }

    @Test
    fun writeRegistry_addsServiceAndClientPathKeys() {
        systemReg.writeText(SYSTEM_REG_FIXTURE)

        assertTrue(GogGalaxyService.writeRegistry(systemReg))

        val text = systemReg.readText()
        assertTrue(text.contains("""[System\\CurrentControlSet\\Services\\GalaxyCommunication]"""))
        assertTrue(text.contains(""""ImagePath"=str(2):"C:\\ProgramData\\GOG.com\\Galaxy\\redists\\GalaxyCommunication.exe""""))
        assertTrue(text.contains(""""ObjectName"="LocalSystem""""))
        assertTrue(text.contains(""""Start"=dword:00000003"""))
        assertTrue(text.contains(""""ErrorControl"=dword:00000001"""))
        assertTrue(text.contains(""""Type"=dword:00000010"""))
        assertTrue(text.contains(""""PreshutdownTimeout"=dword:0002bf20"""))
        assertTrue(text.contains("""[Software\\Wow6432Node\\GOG.com\\GalaxyClient\\paths]"""))
        assertTrue(text.contains(""""client"="C:\\Program Files\\GOG Galaxy""""))
        assertTrue(text.contains(""""Start"=dword:00000002"""))

        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(GogGalaxyService.SERVICE_IMAGE_PATH, editor.getExpandStringValue(GogGalaxyService.SERVICE_KEY, "ImagePath"))
            assertEquals(GogGalaxyService.CLIENT_PATH, editor.getStringValue(GogGalaxyService.CLIENT_PATHS_KEY, "client"))
        }
    }

    @Test
    fun writeRegistry_isIdempotent() {
        systemReg.writeText(SYSTEM_REG_FIXTURE)
        assertTrue(GogGalaxyService.writeRegistry(systemReg))
        val first = systemReg.readText()

        assertFalse(GogGalaxyService.writeRegistry(systemReg))
        assertEquals(first, systemReg.readText())
    }

    @Test
    fun writeRegistry_repairsChangedValue() {
        systemReg.writeText(SYSTEM_REG_FIXTURE)
        assertTrue(GogGalaxyService.writeRegistry(systemReg))
        WineRegistryEditor(systemReg).use { it.setDwordValue(GogGalaxyService.SERVICE_KEY, "Start", 4) }

        assertTrue(GogGalaxyService.writeRegistry(systemReg))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(3, editor.getDwordValue(GogGalaxyService.SERVICE_KEY, "Start"))
        }
    }

    @Test
    fun writeRegistry_skipsMissingSystemReg() {
        assertFalse(GogGalaxyService.writeRegistry(systemReg))
        assertFalse(systemReg.exists())
    }

    @Test
    fun buildCommand_passesCredentialsAndEnvironment() {
        val home = File("/data/files/comet")
        val tmp = File("/data/cache/comet-tmp")
        val command = GogGalaxyService.buildCommand(
            binary = File("/data/lib/libcomet.so"),
            accessToken = "access",
            refreshToken = "refresh",
            userId = "1234",
            username = "player",
            homeDir = home,
            tmpDir = tmp,
        )

        assertEquals(listOf("/data/lib/libcomet.so", "--username", "player"), command.args)
        assertEquals(
            mapOf(
                "COMET_ACCESS_TOKEN" to "access",
                "COMET_REFRESH_TOKEN" to "refresh",
                "COMET_USER_ID" to "1234",
                "HOME" to "/data/files/comet",
                "XDG_DATA_HOME" to "/data/files/comet/.local/share",
                "XDG_CONFIG_HOME" to "/data/files/comet/.config",
                "TMPDIR" to "/data/cache/comet-tmp",
                "COMET_LOG" to "info",
            ),
            command.env,
        )
    }

    private companion object {
        val SYSTEM_REG_FIXTURE = """
            WINE REGISTRY Version 2
            ;; All keys relative to \\Machine

            #arch=win64

            [System\\CurrentControlSet\\Services\\Afd] 1700000000
            #time=1d9a0b0c0d0e0f0
            "ImagePath"=str(2):"C:\\windows\\system32\\drivers\\afd.sys"
            "Start"=dword:00000002
            "Type"=dword:00000001

            [Software\\Wow6432Node\\Microsoft] 1700000000
            #time=1d9a0b0c0d0e0f0

        """.trimIndent()
    }
}
