package app.gamenative.utils

import app.gamenative.utils.SteamInstallScriptRegistry.Entry
import app.gamenative.utils.SteamInstallScriptRegistry.Hive
import app.gamenative.utils.SteamInstallScriptRegistry.ValueType
import com.winlator.core.WineRegistryEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
class SteamInstallScriptRegistryTest {
    private lateinit var prefixDir: File

    @Before
    fun setUp() {
        prefixDir = createTempDirectory(prefix = "steam-install-script-prefix").toFile()
    }

    @After
    fun tearDown() {
        prefixDir.deleteRecursively()
    }

    @Test
    fun parse_expandsTokensAndRedirectsHklmSoftwareTo32BitView() {
        val entries = SteamInstallScriptRegistry.parse(SPORE_SCRIPT, "A:\\")

        assertEquals(
            listOf(
                Entry(Hive.HKLM, SPORE_KEY, "InstallLoc", ValueType.STRING, "A:\\"),
                Entry(Hive.HKLM, SPORE_KEY, "DataDir", ValueType.STRING, "A:\\Data"),
                Entry(Hive.HKLM, SPORE_KEY, "Cache", ValueType.EXPAND_STRING, "C:\\users\\xuser\\AppData\\Local\\Spore"),
                Entry(Hive.HKLM, SPORE_KEY, "Installed", ValueType.DWORD, "1"),
                Entry(Hive.HKCU, "Software\\Electronic Arts\\SPORE", "Language", ValueType.STRING, "en_US"),
            ),
            entries,
        )
    }

    @Test
    fun parse_skipsUnsupportedHivesAndKeepsExistingWow6432Node() {
        val entries = SteamInstallScriptRegistry.parse(
            """
            "InstallScript"
            {
                "Registry"
                {
                    "HKEY_CLASSES_ROOT\\spore"
                    {
                        "string" { "(Default)" "URL:spore" }
                    }
                    "HKLM\\Software\\Wow6432Node\\Foo"
                    {
                        "dword" { "Bar" "0x10" }
                    }
                }
            }
            """.trimIndent(),
            "A:\\",
        )

        assertEquals(
            listOf(Entry(Hive.HKLM, "Software\\Wow6432Node\\Foo", "Bar", ValueType.DWORD, "0x10")),
            entries,
        )
    }

    @Test
    fun parse_selectsLanguageBlockAndFallsBackToEnglish() {
        val script = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKLM\\Software\\Foo"
                    {
                        "dword"
                        {
                            "english" { "Language" "1" }
                            "german" { "Language" "3" }
                            "Installed" "1"
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Foo", "Installed", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Foo", "Language", ValueType.DWORD, "3"),
            ),
            SteamInstallScriptRegistry.parse(script, "A:\\", "german"),
        )
        assertEquals(
            listOf(
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Foo", "Installed", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Foo", "Language", ValueType.DWORD, "1"),
            ),
            SteamInstallScriptRegistry.parse(script, "A:\\", "french"),
        )
    }

    @Test
    fun parse_expandsSteamTokensAndDefaultValueName() {
        val script = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKCU\\Software\\Foo"
                    {
                        "string"
                        {
                            "(Default)" "%ROOTDRIVE%:\\Games"
                            "Saves" "%USER_MYDOCS%\\Foo"
                            "Cache" "%LOCALAPPDATA%"
                            "Client" "%StEaMpAtH%/steam.exe"
                            "Unknown" "%NOPE%\\x"
                        }
                    }
                }
            }
        """.trimIndent()

        assertEquals(
            listOf(
                Entry(Hive.HKCU, "Software\\Foo", null, ValueType.STRING, "A:\\Games"),
                Entry(Hive.HKCU, "Software\\Foo", "Saves", ValueType.STRING, "C:\\users\\xuser\\Documents\\Foo"),
                Entry(Hive.HKCU, "Software\\Foo", "Cache", ValueType.STRING, "C:\\users\\xuser\\AppData\\Local"),
                Entry(Hive.HKCU, "Software\\Foo", "Client", ValueType.STRING, "C:\\Program Files (x86)\\Steam\\steam.exe"),
                Entry(Hive.HKCU, "Software\\Foo", "Unknown", ValueType.STRING, "%NOPE%\\x"),
            ),
            SteamInstallScriptRegistry.parse(script, "A:\\"),
        )
    }

    @Test
    fun parse_returnsEmptyForMalformedOrScriptWithoutRegistry() {
        assertTrue(SteamInstallScriptRegistry.parse("not a vdf {{{", "A:\\").isEmpty())
        assertTrue(SteamInstallScriptRegistry.parse("\"InstallScript\" { \"Run Process\" { } }", "A:\\").isEmpty())
    }

    @Test
    fun write_createsRegFilesAndStoresEachValueType() {
        val entries = SteamInstallScriptRegistry.parse(SPORE_SCRIPT, "A:\\")

        SteamInstallScriptRegistry.write(prefixDir, entries)

        val systemReg = File(prefixDir, "system.reg")
        val userReg = File(prefixDir, "user.reg")
        assertTrue(systemReg.readText().startsWith("WINE REGISTRY Version 2"))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals("A:\\", editor.getStringValue(SPORE_KEY, "InstallLoc"))
            assertEquals("A:\\Data", editor.getStringValue(SPORE_KEY, "DataDir"))
            assertEquals(1, editor.getDwordValue(SPORE_KEY, "Installed"))
        }
        assertTrue(systemReg.readText().contains("\"Cache\"=str(2):\"C:\\\\users\\\\xuser\\\\AppData\\\\Local\\\\Spore\""))
        WineRegistryEditor(userReg).use { editor ->
            assertEquals("en_US", editor.getStringValue("Software\\Electronic Arts\\SPORE", "Language"))
        }
        assertFalse(prefixDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    private companion object {
        const val SPORE_KEY = "Software\\Wow6432Node\\Electronic Arts\\SPORE"

        val SPORE_SCRIPT = """
            "InstallScript"
            {
                "Registry"
                {
                    "HKEY_LOCAL_MACHINE\\Software\\Electronic Arts\\SPORE"
                    {
                        "string"
                        {
                            "InstallLoc" "%INSTALLDIR%"
                            "DataDir" "%INSTALLDIR%\\Data"
                        }
                        "expandstring"
                        {
                            "Cache" "%LOCALAPPDATA%\\Spore"
                        }
                        "dword"
                        {
                            "Installed" "1"
                        }
                    }
                    "HKEY_CURRENT_USER\\Software\\Electronic Arts\\SPORE"
                    {
                        "string"
                        {
                            "Language" "en_US"
                        }
                    }
                }
            }
        """.trimIndent()
    }
}
