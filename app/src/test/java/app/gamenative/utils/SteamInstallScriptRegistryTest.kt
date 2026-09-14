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
                Entry(Hive.HKLM, SPORE_KEY, "Cache", ValueType.EXPAND_STRING, "%LOCALAPPDATA%\\Spore"),
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
                        "string" { "" "URL:spore" }
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
        assertTrue(systemReg.readText().contains("\"Cache\"=str(2):\"%LOCALAPPDATA%\\\\Spore\""))
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
