package app.gamenative.utils

import app.gamenative.gamefixes.CabArchive
import com.winlator.container.Container
import com.winlator.core.WineRegistryEditor
import java.io.ByteArrayOutputStream
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RedistInstallerExtractorTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var components: JSONObject
    private lateinit var windowsDir: File
    private lateinit var workDir: File

    @Before
    fun setUp() {
        val asset = listOf("src/main/assets/wincomponents/wincomponents.json", "app/src/main/assets/wincomponents/wincomponents.json")
            .map(::File)
            .first { it.isFile }
        components = JSONObject(asset.readText())
        windowsDir = temporary.newFolder("windows")
        workDir = File(temporary.root, "work")
    }

    @Test
    fun allowedDllsComeFromWincomponentsJson() {
        assertEquals(
            setOf(
                "concrt140.dll", "msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll", "msvcp140_atomic_wait.dll",
                "msvcp140_codecvt_ids.dll", "vcamp140.dll", "vccorlib140.dll", "vcomp140.dll", "vcruntime140.dll",
                "vcruntime140_1.dll",
            ),
            RedistInstallerExtractor.allowedDlls(components, "vcrun2022"),
        )
        assertEquals(
            setOf("atl80.dll", "msvcm80.dll", "msvcp80.dll", "msvcr80.dll", "vcomp.dll"),
            RedistInstallerExtractor.allowedDlls(components, "vcrun2005"),
        )
        assertEquals(emptySet<String>(), RedistInstallerExtractor.allowedDlls(components, "vcrun1999"))
    }

    @Test
    fun everyVcrunFamilyHasAnAllowList() {
        for (family in RedistInstallerExtractor.FAMILY_MAJOR_VERSIONS.keys) {
            assertTrue(family, RedistInstallerExtractor.allowedDlls(components, family).isNotEmpty())
        }
    }

    @Test
    fun selectKeepsTheNewestBuildPerNameAndArchitecture() {
        val old = candidate("msvcp140.dll", PeVersionReader.MACHINE_AMD64, 14, 28)
        val new = candidate("msvcp140.dll", PeVersionReader.MACHINE_AMD64, 14, 44)
        val x86 = candidate("msvcp140.dll", PeVersionReader.MACHINE_I386, 14, 28)

        assertEquals(listOf(x86, new), RedistInstallerExtractor.select(listOf(old, x86, new)))
    }

    @Test
    fun placesFamilyDllsFromNestedCabinetsByVersionInfo() {
        val x64 = dll(PeVersionReader.MACHINE_AMD64, 14, "msvcp140.dll")
        val x86 = dll(PeVersionReader.MACHINE_I386, 14, "MSVCP140.DLL")
        val members = mapOf(
            "a10" to cabinet("F_CENTRAL_one", "two_x86", "ucrt", "old", "arm64ec", "mfc", "text"),
            "F_CENTRAL_one" to x64,
            "two_x86" to x86,
            "ucrt" to dll(PeVersionReader.MACHINE_AMD64, 10, "ucrtbase.dll"),
            "old" to dll(PeVersionReader.MACHINE_AMD64, 12, "vcruntime140.dll"),
            "arm64ec" to dll(PeVersionReader.MACHINE_AMD64, 14, "vcruntime140_1.dll", hybrid = true),
            "mfc" to dll(PeVersionReader.MACHINE_AMD64, 14, "mfc140.dll"),
            "text" to "not a binary".toByteArray(),
        )
        val installer = installer("VC_redist.x64.exe", ByteArray(300) { 3 } + cabinet("u0") + ByteArray(20) + cabinet("a10"))

        val placed = place(listOf(installer), "vcrun2022", members)

        assertEquals(listOf("msvcp140"), placed)
        assertArrayEquals(x64, File(windowsDir, "system32/msvcp140.dll").readBytes())
        assertArrayEquals(x86, File(windowsDir, "syswow64/msvcp140.dll").readBytes())
        assertFalse(File(windowsDir, "system32/vcruntime140.dll").exists())
        assertFalse(File(windowsDir, "system32/vcruntime140_1.dll").exists())
        assertFalse(File(windowsDir, "system32/ucrtbase.dll").exists())
        assertFalse(File(windowsDir, "system32/mfc140.dll").exists())
        assertFalse(workDir.exists())
    }

    @Test
    fun picksTheNewestBuildAcrossInstallers() {
        val older = dll(PeVersionReader.MACHINE_AMD64, 14, "vcruntime140.dll", minor = 16)
        val newer = dll(PeVersionReader.MACHINE_AMD64, 14, "vcruntime140.dll", minor = 44)
        val first = installer("2017/VC_redist.x64.exe", cabinet("old"))
        val second = installer("2022/VC_redist.x64.exe", cabinet("new"))

        val placed = place(listOf(first, second), "vcrun2022", mapOf("old" to older, "new" to newer))

        assertEquals(listOf("vcruntime140"), placed)
        assertArrayEquals(newer, File(windowsDir, "system32/vcruntime140.dll").readBytes())
    }

    @Test
    fun skipsInstallersWhoseOwnVersionIsAnotherFamily() {
        val payload = dll(PeVersionReader.MACHINE_I386, 14, "msvcp140.dll")
        val vc2013 = installer(
            "vcredist_x86.exe",
            SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(12, 0, 30501, 0), mapOf("OriginalFilename" to "vcredist_x86.exe")) +
                cabinet("payload"),
        )
        var unpacked = 0

        val placed = RedistInstallerExtractor.place(
            listOf(vc2013),
            "vcrun2022",
            allowedByFamily(),
            windowsDir,
            workDir,
            HashMap(),
        ) { cabinet, dir, maxBytes ->
            unpacked++
            unpack(mapOf("payload" to payload), cabinet, dir, maxBytes)
        }

        assertEquals(emptyList<String>(), placed)
        assertEquals(0, unpacked)
    }

    @Test
    fun returnsEmptyWhenNothingMatchesOrUnpackingFails() {
        val installer = installer("VC_redist.x64.exe", cabinet("a10"))

        assertEquals(emptyList<String>(), place(listOf(installer), "vcrun2010", mapOf("a10" to dll(PeVersionReader.MACHINE_AMD64, 14, "msvcp140.dll"))))
        assertEquals(
            emptyList<String>(),
            RedistInstallerExtractor.place(
                listOf(installer, File(temporary.root, "missing.exe")),
                "vcrun2022",
                allowedByFamily(),
                windowsDir,
                workDir,
                HashMap(),
            ) { _, _, _ -> throw java.io.IOException("unsupported compression") },
        )
        assertFalse(workDir.exists())
    }

    @Test
    fun installersForUnionsTheContainerExtraWithTheSteamworksLayout() {
        val gameDir = temporary.newFolder("game")
        val listed = installer("elsewhere/redist/vc_redist.x64.exe", ByteArray(4))
        val notes = installer("elsewhere/redist/readme.txt", ByteArray(4))
        File(gameDir, "_CommonRedist/vcredist/2022").mkdirs()
        val layout = File(gameDir, "_CommonRedist/vcredist/2022/VC_redist.x64.exe").apply { writeBytes(ByteArray(4)) }
        val container = Container("GOG_123").apply {
            putExtra(
                RedistInstallerExtractor.INSTALLERS_EXTRA,
                "${listed.absolutePath}\n\n/does/not/exist.exe\n${notes.absolutePath}\n${layout.absolutePath}\n",
            )
        }

        assertEquals(listOf(listed, layout), RedistInstallerExtractor.installersFor(container, gameDir))
    }

    @Test
    fun installersForUsesSteamInstallScriptSteps() {
        val gameDir = temporary.newFolder("steamgame")
        val redist = File(gameDir, "_CommonRedist/vcredist/2019").apply { mkdirs() }
        val x64 = File(redist, "VC_redist.x64.exe").apply { writeBytes(ByteArray(4)) }
        File(redist, "unrelated.exe").writeBytes(ByteArray(4))
        File(redist, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VC Redist 2019 x64"
                    {
                        "HasRunKey" "HKEY_LOCAL_MACHINE\\Software\\Valve\\Steam\\Apps\\CommonRedist\\vcredist\\2019"
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2019\\VC_redist.x64.exe"
                        "command 1" "/q /norestart"
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(x64, File(redist, "unrelated.exe")),
            RedistInstallerExtractor.installersFor(Container("STEAM_3556750"), gameDir),
        )
    }

    @Test
    fun installersForExpandsCmdStepsToTheInstallersTheyRun() {
        val gameDir = temporary.newFolder("hatintime")
        val redist = File(gameDir, "_CommonRedist/vcredist/2022").apply { mkdirs() }
        val x86 = File(redist, "VC_redist.x86.exe").apply { writeBytes(ByteArray(4)) }
        val x64 = File(redist, "VC_redist.x64.exe").apply { writeBytes(ByteArray(4)) }
        File(redist, "Microsoft Visual C++ 2022 x86.cmd").writeText("start /w \"\" \"%~dp0\\vc_redist.X86.exe\" /q /norestart\r\n")
        File(redist, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "VC 2022 x86"
                    {
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\vcredist\\2022\\Microsoft Visual C++ 2022 x86.cmd"
                    }
                }
            }
            """.trimIndent(),
        )
        val dotNet = File(gameDir, "_CommonRedist/DotNet/4.8").apply { mkdirs() }
        File(dotNet, "noop.cmd").writeText("@echo off\r\nexit /b 0\r\n")
        File(dotNet, "installscript.vdf").writeText(
            """
            "InstallScript"
            {
                "Run Process"
                {
                    "noop"
                    {
                        "process 1" "%INSTALLDIR%\\_CommonRedist\\DotNet\\4.8\\noop.cmd"
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf(x86, x64), RedistInstallerExtractor.installersFor(Container("STEAM_253230"), gameDir))
    }

    @Test
    fun scriptTargetsResolvesDirectoryVariableRelativePathsAndSiblings() {
        val dir = temporary.newFolder("redist")
        val nested = File(dir, "Sub").apply { mkdirs() }
        val named = File(nested, "Setup.MSI").apply { writeBytes(ByteArray(4)) }
        val direct = File(dir, "VC_redist.x64.exe").apply { writeBytes(ByteArray(4)) }
        val sibling = File(dir, "another.msi").apply { writeBytes(ByteArray(4)) }
        File(dir, "notes.txt").writeText("x")
        val script = File(dir, "install.BAT").apply {
            writeText(
                listOf(
                    "\"%~dp0VC_redist.x64.exe\" /q",
                    "msiexec /i sub\\setup.msi /qn",
                    "C:\\Windows\\system32\\cmd.exe",
                    "%TEMP%\\other.exe",
                    "..\\outside.exe",
                    "missing.exe",
                ).joinToString("\r\n"),
            )
        }

        assertEquals(listOf(direct, named, direct, sibling), RedistInstallerExtractor.scriptTargets(script))
        assertEquals(listOf(direct, named, sibling), RedistInstallerExtractor.expandInstallers(listOf(script, direct)))
    }

    @Test
    fun installersForFallsBackToTheSteamworksLayout() {
        val gameDir = temporary.newFolder("gog")
        val x86 = File(gameDir, "_CommonRedist/vcredist/2010").apply { mkdirs() }.let { File(it, "vcredist_x86.exe") }
        x86.writeBytes(ByteArray(4))
        val x64 = File(gameDir, "_CommonRedist/vcredist/2022").apply { mkdirs() }.let { File(it, "VC_redist.x64.exe") }
        x64.writeBytes(ByteArray(4))
        File(gameDir, "_CommonRedist/vcredist/2022/readme.txt").writeText("x")

        assertEquals(listOf(x86, x64), RedistInstallerExtractor.installersFor(Container("GOG_42"), gameDir))
        assertEquals(emptyList<File>(), RedistInstallerExtractor.installersFor(Container("GOG_42"), null))
    }

    private fun place(installers: List<File>, family: String, members: Map<String, ByteArray>): List<String> =
        RedistInstallerExtractor.place(
            installers,
            family,
            allowedByFamily(),
            windowsDir,
            workDir,
            HashMap(),
        ) { cabinet, dir, maxBytes -> unpack(members, cabinet, dir, maxBytes) }

    private fun allowedByFamily(): Map<String, Set<String>> =
        RedistInstallerExtractor.FAMILY_MAJOR_VERSIONS.keys.associateWith { RedistInstallerExtractor.allowedDlls(components, it) }

    private fun unpack(members: Map<String, ByteArray>, cabinet: File, dir: File, maxBytes: Long): List<Pair<String, File>> {
        assertTrue(maxBytes > 0)
        dir.mkdirs()
        val names = CabArchive.locateAll(cabinet).first().entryNames
        return names.mapIndexedNotNull { index, name ->
            members[name]?.let { bytes -> name to File(dir, "entry-$index").apply { writeBytes(bytes) } }
        }
    }

    @Test
    fun normalizesCabinetMemberNames() {
        assertEquals("msvcr100", RedistInstallerExtractor.normalizeMemberName("F_CENTRAL_msvcr100_x86"))
        assertEquals("msvcp140", RedistInstallerExtractor.normalizeMemberName("F_CENTRAL_msvcp140_x64"))
        assertEquals("msvcp140", RedistInstallerExtractor.normalizeMemberName("msvcp140.dll_amd64"))
        assertEquals("vcomp140", RedistInstallerExtractor.normalizeMemberName("vcomp140.dll_x86"))
        assertEquals("msvcp80", RedistInstallerExtractor.normalizeMemberName("ul_msvcp80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E"))
        assertEquals("msvcr90", RedistInstallerExtractor.normalizeMemberName("nosxs_msvcr90.dll"))
        assertEquals("atl100", RedistInstallerExtractor.normalizeMemberName("F_atl100_amd64"))
        assertEquals("msvcr120", RedistInstallerExtractor.normalizeMemberName("msvcr120.dll"))
    }

    @Test
    fun identifiesClrBuildsAndMangledMembersInFamilyOrder() {
        val clr = SyntheticPe.build(
            PeVersionReader.MACHINE_I386,
            intArrayOf(10, 0, 40219, 1),
            mapOf("InternalName" to "msvcr100_clr0400.dll", "OriginalFilename" to "msvcr100_clr0400.dll"),
        )
        assertEquals(
            listOf("msvcr100_clr0400", "msvcr100"),
            RedistInstallerExtractor.nameCandidates(PeVersionReader.read(clr)!!, "F_CENTRAL_msvcr100_x86"),
        )
        val unnamed = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(8, 0, 50727, 762))

        val placed2010 = place(
            listOf(installer("vcredist_x86.exe", cabinet("vc_red.cab"))),
            "vcrun2010",
            mapOf("vc_red.cab" to cabinet("F_CENTRAL_msvcr100_x86"), "F_CENTRAL_msvcr100_x86" to clr),
        )
        val placed2005 = place(
            listOf(installer("vcredist2005_x86.exe", cabinet("vcredist.msi"))),
            "vcrun2005",
            mapOf(
                "vcredist.msi" to ByteArray(64) { 0x20 } + cabinet("ul_msvcp80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E"),
                "ul_msvcp80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E" to unnamed,
            ),
        )

        assertEquals(listOf("msvcr100"), placed2010)
        assertArrayEquals(clr, File(windowsDir, "syswow64/msvcr100.dll").readBytes())
        assertEquals(listOf("msvcp80"), placed2005)
        assertArrayEquals(unnamed, File(windowsDir, "syswow64/msvcp80.dll").readBytes())
    }

    @Test
    fun aFailingCabinetDoesNotStopTheOtherCabinetsOfTheInstaller() {
        val x64 = dll(PeVersionReader.MACHINE_AMD64, 14, "msvcp140.dll")
        val installer = installer("VC_redist.x64.exe", cabinet("patch") + ByteArray(8) + cabinet("a10") + ByteArray(8) + cabinet("broken"))
        val members = mapOf("a10" to cabinet("msvcp140.dll_amd64"), "msvcp140.dll_amd64" to x64, "broken" to cabinet("lzx"))

        val placed = RedistInstallerExtractor.place(
            listOf(installer),
            "vcrun2022",
            allowedByFamily(),
            windowsDir,
            workDir,
            HashMap(),
        ) { cabinet, dir, maxBytes ->
            val names = CabArchive.locateAll(cabinet).first().entryNames
            if ("patch" in names || "lzx" in names) throw java.io.IOException("Unsupported LZX window (-25)")
            unpack(members, cabinet, dir, maxBytes)
        }

        assertEquals(listOf("msvcp140"), placed)
        assertArrayEquals(x64, File(windowsDir, "system32/msvcp140.dll").readBytes())
    }

    @Test
    fun scriptSiblingsAreOnlyAddedWhenTheScriptNamesAnInstallerOrTheyLookLikeOne() {
        val gameDir = temporary.newFolder("dow")
        File(gameDir, "W40k.exe").writeBytes(ByteArray(4))
        File(gameDir, "ObjectEditor.exe").writeBytes(ByteArray(4))
        val bat = File(gameDir, "installscript.bat").apply {
            writeText("reg add \"HKCU\\Software\\BugSplat\" /v Enabled /t REG_DWORD /d 1 /f\r\n")
        }
        assertEquals(emptyList<File>(), RedistInstallerExtractor.scriptTargets(bat))

        val vcredist = File(gameDir, "VCREDIST_x86.exe").apply { writeBytes(ByteArray(4)) }
        val openal = File(gameDir, "oalinst.exe").apply { writeBytes(ByteArray(4)) }
        val msi = File(gameDir, "Runtime.MSI").apply { writeBytes(ByteArray(4)) }
        File(gameDir, "PhysXEditorTool.dll").writeBytes(ByteArray(4))

        assertEquals(listOf(msi, vcredist, openal), RedistInstallerExtractor.scriptTargets(bat))
        assertTrue(RedistInstallerExtractor.looksLikeInstaller(File("PhysX_9.21.0713_SystemSoftware.msi")))
        assertTrue(RedistInstallerExtractor.looksLikeInstaller(File("DXSETUP.exe")))
        assertTrue(RedistInstallerExtractor.looksLikeInstaller(File("vc_redist.x64.exe")))
        assertFalse(RedistInstallerExtractor.looksLikeInstaller(File("W40k.exe")))
    }

    @Test
    fun skipsDotNetInstallersForVcrunFamilies() {
        var unpacked = 0
        val dotNet = installer(
            "NDP452-KB2901907-x86-x64-AllOS-ENU.exe",
            SyntheticPe.build(
                PeVersionReader.MACHINE_I386,
                intArrayOf(4, 5, 51209, 34209),
                mapOf("ProductName" to "Microsoft .NET Framework 4.5.2", "FileDescription" to "Setup Installer"),
            ) + cabinet("payload"),
        )
        val framework = installer(
            "dotNetFx40_Full_x86_x64.exe",
            SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(0, 0, 0, 0), mapOf("FileDescription" to "Microsoft Framework Installer")) +
                cabinet("payload"),
        )

        val placed = RedistInstallerExtractor.place(listOf(dotNet, framework), "vcrun2010", allowedByFamily(), windowsDir, workDir, HashMap()) { cabinet, dir, maxBytes ->
            unpacked++
            unpack(mapOf("payload" to dll(PeVersionReader.MACHINE_I386, 10, "msvcr100.dll")), cabinet, dir, maxBytes)
        }

        assertEquals(emptyList<String>(), placed)
        assertEquals(0, unpacked)
    }

    @Test
    fun anInstallerIsUnpackedOnceAcrossFamilies() {
        val msvcr100 = dll(PeVersionReader.MACHINE_I386, 10, "msvcr100.dll")
        val msvcp140 = dll(PeVersionReader.MACHINE_I386, 14, "msvcp140.dll")
        val members = mapOf("a" to msvcr100, "b" to msvcp140, "c" to dll(PeVersionReader.MACHINE_I386, 14, "W40k.exe"))
        val bundle = installer("redist/all_in_one.exe", cabinet("a", "b", "c"))
        val cache = HashMap<String, List<RedistInstallerExtractor.InventoryEntry>>()
        var unpacked = 0
        val unpacker = RedistInstallerExtractor.CabinetUnpacker { cabinet, dir, maxBytes ->
            unpacked++
            unpack(members, cabinet, dir, maxBytes)
        }

        val placed2010 = RedistInstallerExtractor.place(listOf(bundle), "vcrun2010", allowedByFamily(), windowsDir, workDir, cache, unpacker)
        val placed2022 = RedistInstallerExtractor.place(listOf(bundle), "vcrun2022", allowedByFamily(), windowsDir, workDir, cache, unpacker)

        assertEquals(listOf("msvcr100"), placed2010)
        assertEquals(listOf("msvcp140"), placed2022)
        assertEquals(1, unpacked)
        assertEquals(2, cache.values.single().size)
        assertArrayEquals(msvcp140, File(windowsDir, "syswow64/msvcp140.dll").readBytes())
        assertFalse(workDir.exists())
    }

    @Test
    fun familiesOfReadsEachInstallersOwnVersionWithoutUnpacking() {
        fun exe(name: String, major: Int, product: String) = installer(
            "families/$name",
            SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(major, 0, 1, 0), mapOf("ProductName" to product)) + cabinet("payload"),
        )
        val installers = listOf(
            exe("vcredist_2005_x86.exe", 8, "Microsoft Visual C++ 2005 Redistributable"),
            exe("vcredist_2008_x86.exe", 9, "Microsoft Visual C++ 2008 Redistributable"),
            exe("vcredist_x86_vs2010sp1.exe", 10, "Microsoft Visual C++ 2010 x86 Redistributable"),
            exe("vcredist_2012_x64.exe", 11, "Microsoft Visual C++ 2012 Redistributable (x64)"),
            exe("vcredist_2013_x64.exe", 12, "Microsoft Visual C++ 2013 Redistributable (x64)"),
            exe("VC_redist.x64.exe", 14, "Microsoft Visual C++ 2015-2022 Redistributable (x64)"),
            exe("VC_redist.x86.exe", 14, "Microsoft Visual C++ 2015-2022 Redistributable (x86)"),
        )

        assertEquals(
            setOf("vcrun2005", "vcrun2008", "vcrun2010", "vcrun2012", "vcrun2013", "vcrun2022"),
            RedistInstallerExtractor.familiesOf(installers),
        )
    }

    @Test
    fun familiesOfSkipsNonVcInstallers() {
        val skipped = listOf(
            installer("ndp48.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(4, 8, 1, 0), mapOf("ProductName" to "Microsoft .NET Framework"))),
            installer("NDP452.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(12, 0, 0, 0), mapOf("FileDescription" to "Microsoft .NET Framework 4.5.2 Setup"))),
            installer("DXSETUP.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 29, 1974, 0), mapOf("ProductName" to "Microsoft DirectX for Windows"))),
            installer("PhysX.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 13, 0, 0), mapOf("ProductName" to "NVIDIA PhysX"))),
            installer("oalinst.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(2, 0, 7, 0), mapOf("ProductName" to "OpenAL"))),
            installer("game.exe", SyntheticPe.build(PeVersionReader.MACHINE_AMD64, intArrayOf(1, 0, 0, 0), mapOf("ProductName" to "Game"))),
            installer("noversion.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386)),
            installer("runtime.msi", ByteArray(64)),
            File(temporary.root, "missing.exe"),
        )

        assertEquals(emptySet<String>(), RedistInstallerExtractor.familiesOf(skipped))
    }

    @Test
    fun familiesOfLooksInsideSelfExtractorStubs() {
        val stub = SyntheticPe.build(
            PeVersionReader.MACHINE_I386,
            intArrayOf(6, 0, 2900, 2180),
            mapOf("FileDescription" to "Win32 Cabinet Self-Extractor", "ProductName" to "Microsoft Windows Operating System"),
        )
        val setup2005 = SyntheticPe.build(
            PeVersionReader.MACHINE_I386,
            intArrayOf(2, 0, 50727, 42),
            mapOf("FileDescription" to "IExpress Setup", "ProductName" to "Microsoft Visual C++ 2005 Redistributable"),
        )
        val setup2008 = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 0, 30729, 6161), mapOf("ProductName" to "Setup"))
        val directx = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(9, 29, 1974, 0), mapOf("ProductName" to "Microsoft DirectX for Windows"))
        val members = mapOf("VCREDI~1.EXE" to setup2005, "inner2008.exe" to setup2008, "dx.exe" to directx, "readme.txt" to "x".toByteArray())
        val probe = File(temporary.root, "probe")
        var unpacked = 0
        val unpacker = RedistInstallerExtractor.CabinetUnpacker { cabinet, dir, maxBytes ->
            unpacked++
            unpack(members, cabinet, dir, maxBytes)
        }

        assertEquals(
            setOf("vcrun2005"),
            RedistInstallerExtractor.familiesOf(listOf(installer("vcredist_2005_x86.exe", stub + cabinet("readme.txt", "VCREDI~1.EXE"))), probe, unpacker),
        )
        assertEquals(
            setOf("vcrun2008"),
            RedistInstallerExtractor.familiesOf(listOf(installer("stub2008.exe", stub + cabinet("dx.exe", "inner2008.exe"))), probe, unpacker),
        )
        assertEquals(
            emptySet<String>(),
            RedistInstallerExtractor.familiesOf(listOf(installer("dxstub.exe", stub + cabinet("dx.exe", "readme.txt"))), probe, unpacker),
        )
        assertEquals(3, unpacked)
        assertFalse(probe.exists())

        val vc2010 = installer("vcredist_x86_vs2010sp1.exe", SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(10, 0, 40219, 325)) + cabinet("dx.exe"))
        assertEquals(setOf("vcrun2010"), RedistInstallerExtractor.familiesOf(listOf(vc2010), probe, unpacker))
        assertEquals(3, unpacked)
    }

    @Test
    fun placeDescendsFromAStubThroughTheInnerExeAndMsiToTheRuntime() {
        val stub = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(6, 0, 2900, 2180), mapOf("FileDescription" to "Win32 Cabinet Self-Extractor"))
        val setup = SyntheticPe.build(
            PeVersionReader.MACHINE_I386,
            intArrayOf(2, 0, 50727, 42),
            mapOf("ProductName" to "Microsoft Visual C++ 2005 Redistributable", "OriginalFilename" to "setup.exe"),
        ) + cabinet("vcredist.msi", "vcredis1.cab")
        val msi = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte()) + ByteArray(60) +
            cabinet("manifest.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E") + ByteArray(12) +
            cabinet("ul_msvcp80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E", "msvcr80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E")
        val msvcp80 = dll(PeVersionReader.MACHINE_I386, 8, "MSVCP80.DLL")
        val msvcr80 = dll(PeVersionReader.MACHINE_I386, 8, "MSVCR80.DLL")
        val members = mapOf(
            "VCREDI~1.EXE" to setup,
            "vcredist.msi" to msi,
            "vcredis1.cab" to cabinet("FL_msdia71_dll"),
            "FL_msdia71_dll" to dll(PeVersionReader.MACHINE_I386, 7, "MSDIA71.DLL"),
            "manifest.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E" to "<assembly/>".toByteArray(),
            "ul_msvcp80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E" to msvcp80,
            "msvcr80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E" to msvcr80,
        )

        val placed = place(listOf(installer("vcredist_2005_x86.exe", stub + cabinet("VCREDI~1.EXE"))), "vcrun2005", members)

        assertEquals(listOf("msvcp80", "msvcr80"), placed)
        assertArrayEquals(msvcp80, File(windowsDir, "syswow64/msvcp80.dll").readBytes())
        assertArrayEquals(msvcr80, File(windowsDir, "syswow64/msvcr80.dll").readBytes())
        assertFalse(workDir.exists())
    }

    @Test
    fun unrealLayoutCollectsOnlyX64AndX86Installers() {
        val gameDir = temporary.newFolder("ue")
        val redist = File(gameDir, "engine/Extras/REDIST/en-US").apply { mkdirs() }
        val x64 = File(redist, "VC_redist.x64.exe").apply { writeBytes(ByteArray(4)) }
        File(redist, "vc_redist.arm64.exe").writeBytes(ByteArray(4))
        File(redist, "readme.txt").writeText("x")
        File(gameDir, "Engine/Binaries/Win64").mkdirs()

        assertEquals(listOf(x64), RedistInstallerExtractor.unrealLayoutInstallers(gameDir))

        val prereq = File(redist, "UE4PrereqSetup_x64.exe").apply { writeBytes(ByteArray(4)) }
        val ue5 = File(redist, "UEPrereqSetup_x64.exe").apply { writeBytes(ByteArray(4)) }
        assertEquals(listOf(prereq, ue5, x64), RedistInstallerExtractor.unrealLayoutInstallers(gameDir))
        assertEquals(emptyList<File>(), RedistInstallerExtractor.unrealLayoutInstallers(temporary.newFolder("empty")))
    }

    @Test
    fun storeGamesWithoutRedistMetadataFallBackToTheUnrealInstallers() {
        val gameDir = temporary.newFolder("uegame")
        val x64 = File(gameDir, "Engine/Extras/Redist/en-us/VC_redist.x64.exe").apply {
            parentFile!!.mkdirs()
            writeBytes(
                SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(14, 38, 33135, 0), mapOf("ProductName" to "Microsoft Visual C++ 2015-2022 Redistributable (x64)")),
            )
        }
        File(gameDir, "Engine/Extras/Redist/en-us/vc_redist.arm64.exe").writeBytes(ByteArray(4))

        val plan = RedistComponents.planFor(app.gamenative.data.GameSource.CUSTOM_GAME, gameDir)!!

        assertEquals(listOf(x64), plan.installers)
        assertEquals(setOf("vcrun2022"), RedistInstallerExtractor.familiesOf(plan.installers, File(temporary.root, "probe")) { _, _, _ -> emptyList() })
        assertEquals(null, RedistComponents.planFor(app.gamenative.data.GameSource.STEAM, gameDir))

        val withRedist = temporary.newFolder("withredist")
        val vc2019 = File(withRedist, "_CommonRedist/vcredist/2019/VC_redist.x64.exe").apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(4))
        }
        File(withRedist, "Engine/Extras/Redist/en-us").mkdirs()
        File(withRedist, "Engine/Extras/Redist/en-us/VC_redist.x64.exe").writeBytes(ByteArray(4))
        assertEquals(listOf(vc2019), RedistComponents.planFor(app.gamenative.data.GameSource.CUSTOM_GAME, withRedist)!!.installers)
    }

    @Test
    fun familiesOfProbesInnerInstallersBeforeOtherPeMembers() {
        val stub = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(6, 0, 2900, 2180), mapOf("FileDescription" to "Win32 Cabinet Self-Extractor"))
        val resource = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(6, 0, 1, 0), mapOf("ProductName" to "Setup resources"))
        val setup = SyntheticPe.build(
            PeVersionReader.MACHINE_I386,
            intArrayOf(2, 0, 50727, 762),
            mapOf("ProductName" to "Microsoft Visual C++ 2005 Redistributable"),
        )
        val members = mapOf(
            "install.res.1033.dll" to resource,
            "install.res.1041.dll" to resource,
            "install.res.1042.dll" to resource,
            "eula.1033.txt" to "eula".toByteArray(),
            "vcredist.msi" to ByteArray(64),
            "VCREDI~3.EXE" to setup,
        )
        val stubFile = installer(
            "_CommonRedist/vcredist/2005/vcredist_x86.exe",
            stub + cabinet("install.res.1033.dll", "install.res.1041.dll", "install.res.1042.dll", "eula.1033.txt", "vcredist.msi", "VCREDI~3.EXE"),
        )

        val families = RedistInstallerExtractor.familiesOf(listOf(stubFile), File(temporary.root, "probe")) { cabinet, dir, maxBytes ->
            unpack(members, cabinet, dir, maxBytes)
        }

        assertEquals(setOf("vcrun2005"), families)
    }

    @Test
    fun aStubTreeIsUnpackedOnceAcrossFamiliesAndIdenticalCopies() {
        val stub = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(6, 0, 2900, 2180), mapOf("FileDescription" to "Win32 Cabinet Self-Extractor"))
        val setup = SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(2, 0, 50727, 42), mapOf("ProductName" to "Microsoft Visual C++ 2005 Redistributable")) +
            cabinet("vcredist.msi")
        val msi = ByteArray(64) + cabinet("ul_msvcr80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E")
        val msvcr80 = dll(PeVersionReader.MACHINE_I386, 8, "MSVCR80.DLL")
        val members = mapOf(
            "VCREDI~1.EXE" to setup,
            "vcredist.msi" to msi,
            "ul_msvcr80.dll.98CB24AD_52FB_DB5F_FF1F_C8B3B9A1E18E" to msvcr80,
        )
        val bytes = stub + cabinet("VCREDI~1.EXE")
        val root = installer("vcredist/vcredist_x86.exe", bytes)
        val copy = installer("_CommonRedist/vcredist/2005/vcredist_x86.exe", bytes)
        val cache = HashMap<String, List<RedistInstallerExtractor.InventoryEntry>>()
        var unpacked = 0
        val unpacker = RedistInstallerExtractor.CabinetUnpacker { cabinet, dir, maxBytes ->
            unpacked++
            unpack(members, cabinet, dir, maxBytes)
        }

        val placed2010 = RedistInstallerExtractor.place(listOf(root, copy), "vcrun2010", allowedByFamily(), windowsDir, workDir, cache, unpacker)
        val afterFirstFamily = unpacked
        val placed2005 = RedistInstallerExtractor.place(listOf(root, copy), "vcrun2005", allowedByFamily(), windowsDir, workDir, cache, unpacker)

        assertEquals(emptyList<String>(), placed2010)
        assertEquals(listOf("msvcr80"), placed2005)
        assertEquals(3, afterFirstFamily)
        assertEquals(afterFirstFamily, unpacked)
        assertEquals(1, cache.size)
        assertArrayEquals(msvcr80, File(windowsDir, "syswow64/msvcr80.dll").readBytes())
    }

    @Test
    fun vcRuntimeVersionIsTheHighestVc14Version() {
        fun version(major: Long, minor: Long, build: Long) = (major shl 48) or (minor shl 32) or (build shl 16)

        assertEquals(
            SteamInstallScriptRegistry.VcRuntimeVersion(14, 44, 35211),
            RedistInstallerExtractor.vcRuntimeVersionFor(
                listOf(version(14, 42, 34438), version(14, 0, 24215), version(14, 44, 35211), version(12, 0, 40660)),
            ),
        )
        assertEquals(null, RedistInstallerExtractor.vcRuntimeVersionFor(listOf(version(10, 0, 40219))))
        assertEquals(null, RedistInstallerExtractor.vcRuntimeVersionFor(emptyList()))
    }

    @Test
    fun recordVcRuntimeUsesThePlacedDllsAndTheInstallersSeen() {
        RedistInstallerExtractor.clearCache()
        val prefixDir = temporary.newFolder("prefix")
        val systemReg = File(prefixDir, "system.reg").apply { writeText("WINE REGISTRY Version 2\n\n") }
        val prefixWindows = File(prefixDir, "drive_c/windows")
        File(prefixWindows, "system32").mkdirs()
        File(prefixWindows, "syswow64").mkdirs()
        File(prefixWindows, "system32/msvcp140.dll").writeBytes(dll(PeVersionReader.MACHINE_AMD64, 14, "msvcp140.dll", minor = 42))
        File(prefixWindows, "syswow64/vcruntime140.dll").writeBytes(dll(PeVersionReader.MACHINE_I386, 14, "vcruntime140.dll", minor = 40))
        val key = "Software\\Wow6432Node\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\arm64"

        RedistInstallerExtractor.recordVcRuntime("vcrun2010", prefixWindows, prefixDir)
        WineRegistryEditor(systemReg).use { assertEquals(null, it.getDwordValue(key, "Minor")) }

        RedistInstallerExtractor.recordVcRuntime("vcrun2022", prefixWindows, prefixDir)
        WineRegistryEditor(systemReg).use { assertEquals("v14.42.1.00", it.getStringValue(key, "Version")) }

        val bundle = installer(
            "Engine/Extras/Redist/en-us/VC_redist.x64.exe",
            SyntheticPe.build(PeVersionReader.MACHINE_I386, intArrayOf(14, 42, 34438, 0), mapOf("ProductName" to "Microsoft Visual C++ 2015-2022 Redistributable (x64)")),
        )
        assertEquals(setOf("vcrun2022"), RedistInstallerExtractor.familiesOf(listOf(bundle), File(temporary.root, "probe")) { _, _, _ -> emptyList() })
        RedistInstallerExtractor.recordVcRuntime("vcrun2022", prefixWindows, prefixDir)
        WineRegistryEditor(systemReg).use { editor ->
            assertEquals("v14.42.34438.00", editor.getStringValue(key, "Version"))
            assertEquals(34438, editor.getDwordValue("Software\\Microsoft\\VisualStudio\\14.0\\VC\\Runtimes\\x64", "Bld"))
        }
        RedistInstallerExtractor.clearCache()
    }

    private fun candidate(name: String, machine: Int, major: Int, minor: Int) =
        RedistInstallerExtractor.Candidate(ByteArray(0), name, machine, (major.toLong() shl 48) or (minor.toLong() shl 32))

    private fun dll(machine: Int, major: Int, originalFilename: String, minor: Int = 0, hybrid: Boolean = false): ByteArray =
        SyntheticPe.build(machine, intArrayOf(major, minor, 1, 0), mapOf("OriginalFilename" to originalFilename), hybrid)

    private fun installer(relativePath: String, bytes: ByteArray): File =
        File(temporary.root, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

    private fun cabinet(vararg entryNames: String): ByteArray {
        val table = ByteArrayOutputStream()
        entryNames.forEach { name ->
            table.write(ByteArray(16))
            table.write(name.toByteArray(Charsets.US_ASCII))
            table.write(0)
        }
        val tableBytes = table.toByteArray()
        val payload = ByteArray(32) { 0x11 }
        val header = ByteArray(36)
        "MSCF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        putU32(header, 8, 36 + tableBytes.size + payload.size)
        putU32(header, 16, 36)
        header[24] = 3
        header[25] = 1
        header[26] = 1
        header[28] = entryNames.size.toByte()
        return header + tableBytes + payload
    }

    private fun putU32(bytes: ByteArray, index: Int, value: Int) {
        bytes[index] = value.toByte()
        bytes[index + 1] = (value ushr 8).toByte()
        bytes[index + 2] = (value ushr 16).toByte()
        bytes[index + 3] = (value ushr 24).toByte()
    }
}
