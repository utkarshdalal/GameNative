package app.gamenative.utils.installscript

import com.winlator.xenvironment.ImageFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InstallScriptParserTest {

    private val installDir = "A:"
    private val user = ImageFs.USER

    // -----------------------------------------------------------------------
    // Registry tests
    // -----------------------------------------------------------------------

    @Test
    fun parseRegistry_parsesStringValues() {
        val vdf = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\Valve\\Half-Life"
                    {
                        "string"
                        {
                            "InstallPath"    "%INSTALLDIR%"
                            "Version"        "1.1.0.0"
                        }
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.registryActions.size)
        val action = result.registryActions[0]
        assertEquals("HKLM\\Software\\Valve\\Half-Life", action.keyPath)
        assertEquals(installDir, action.values.strings["InstallPath"])
        assertEquals("1.1.0.0", action.values.strings["Version"])
        assertTrue(action.values.dwords.isEmpty())
    }

    @Test
    fun parseRegistry_parsesDwordValues() {
        val vdf = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\Valve\\Half-Life"
                    {
                        "dword"
                        {
                            "PatchVersion"    "1"
                            "Installed"       "42"
                        }
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.registryActions.size)
        val action = result.registryActions[0]
        assertEquals(1L, action.values.dwords["PatchVersion"])
        assertEquals(42L, action.values.dwords["Installed"])
        assertTrue(action.values.strings.isEmpty())
    }

    @Test
    fun parseRegistry_parsesLanguageOverrides() {
        val vdf = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\Valve\\Half-Life"
                    {
                        "string"
                        {
                            "DisplayName"    "Half-Life"
                        }
                        "french"
                        {
                            "string"
                            {
                                "DisplayName"    "Half-Life FR"
                            }
                            "dword"
                            {
                                "LangId"    "12"
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.registryActions.size)
        val action = result.registryActions[0]
        assertEquals("Half-Life", action.values.strings["DisplayName"])
        val frenchOverride = action.languageOverrides["french"]
        assertEquals("Half-Life FR", frenchOverride?.strings?.get("DisplayName"))
        assertEquals(12L, frenchOverride?.dwords?.get("LangId"))
    }

    @Test
    fun parseRegistry_expandsEnvVars() {
        val vdf = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\MyGame"
                    {
                        "string"
                        {
                            "InstallPath"    "%INSTALLDIR%"
                            "AppData"        "%APPDATA%\\MyGame"
                        }
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.registryActions.size)
        val strings = result.registryActions[0].values.strings
        assertEquals(installDir, strings["InstallPath"])
        assertEquals("C:\\users\\$user\\AppData\\Roaming\\MyGame", strings["AppData"])
    }

    // -----------------------------------------------------------------------
    // Run Process tests
    // -----------------------------------------------------------------------

    @Test
    fun parseRunProcess_parsesEntries() {
        val vdf = """
            "InstallScript"
            {
                "Run Process"
                {
                    "DirectX"
                    {
                        "HasRunKey"     "HKLM\\Software\\Valve\\Steam\\Apps\\70"
                        "Process 1"     "%INSTALLDIR%\\DirectX\\DXSETUP.exe"
                        "Command 1"     "/silent"
                        "NoCleanUp"     "1"
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.runProcessActions.size)
        val action = result.runProcessActions[0]
        assertEquals("DirectX", action.name)
        assertEquals("$installDir\\DirectX\\DXSETUP.exe", action.process)
        assertEquals("/silent", action.command)
        assertEquals("HKLM\\Software\\Valve\\Steam\\Apps\\70", action.hasRunKey)
        assertTrue(action.noCleanUp)
    }

    @Test
    fun parseRunProcess_parsesOSRequirement() {
        val vdf = """
            "InstallScript"
            {
                "Run Process"
                {
                    "VCRedist"
                    {
                        "Process 1"     "%INSTALLDIR%\\vcredist_x64.exe"
                        "Requirement_OS"
                        {
                            "Is64BitWindows"    "1"
                            "OSType"            "win7"
                        }
                    }
                }
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertEquals(1, result.runProcessActions.size)
        val action = result.runProcessActions[0]
        assertEquals("VCRedist", action.name)
        val req = action.requirementOS
        assertEquals(true, req?.is64BitWindows)
        assertEquals("win7", req?.osType)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun parse_returnsEmptyForMissingSections() {
        val vdf = """
            "InstallScript"
            {
            }
        """.trimIndent()

        val result = InstallScriptParser.parseFromString(vdf, installDir)
        assertTrue(result.registryActions.isEmpty())
        assertTrue(result.runProcessActions.isEmpty())
    }

    @Test
    fun parse_returnsEmptyForInvalidVdf() {
        val invalid = "this is not valid vdf content {{ [{{"
        val result = InstallScriptParser.parseFromString(invalid, installDir)
        assertTrue(result.registryActions.isEmpty())
        assertTrue(result.runProcessActions.isEmpty())
    }
}
