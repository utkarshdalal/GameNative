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

    @Test
    fun parseRunProcessMarks_marksEachStepUnderAppsKeyAndHasRunKeyInBothViews() {
        val entries = SteamInstallScriptRegistry.parseRunProcessMarks(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VCRedist2019_x64"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019\\x64"
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe"
                        "command 1" "/q /norestart"
                        "NoCleanUp" "1"
                    }
                    "EADesktopSetup"
                    {
                        "HasRunKey" "HKLM\\SOFTWARE\\Wow6432Node\\Electronic Arts\\EA Desktop"
                        "Process 1" "%INSTALLDIR%\\__Installer\\EAappInstaller.exe"
                        "MinimumHasRunValue" "3"
                    }
                    "NoKey"
                    {
                        "process 1" "%INSTALLDIR%\\setup.exe"
                    }
                }
                "Firewall"
                {
                    "Game" "%INSTALLDIR%\\game.exe"
                }
            }
            """.trimIndent(),
            APP_ID,
        )

        assertEquals(
            listOf(
                Entry(Hive.HKLM, APPS_KEY_32, "VCRedist2019_x64", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "VCRedist2019_x64", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019\\x64", "VCRedist2019_x64", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019\\x64", "VCRedist2019_x64", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_32, "EADesktopSetup", ValueType.DWORD, "3"),
                Entry(Hive.HKLM, APPS_KEY_64, "EADesktopSetup", ValueType.DWORD, "3"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Electronic Arts\\EA Desktop", "EADesktopSetup", ValueType.DWORD, "3"),
                Entry(Hive.HKLM, "Software\\Electronic Arts\\EA Desktop", "EADesktopSetup", ValueType.DWORD, "3"),
            ),
            entries,
        )
    }

    @Test
    fun parseRunProcessMarks_handlesLowercaseBlockHkcuAndLowMinimum() {
        val entries = SteamInstallScriptRegistry.parseRunProcessMarks(
            """
            "installscript"
            {
                "run process"
                {
                    "DirectX"
                    {
                        "hasrunkey" "HKEY_CURRENT_USER\\Software\\Foo\\Redist"
                        "process 1" "%INSTALLDIR%\\redist\\DXSETUP.exe"
                        "minimumhasrunvalue" "0"
                    }
                    "Bogus"
                    {
                        "HasRunKey" "HKEY_CLASSES_ROOT\\Foo"
                    }
                }
            }
            """.trimIndent(),
            APP_ID,
        )

        assertEquals(
            listOf(
                Entry(Hive.HKLM, APPS_KEY_32, "DirectX", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "DirectX", ValueType.DWORD, "1"),
                Entry(Hive.HKCU, "Software\\Foo\\Redist", "DirectX", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_32, "Bogus", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "Bogus", ValueType.DWORD, "1"),
            ),
            entries,
        )
    }

    @Test
    fun parseRunProcessMarks_usesRunKeyNameAndWritesHasRunStringKey() {
        val entries = SteamInstallScriptRegistry.parseRunProcessMarks(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "EADesktopSetup"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Electronic Arts\\EA Desktop"
                        "runkeyname" "EAInstalled"
                        "process 1" "%INSTALLDIR%\\__Installer\\EAappInstaller.exe"
                    }
                    "Origin"
                    {
                        "HasRunStringKey" "HKLM\\Software\\Origin\\Installed"
                        "HasRunStringValue" "yes"
                    }
                    "Uplay"
                    {
                        "hasrunstringkey" "HKEY_LOCAL_MACHINE\\Software\\Ubisoft\\Launcher"
                    }
                }
            }
            """.trimIndent(),
            APP_ID,
        )

        assertEquals(
            listOf(
                Entry(Hive.HKLM, APPS_KEY_32, "EAInstalled", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "EAInstalled", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Electronic Arts\\EA Desktop", "EAInstalled", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Electronic Arts\\EA Desktop", "EAInstalled", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_32, "Origin", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "Origin", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Origin", "Installed", ValueType.STRING, "yes"),
                Entry(Hive.HKLM, "Software\\Origin", "Installed", ValueType.STRING, "yes"),
                Entry(Hive.HKLM, APPS_KEY_32, "Uplay", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, APPS_KEY_64, "Uplay", ValueType.DWORD, "1"),
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Ubisoft", "Launcher", ValueType.STRING, "true"),
                Entry(Hive.HKLM, "Software\\Ubisoft", "Launcher", ValueType.STRING, "true"),
            ),
            entries,
        )
    }

    @Test
    fun parseRunProcessMarks_acceptsShortAndLongHklmPrefixesForEveryKeyKind() {
        fun script(prefix: String) = """
            "InstallScript"
            {
                "Run Process"
                {
                    "Step"
                    {
                        "HasRunKey" "$prefix\\Software\\Foo"
                        "HasRunStringKey" "$prefix\\Software\\Bar\\Done"
                    }
                }
            }
        """.trimIndent()

        val long = SteamInstallScriptRegistry.parseRunProcessMarks(script("HKEY_LOCAL_MACHINE"), APP_ID)
        val short = SteamInstallScriptRegistry.parseRunProcessMarks(script("HKLM"), APP_ID)
        val lower = SteamInstallScriptRegistry.parseRunProcessMarks(script("hklm"), APP_ID)

        assertEquals(long, short)
        assertEquals(long, lower)
        assertTrue(long.contains(Entry(Hive.HKLM, "Software\\Foo", "Step", ValueType.DWORD, "1")))
        assertTrue(long.contains(Entry(Hive.HKLM, "Software\\Wow6432Node\\Bar", "Done", ValueType.STRING, "true")))
    }

    @Test
    fun parseRunProcessMarks_returnsEmptyWithoutRunProcess() {
        assertTrue(SteamInstallScriptRegistry.parseRunProcessMarks("not a vdf {{{", APP_ID).isEmpty())
        assertTrue(SteamInstallScriptRegistry.parseRunProcessMarks(SPORE_SCRIPT, APP_ID).isEmpty())
    }

    @Test
    fun parseRunProcessMarks_writesDwordsIntoBothViews() {
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        val entries = SteamInstallScriptRegistry.parseRunProcessMarks(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "DirectX"
                    {
                        "HasRunKey" "HKLM\\Software\\Valve\\Steam\\Apps\\CommonRedist\\DirectX\\Jun2010"
                        "MinimumHasRunValue" "2"
                    }
                }
            }
            """.trimIndent(),
            APP_ID,
        )

        SteamInstallScriptRegistry.write(prefixDir, entries)

        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(2, editor.getDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\DirectX\\Jun2010", "DirectX"))
            assertEquals(2, editor.getDwordValue("Software\\Valve\\Steam\\Apps\\CommonRedist\\DirectX\\Jun2010", "DirectX"))
            assertEquals(2, editor.getDwordValue(APPS_KEY_32, "DirectX"))
            assertEquals(2, editor.getDwordValue(APPS_KEY_64, "DirectX"))
        }
    }

    @Test
    fun findCommonRedistScripts_findsNestedScriptsWithinDepth() {
        val gameDir = File(prefixDir, "game")
        val dotNet = File(gameDir, "_commonredist/DotNet/4.7.2/InstallScript.vdf")
        val vc = File(gameDir, "_commonredist/vcredist/2019/installscript.vdf")
        val tooDeep = File(gameDir, "_commonredist/a/b/c/d/installscript.vdf")
        val other = File(gameDir, "_commonredist/vcredist/2019/VC_redist.x64.exe")
        for (file in listOf(dotNet, vc, tooDeep, other)) {
            file.parentFile!!.mkdirs()
            file.writeText("x")
        }

        assertEquals(listOf(dotNet, vc), SteamInstallScriptRegistry.findCommonRedistScripts(gameDir))
        assertTrue(SteamInstallScriptRegistry.findCommonRedistScripts(File(prefixDir, "missing")).isEmpty())
    }

    @Test
    fun applyRunProcessMarks_marksGameAndCommonRedistScriptsAndReappliesWhenScriptChanges() {
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val gameScript = File(gameDir, "installscript.vdf")
        gameScript.writeText(runProcessScript("EADesktopSetup", "HKLM\\Software\\Electronic Arts\\EA Desktop"))
        val dotNetScript = File(gameDir, "_CommonRedist/DotNet/4.7.2/installscript.vdf")
        dotNetScript.parentFile!!.mkdirs()
        dotNetScript.writeText(runProcessScript("4.7.2", "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\.NET\\4.7.2"))

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, gameScript))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(1, editor.getDwordValue(APPS_KEY_32, "EADesktopSetup"))
            assertEquals(1, editor.getDwordValue("Software\\Electronic Arts\\EA Desktop", "EADesktopSetup"))
            assertEquals(1, editor.getDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\.NET\\4.7.2", "4.7.2"))
            assertEquals(1, editor.getDwordValue("Software\\Valve\\Steam\\Apps\\CommonRedist\\.NET\\4.7.2", "4.7.2"))
        }

        val afterFirst = systemReg.readText()
        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, gameScript))
        assertEquals(afterFirst, systemReg.readText())

        gameScript.writeText(runProcessScript("PhysX", "HKLM\\Software\\Valve\\Steam\\Apps\\CommonRedist\\PhysX\\9.13.0604"))
        gameScript.setLastModified(gameScript.lastModified() + 2000)

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, gameScript))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(1, editor.getDwordValue(APPS_KEY_64, "PhysX"))
            assertEquals(1, editor.getDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\PhysX\\9.13.0604", "PhysX"))
        }
        assertFalse(prefixDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun applyRunProcessMarks_skipsWithoutScriptsOrSystemReg() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val gameScript = File(gameDir, "installscript.vdf")
        gameScript.writeText(runProcessScript("Step", "HKLM\\Software\\Foo"))

        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, gameScript))
        assertFalse(File(prefixDir, "system.reg").exists())

        File(prefixDir, "system.reg").writeText("WINE REGISTRY Version 2\n\n")
        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, null))
    }

    @Test
    fun redistMarkEntries_coversCommonRedistAndVcRuntimesInBothViews() {
        val entries = SteamInstallScriptRegistry.redistMarkEntries()

        assertEquals(SteamInstallScriptRegistry.COMMON_REDIST_MARKS.size * 2 + 3 * 2 * 6, entries.size)
        assertTrue(entries.all { it.hive == Hive.HKLM })
        assertTrue(
            entries.contains(
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022", "x64 14.51.36247.0", ValueType.DWORD, "1"),
            ),
        )
        assertTrue(
            entries.contains(
                Entry(Hive.HKLM, "Software\\Valve\\Steam\\Apps\\CommonRedist\\DirectX\\Jun2010", "dxsetup", ValueType.DWORD, "1"),
            ),
        )
        assertTrue(
            entries.contains(
                Entry(Hive.HKLM, "Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\.NET\\4.8", "4.8", ValueType.DWORD, "1"),
            ),
        )
        for (arch in listOf("x86", "x64", "arm64")) {
            for (root in listOf("Software\\Wow6432Node", "Software")) {
                val key = "$root\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\$arch"
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Installed", ValueType.DWORD, "1")))
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Major", ValueType.DWORD, "14")))
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Minor", ValueType.DWORD, "44")))
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Bld", ValueType.DWORD, "35211")))
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Rbld", ValueType.DWORD, "0")))
                assertTrue(entries.contains(Entry(Hive.HKLM, key, "Version", ValueType.STRING, "v14.44.35211.00")))
            }
        }
    }

    @Test
    fun writeRedistMarks_writesOnceAndStampsPrefix() {
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")

        assertTrue(SteamInstallScriptRegistry.writeRedistMarks(prefixDir))

        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(1, editor.getDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019", "x86 14.28.29334.0"))
            assertEquals(1, editor.getDwordValue("Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2008", "x64 SP1"))
            assertEquals(35211, editor.getDwordValue("Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64", "Bld"))
            assertEquals("v14.44.35211.00", editor.getStringValue("Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\arm64", "Version"))
        }
        assertTrue(prefixDir.listFiles().orEmpty().any { it.name.startsWith(".steam_redist_marks_v") })

        val afterFirst = systemReg.readText()
        assertFalse(SteamInstallScriptRegistry.writeRedistMarks(prefixDir))
        assertEquals(afterFirst, systemReg.readText())
        assertFalse(prefixDir.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun writeRedistMarks_skipsPrefixWithoutSystemReg() {
        assertFalse(SteamInstallScriptRegistry.writeRedistMarks(prefixDir))
        assertFalse(File(prefixDir, "system.reg").exists())
        assertTrue(prefixDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun parseRunProcessSteps_resolvesInstallDirProcessesAndRequirements() {
        val gameDir = File(prefixDir, "game")
        val x64 = File(gameDir, "_CommonRedist/vcredist/2019/VC_redist.x64.exe").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        val script = File(gameDir, "_CommonRedist/vcredist/2019/installscript.vdf")
        val vdf = """
            "InstallScript"
            {
                "Run Process"
                {
                    "VC Redist 2019 x64"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019"
                        "process 1" "%INSTALLDIR%\\_commonredist\\VCREDIST\\2019\\vc_redist.x64.exe"
                        "command 1" "/install /quiet /norestart"
                        "Requirement_OS"
                        {
                            "Is64BitWindows" "1"
                            "OSType" "Windows"
                        }
                    }
                    "VC Redist 2019 x86"
                    {
                        "process 2" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x86.exe"
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe"
                    }
                }
            }
        """.trimIndent()

        val steps = SteamInstallScriptRegistry.parseRunProcessSteps(vdf, script, gameDir)

        assertEquals(3, steps.size)
        val first = steps[0]
        assertEquals("VC Redist 2019 x64", first.name)
        assertEquals("process 1", first.processKey)
        assertEquals("A:\\_commonredist\\VCREDIST\\2019\\vc_redist.x64.exe", first.path)
        assertEquals(x64, first.file)
        assertEquals("HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019", first.hasRunKey)
        assertEquals(mapOf("Is64BitWindows" to "1", "OSType" to "Windows"), first.requirementOs)
        assertEquals(true, first.requires64BitWindows)
        assertEquals(script, first.script)

        assertEquals(listOf("process 1", "process 2"), steps.drop(1).map { it.processKey })
        assertEquals(x64, steps[1].file)
        assertEquals(null, steps[2].file)
        assertEquals("A:\\_CommonRedist\\vcredist\\2019\\VC_redist.x86.exe", steps[2].path)
        assertEquals(null, steps[2].hasRunKey)
        assertEquals(null, steps[2].requires64BitWindows)
    }

    @Test
    fun parseRunProcessSteps_doesNotResolvePathsOutsideTheGameDir() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        File(prefixDir, "outside.exe").writeText("x")
        val vdf = """
            "InstallScript"
            {
                "Run Process"
                {
                    "outside"
                    {
                        "process 1" "%INSTALLDIR%\\..\\outside.exe"
                    }
                    "windows"
                    {
                        "process 1" "%WINDIR%\\system32\\cmd.exe"
                    }
                    "empty"
                    {
                        "process 1" ""
                    }
                }
            }
        """.trimIndent()

        val steps = SteamInstallScriptRegistry.parseRunProcessSteps(vdf, File(gameDir, "installscript.vdf"), gameDir)

        assertEquals(listOf("outside", "windows"), steps.map { it.name })
        assertTrue(steps.all { it.file == null })
        assertEquals("C:\\windows\\system32\\cmd.exe", steps[1].path)
    }

    @Test
    fun runProcessSteps_readsGameScriptAndNestedCommonRedistScripts() {
        val gameDir = File(prefixDir, "game")
        val gameSetup = File(gameDir, "Redist/setup.exe").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        File(gameDir, "InstallScript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "Game Setup"
                    {
                        "process 1" "%INSTALLDIR%\\Redist\\setup.exe"
                    }
                }
            }
            """.trimIndent(),
        )
        val vc2022 = File(gameDir, "_CommonRedist/vcredist/2022/VC_redist.x64.exe").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        File(vc2022.parentFile, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VC 2022 x64"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                    }
                    "VC 2022 x86"
                    {
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x86.exe"
                    }
                }
            }
            """.trimIndent(),
        )
        File(gameDir, "_CommonRedist/DirectX/Jun2010/installscript.vdf").apply {
            parentFile!!.mkdirs()
            writeText("not a vdf {")
        }

        val steps = SteamInstallScriptRegistry.runProcessSteps(gameDir, "installscript.vdf")

        assertEquals(listOf("Game Setup", "VC 2022 x64", "VC 2022 x86"), steps.map { it.name })
        assertEquals(listOf(gameSetup, vc2022, null), steps.map { it.file })
        assertEquals(steps, SteamInstallScriptRegistry.runProcessSteps(gameDir, null))
        assertEquals(steps, SteamInstallScriptRegistry.runProcessSteps(gameDir, "  "))
        assertEquals(listOf("VC 2022 x64", "VC 2022 x86"), SteamInstallScriptRegistry.runProcessSteps(gameDir, "missing.vdf").map { it.name })
        assertTrue(SteamInstallScriptRegistry.runProcessSteps(File(prefixDir, "missing"), "installscript.vdf").isEmpty())
    }

    @Test
    fun gameScriptFile_fallsBackToRootInstallScriptWhenTheStoredNameIsBlank() {
        val gameDir = File(prefixDir, "Borderlands 2").apply { mkdirs() }
        assertEquals(null, SteamInstallScriptRegistry.gameScriptFile(gameDir, ""))

        val root = File(gameDir, "InstallScript.VDF").apply { writeText(runProcessScript("vcredist2010", "HKLM\\Software\\Gearbox")) }
        val named = File(gameDir, "Binaries/setup.vdf").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }

        assertEquals(root, SteamInstallScriptRegistry.gameScriptFile(gameDir, ""))
        assertEquals(root, SteamInstallScriptRegistry.gameScriptFile(gameDir, null))
        assertEquals(named, SteamInstallScriptRegistry.gameScriptFile(gameDir, "binaries\\SETUP.vdf"))
        assertEquals(null, SteamInstallScriptRegistry.gameScriptFile(gameDir, "missing.vdf"))
    }

    @Test
    fun runProcessSteps_readsRootScriptWithoutAStoredNameAndFindsTheRedistItRuns() {
        val gameDir = File(prefixDir, "Borderlands 2")
        val redist = File(gameDir, "Binaries/Redist/vcredist_x86_vs2010sp1.exe").apply {
            parentFile!!.mkdirs()
            writeText("x")
        }
        File(gameDir, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VCRedist2010"
                    {
                        "process 1" "%INSTALLDIR%\\Binaries\\Redist\\vcredist_x86_vs2010sp1.exe"
                        "command 1" "/q /norestart"
                    }
                }
            }
            """.trimIndent(),
        )

        val steps = SteamInstallScriptRegistry.runProcessSteps(gameDir, "")

        assertEquals(listOf("VCRedist2010"), steps.map { it.name })
        assertEquals(redist, steps.single().file)
    }

    @Test
    fun raiseVcRuntimeVersion_writesEveryArchInBothViewsWithoutDowngrading() {
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        val runtimes = "Microsoft\\VisualStudio\\14.0\\VC\\Runtimes"
        SteamInstallScriptRegistry.write(
            prefixDir,
            SteamInstallScriptRegistry.vcRuntimeEntries("Software\\$runtimes\\x64", SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34433)) +
                SteamInstallScriptRegistry.vcRuntimeEntries("Software\\Wow6432Node\\$runtimes\\x86", SteamInstallScriptRegistry.VcRuntimeVersion(14, 50, 35719)),
        )
        val target = SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34438)

        assertTrue(SteamInstallScriptRegistry.raiseVcRuntimeVersion(prefixDir, target))

        WineRegistryEditor(systemReg).use { editor ->
            for (arch in listOf("x86", "x64", "arm64")) {
                for (root in listOf("Software\\Wow6432Node", "Software")) {
                    val key = "$root\\$runtimes\\$arch"
                    if (root == "Software\\Wow6432Node" && arch == "x86") {
                        assertEquals(50, editor.getDwordValue(key, "Minor"))
                        assertEquals("v14.50.35719.00", editor.getStringValue(key, "Version"))
                        continue
                    }
                    assertEquals(1, editor.getDwordValue(key, "Installed"))
                    assertEquals(14, editor.getDwordValue(key, "Major"))
                    assertEquals(42, editor.getDwordValue(key, "Minor"))
                    assertEquals(34438, editor.getDwordValue(key, "Bld"))
                    assertEquals(0, editor.getDwordValue(key, "Rbld"))
                    assertEquals("v14.42.34438.00", editor.getStringValue(key, "Version"))
                }
            }
        }

        val afterRaise = systemReg.readText()
        assertFalse(SteamInstallScriptRegistry.raiseVcRuntimeVersion(prefixDir, SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34430)))
        assertFalse(SteamInstallScriptRegistry.raiseVcRuntimeVersion(prefixDir, target))
        assertEquals(afterRaise, systemReg.readText())
        assertFalse(SteamInstallScriptRegistry.raiseVcRuntimeVersion(File(prefixDir, "missing"), target))
    }

    @Test
    fun vcRuntimeVersion_ordersAndFormats() {
        val fromFile = SteamInstallScriptRegistry.VcRuntimeVersion.fromFileVersion((14L shl 48) or (44L shl 32) or (35211L shl 16))

        assertEquals(SteamInstallScriptRegistry.VcRuntimeVersion(14, 44, 35211), fromFile)
        assertEquals("v14.44.35211.00", fromFile.text)
        assertTrue(SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34438) > SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34433))
        assertTrue(SteamInstallScriptRegistry.VcRuntimeVersion(14, 44, 1) > SteamInstallScriptRegistry.VcRuntimeVersion(14, 42, 34438))
    }

    @Test
    fun parseRunProcessMarks_marksArkhamPhysxDirectxAndVcSteps() {
        val marked = marksFor(
            """
            "PhysX Version"
            {
                "HasRunKey" "HKEY_LOCAL_MACHINE\\SOFTWARE\\AGEIA Technologies"
                "Process 1" "%INSTALLDIR%\\redist\\PhysX_9.08.14_9.09.0814_SystemSoftware.exe"
                "Command 1" "/quiet"
                "MinimumHasRunValue" "9090814"
            }
            "DirectX"
            {
                "Process 1" "%INSTALLDIR%\\redist\\DirectX\\DXSETUP.exe"
                "Command 1" "/silent"
            }
            "VCRedist"
            {
                "Process 1" "%INSTALLDIR%\\redist\\vcredist_x86.exe"
                "Command 1" "/q:a"
            }
            """.trimIndent(),
        )

        assertEquals(listOf("PhysX Version", "DirectX", "VCRedist"), marked)
    }

    @Test
    fun applyRunProcessMarks_doesNotLowerAnExistingVersionValue() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val script = File(gameDir, "installscript.vdf").apply {
            writeText(
                """
                "InstallScript"
                {
                    "Run Process"
                    {
                        "PhysX Version"
                        {
                            "HasRunKey" "HKEY_LOCAL_MACHINE\\SOFTWARE\\AGEIA Technologies"
                            "Process 1" "%INSTALLDIR%\\redist\\PhysX.exe"
                            "MinimumHasRunValue" "9090814"
                        }
                    }
                }
                """.trimIndent(),
            )
        }
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        WineRegistryEditor(systemReg).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            editor.setDwordValue("Software\\AGEIA Technologies", "PhysX Version", 9231019)
            editor.setDwordValue("Software\\Wow6432Node\\AGEIA Technologies", "PhysX Version", 1)
        }

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))

        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(9231019, editor.getDwordValue("Software\\AGEIA Technologies", "PhysX Version"))
            assertEquals(9090814, editor.getDwordValue("Software\\Wow6432Node\\AGEIA Technologies", "PhysX Version"))
            assertEquals(9090814, editor.getDwordValue(APPS_KEY_64, "PhysX Version"))
        }
    }

    @Test
    fun applyRunProcessMarks_reappliesWhenAStampedMarkIsMissing() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val script = File(gameDir, "installscript.vdf").apply {
            writeText(runProcessScript("VCRedist", "HKLM\\Software\\Valve\\Steam\\Apps\\$APP_ID"))
        }
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))

        WineRegistryEditor(systemReg).use { editor -> editor.removeValue(APPS_KEY_32, "VCRedist") }

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(1, editor.getDwordValue(APPS_KEY_32, "VCRedist"))
            assertEquals(1, editor.getDwordValue(APPS_KEY_64, "VCRedist"))
        }
    }

    @Test
    fun clearPrefixStamps_removesEveryMarkStampSoAFreshPrefixIsRemarked() {
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        assertTrue(SteamInstallScriptRegistry.writeRedistMarks(prefixDir))
        File(prefixDir, ".steam_install_script_run_process_$APP_ID").writeText("x")
        File(prefixDir, ".steam_install_script_installed_$APP_ID").writeText("")
        File(prefixDir, "user.reg").writeText("WINE REGISTRY Version 2\n\n")

        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        SteamInstallScriptRegistry.clearPrefixStamps(prefixDir)

        assertFalse(prefixDir.listFiles().orEmpty().any { it.name.startsWith(".steam_") })
        assertTrue(File(prefixDir, "user.reg").isFile)
        assertTrue(SteamInstallScriptRegistry.writeRedistMarks(prefixDir))
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals(1, editor.getDwordValue("Software\\Wow6432Node\\Valve\\Steam\\Apps\\CommonRedist\\DirectX\\Jun2010", "dxsetup"))
        }
    }

    private fun marksFor(step: String, gameDir: File? = null): List<String> {
        val vdf = """
            "InstallScript"
            {
                "Run Process"
                {
                    $step
                }
            }
        """.trimIndent()
        return SteamInstallScriptRegistry.parseRunProcessMarks(vdf, APP_ID, gameDir)
            .filter { it.key == APPS_KEY_64 }
            .mapNotNull { it.name }
    }

    @Test
    fun parseRunProcessMarks_marksRedistributableSteps() {
        val marked = marksFor(
            """
            "VC2022"
            {
                "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                "command 1" "/install /quiet /norestart"
            }
            "DirectX"
            {
                "process 1" "%INSTALLDIR%\\_CommonRedist\\DirectX\\Jun2010\\DXSETUP.exe"
            }
            "Framework"
            {
                "process 1" "%INSTALLDIR%\\Redist\\NDP472-KB4054530-x86-x64-AllOS-ENU.exe"
            }
            "Origin"
            {
                "process 1" "%INSTALLDIR%\\__Installer\\Origin\\redist\\internal\\OriginThinSetup.exe"
            }
            "ByCommand"
            {
                "process 1" "%WINDIR%\\system32\\msiexec.exe"
                "command 1" "/i %INSTALLDIR%\\Redist\\PhysX_9.13.0604_SystemSoftware.msi /quiet"
            }
            """,
        )

        assertEquals(listOf("VC2022", "DirectX", "Framework", "Origin", "ByCommand"), marked)
    }

    @Test
    fun parseRunProcessMarks_leavesOtherProcessesUnmarked() {
        val marked = marksFor(
            """
            "Register"
            {
                "HasRunKey" "HKLM\\Software\\Studio\\Game"
                "process 1" "%INSTALLDIR%\\setup.exe"
                "command 1" "/register"
            }
            "Vc"
            {
                "process 1" "%INSTALLDIR%\\vcredist_x86.exe"
            }
            """,
        )

        assertEquals(listOf("Vc"), marked)
    }

    @Test
    fun parseRunProcessMarks_marksStepsWithoutAProcess() {
        val marked = marksFor(
            """
            "KeyOnly"
            {
                "HasRunKey" "HKLM\\Software\\Studio\\Game"
                "MinimumHasRunValue" "2"
            }
            "EmptyProcess"
            {
                "process 1" ""
                "HasRunKey" "HKLM\\Software\\Studio\\Other"
            }
            """,
        )

        assertEquals(listOf("KeyOnly", "EmptyProcess"), marked)
    }

    @Test
    fun parseRunProcessMarks_marksCmdStepsByTheInstallerTheyStart() {
        val gameDir = File(prefixDir, "game")
        File(gameDir, "Prereqs").mkdirs()
        File(gameDir, "Prereqs/Microsoft Visual C++ 2022 x86.cmd")
            .writeText("start /w \"\" \"%~dp0\\VC_redist.x86.exe\" /q /norestart\r\n")
        File(gameDir, "tools").mkdirs()
        File(gameDir, "tools/register.cmd").writeText("@echo off\r\nregsvr32 /s \"%~dp0\\game.dll\"\r\n")
        val steps = """
            "VC 2022 x86"
            {
                "process 1" "%INSTALLDIR%\\Prereqs\\Microsoft Visual C++ 2022 x86.cmd"
            }
            "Register"
            {
                "process 1" "%INSTALLDIR%\\tools\\register.cmd"
            }
            "Missing"
            {
                "process 1" "%INSTALLDIR%\\tools\\missing.cmd"
            }
        """

        assertEquals(listOf("VC 2022 x86"), marksFor(steps, gameDir))
        assertEquals(emptyList<String>(), marksFor(steps, null))
    }

    @Test
    fun parseRunProcessMarks_leavesMixedStepsUnmarkedAndPairsCommandsWithTheirProcess() {
        val marked = marksFor(
            """
            "Mixed"
            {
                "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
                "process 2" "%INSTALLDIR%\\setup.exe"
            }
            "BothArches"
            {
                "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x86.exe"
                "process 2" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\VC_redist.x64.exe"
            }
            "OtherCommand"
            {
                "process 1" "%INSTALLDIR%\\setup.exe"
                "process 2" "%WINDIR%\\system32\\msiexec.exe"
                "command 2" "/i %INSTALLDIR%\\Redist\\PhysX_9.13.0604_SystemSoftware.msi /quiet"
            }
            "UnrealPrereqs"
            {
                "process 1" "%INSTALLDIR%\\Engine\\Extras\\Redist\\en-us\\UE4PrereqSetup_x64.exe"
            }
            "UnrealPrereqsNew"
            {
                "process 1" "%INSTALLDIR%\\Engine\\Extras\\Redist\\en-us\\UEPrereqSetup_x64.exe"
            }
            """,
        )

        assertEquals(listOf("BothArches", "UnrealPrereqs", "UnrealPrereqsNew"), marked)
    }

    @Test
    fun parseRunProcessMarks_marksBatStepsByTheInstallerTheyStart() {
        val gameDir = File(prefixDir, "game")
        File(gameDir, "Prereqs").mkdirs()
        File(gameDir, "Prereqs/vc.bat").writeText("start /w \"\" \"%~dp0\\vcredist_x86.exe\" /q\r\n")
        val steps = """
            "Vc"
            {
                "process 1" "%INSTALLDIR%\\Prereqs\\vc.bat"
            }
        """

        assertEquals(listOf("Vc"), marksFor(steps, gameDir))
    }

    @Test
    fun applyRunProcessMarks_keepsDwordsWithTheHighBitSet() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val script = File(gameDir, "installscript.vdf").apply {
            writeText(runProcessScript("VCRedist", "HKLM\\Software\\Studio\\Game"))
        }
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        WineRegistryEditor(systemReg).use { editor ->
            editor.setCreateKeyIfNotExist(true)
            editor.setDwordValue("Software\\Studio\\Game", "VCRedist", 0x80000001.toInt())
        }

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))

        WineRegistryEditor(systemReg).use { editor ->
            assertTrue(SteamInstallScriptRegistry.storedDword(editor, "Software\\Studio\\Game", "VCRedist")!! >= 0x80000000L)
            assertEquals(1, editor.getDwordValue("Software\\Wow6432Node\\Studio\\Game", "VCRedist"))
        }
    }

    @Test
    fun applyRunProcessMarks_reappliesWhenAStampedMarkWasLoweredOrAUserMarkIsMissing() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val script = File(gameDir, "installscript.vdf").apply {
            writeText(runProcessScript("VCRedist", "HKCU\\Software\\Studio\\Game"))
        }
        val systemReg = File(prefixDir, "system.reg")
        systemReg.writeText("WINE REGISTRY Version 2\n\n")
        val userReg = File(prefixDir, "user.reg")

        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        assertFalse(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))

        WineRegistryEditor(userReg).use { editor -> editor.removeValue("Software\\Studio\\Game", "VCRedist") }
        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        WineRegistryEditor(userReg).use { editor -> assertEquals(1, editor.getDwordValue("Software\\Studio\\Game", "VCRedist")) }

        WineRegistryEditor(systemReg).use { editor -> editor.setDwordValue(APPS_KEY_64, "VCRedist", 0) }
        assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
        WineRegistryEditor(systemReg).use { editor -> assertEquals(1, editor.getDwordValue(APPS_KEY_64, "VCRedist")) }
    }

    @Test
    fun applyRunProcessMarks_doesNotStampWhenAScriptCannotBeRead() {
        val gameDir = File(prefixDir, "game").apply { mkdirs() }
        val script = File(gameDir, "installscript.vdf").apply {
            writeText(runProcessScript("VCRedist", "HKLM\\Software\\Studio\\Game"))
        }
        val unreadable = File(gameDir, "_CommonRedist/vcredist/2022/installscript.vdf").apply {
            parentFile!!.mkdirs()
            writeText(runProcessScript("2022", "HKLM\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2022"))
            setReadable(false)
        }
        File(prefixDir, "system.reg").writeText("WINE REGISTRY Version 2\n\n")

        try {
            org.junit.Assume.assumeFalse(unreadable.canRead())
            assertTrue(SteamInstallScriptRegistry.applyRunProcessMarks(prefixDir, gameDir, APP_ID, script))
            assertFalse(File(prefixDir, ".steam_install_script_run_process_$APP_ID").exists())
        } finally {
            unreadable.setReadable(true)
        }
    }

    private fun runProcessScript(
        step: String,
        hasRunKey: String,
        process: String = "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe",
    ) = """
        "InstallScript"
        {
            "Run Process"
            {
                "$step"
                {
                    "HasRunKey" "${hasRunKey.replace("\\", "\\\\")}"
                    "process 1" "${process.replace("\\", "\\\\")}"
                }
            }
        }
    """.trimIndent()

    private companion object {
        const val APP_ID = 1222670
        const val APPS_KEY_32 = "Software\\Wow6432Node\\Valve\\Steam\\Apps\\1222670"
        const val APPS_KEY_64 = "Software\\Valve\\Steam\\Apps\\1222670"
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
