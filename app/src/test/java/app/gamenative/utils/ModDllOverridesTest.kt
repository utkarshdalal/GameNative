package app.gamenative.utils

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModDllOverridesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun file(path: String): File = File(temp.root, path).apply {
        parentFile.mkdirs()
        writeText("test")
    }

    @Test fun mergesWithoutChangingExistingEntries() {
        val original = "icu=n;xaudio2_7=native,builtin"
        assertEquals("$original;dinput8=n,b;winhttp=n,b", ModDllOverrides.merge(original, listOf("dinput8", "winhttp")).value)
    }

    @Test fun preservesGroupedMixedCaseAndExtensionChoices() {
        val original = "DINPUT8.dll,winhttp=b;version="
        assertEquals(original, ModDllOverrides.merge(original, listOf("dinput8", "winhttp", "version")).value)
    }

    @Test fun preservesWildcardsAndPathSpecificChoices() {
        for (original in listOf("*=b", "*dinput8=b", "C:\\Game\\dinput8.dll=")) {
            assertEquals(original, ModDllOverrides.merge(original, listOf("dinput8")).value)
        }
    }

    @Test fun nativeAliasesAndDuplicateNamesAreNotRewritten() {
        val original = "dinput8=native,builtin;dinput8=b"
        assertEquals(original, ModDllOverrides.merge(original, listOf("dinput8", "dinput8")).value)
    }

    @Test fun malformedSettingsAreLeftAlone() {
        for (original in listOf("dinput8", "icu=n winhttp=b", "winhttp=surprise")) {
            assertEquals(original, ModDllOverrides.merge(original, listOf("dinput8")).value)
        }
    }

    @Test fun emptyValueAndTrailingSeparatorAreSupported() {
        val result = ModDllOverrides.merge("", listOf("dinput8", "dinput8"))
        assertEquals("dinput8=n,b", result.value)
        assertEquals(listOf("dinput8"), result.added)
        assertEquals("icu=n;winhttp=n,b", ModDllOverrides.merge("icu=n;", listOf("winhttp")).value)
    }

    @Test fun detectsMixedCaseBesideExecutableOnly() {
        val exe = file("Game/Bin/GAME.exe")
        file("Game/Bin/DINPUT8.DLL")
        file("Game/winhttp.dll")
        file("Game/Bin/backup/winhttp.dll")
        assertEquals(listOf("dinput8"), ModDllOverrides.detect(exe))
    }

    @Test fun broadProxiesRequireFrameworkEvidenceAndGraphicsAreExcluded() {
        val exe = file("Game/game.exe")
        listOf("version", "winmm", "dsound", "dxgi", "d3d11").forEach { file("Game/$it.dll") }
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
        file("Game/doorstop_config.ini")
        assertEquals(listOf("version", "winmm", "dsound"), ModDllOverrides.detect(exe))
    }

    @Test fun asiAndFrameworkDirectoriesProvideEvidence() {
        val exe = file("Game/game.exe")
        file("Game/version.dll")
        val asi = file("Game/plugin.ASI")
        assertEquals(listOf("version"), ModDllOverrides.detect(exe))
        asi.delete()
        File(exe.parentFile, "MelonLoader").mkdir()
        assertEquals(listOf("version"), ModDllOverrides.detect(exe))
    }

    @Test fun removingProxyRemovesAutomaticEntryOnNextLaunch() {
        val exe = file("Game/game.exe")
        val dll = file("Game/winhttp.dll")
        val saved = "icu=n"
        assertEquals("icu=n;winhttp=n,b", ModDllOverrides.merge(saved, ModDllOverrides.detect(exe)).value)
        assertTrue(dll.delete())
        assertEquals(saved, ModDllOverrides.merge(saved, ModDllOverrides.detect(exe)).value)
    }

    @Test fun asiPluginsInSupportedSubdirectoriesProvideEvidence() {
        for (folder in listOf("Scripts", "pLuGiNs", "UPDATE")) {
            val exe = file("$folder/game.exe")
            val proxies = listOf("version", "winmm", "dsound")
            proxies.forEach { file("$folder/$it.dll") }
            val plugin = file("$folder/$folder/Fix.ASI")
            assertEquals(folder, proxies, ModDllOverrides.detect(exe))
            assertTrue(plugin.delete())
            assertTrue("Empty $folder must not count as loader evidence", ModDllOverrides.detect(exe).isEmpty())
        }
    }

    @Test fun pluginDirectoriesRequireActualAsiFiles() {
        val exe = file("Game/game.exe")
        file("Game/version.dll")
        for (folder in listOf("scripts", "plugins", "update")) {
            file("Game/$folder/readme.txt")
            File(exe.parentFile, "$folder/directory.asi").mkdir()
        }
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
    }

    @Test fun unrelatedAndNestedPluginDirectoriesDoNotProvideEvidence() {
        val exe = file("Game/game.exe")
        file("Game/version.dll")
        file("Game/backup/Fix.asi")
        file("Game/scripts/nested/Fix.asi")
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
    }

    @Test fun subdirectoryEvidenceDoesNotDetectDllsOutsideExecutableDirectory() {
        val exe = file("Game/game.exe")
        file("Game/scripts/Fix.asi")
        file("Game/scripts/version.dll")
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
    }

    @Test fun ignoresMissingExecutablesAndDllNamedDirectories() {
        assertTrue(ModDllOverrides.detect(File(temp.root, "absent.exe")).isEmpty())
        val exe = file("Game/game.exe")
        File(exe.parentFile, "winhttp.dll").mkdir()
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
    }

    @Test fun resolvesNestedRelativeAndMappedDrivePathsWithWindowsCase() {
        val exe = file("Game/Bin/Game.exe")
        val drives = mapOf("A" to File(temp.root, "Game"), "D" to File(temp.root, "Game"))
        for (path in listOf("bin/game.EXE", ".\\Bin\\Game.exe", "d:\\bin\\game.exe", "\"A:\\Bin\\Game.exe\"")) {
            assertEquals(exe.canonicalFile, ModDllOverrides.resolveExecutable(path, drives)?.canonicalFile)
        }
    }

    @Test fun resolvesPrefixDriveC() {
        val exe = file("prefix/drive_c/Games/Game/game.exe")
        val resolved = ModDllOverrides.resolveExecutable("C:\\Games\\Game\\game.exe", emptyMap(), File(temp.root, "prefix/drive_c"))
        assertEquals(exe.canonicalFile, resolved)
    }

    @Test fun rejectsUnknownDrivesTraversalScriptsAndSystemTools() {
        file("Game/game.exe")
        val drives = mapOf("A" to File(temp.root, "Game"))
        for (path in listOf("Z:\\game.exe", "../Game/game.exe", "launch.bat", "game.exe -arg", "/game.exe", "A:game.exe")) {
            assertNull(path, ModDllOverrides.resolveExecutable(path, drives))
        }
        file("prefix/Windows/System32/tool.exe")
        assertNull(ModDllOverrides.resolveExecutable("C:\\Windows\\System32\\tool.exe", emptyMap(), File(temp.root, "prefix")))
    }

    @Test fun preservesRegistryChoicesExceptGameNativeInputDefault() {
        val registry = ModDllOverrides.RegistryOverrides(
            global = mapOf("dinput8" to "builtin,native", "winhttp" to "builtin"),
            app = mapOf("version" to ""),
        )
        val result = ModDllOverrides.merge("icu=n", listOf("dinput8", "winhttp", "version"), registry)
        assertEquals(listOf("dinput8"), result.added)
        assertEquals(listOf("winhttp", "version"), result.preserved)
    }

    @Test fun appRegistryAndEnvironmentWinOverDefaultException() {
        val registry = ModDllOverrides.RegistryOverrides(mapOf("dinput8" to "b,n"), mapOf("dinput8" to "b,n"))
        assertEquals("", ModDllOverrides.merge("", listOf("dinput8"), registry).value)
        assertEquals("dinput8=b", ModDllOverrides.merge("dinput8=b", listOf("dinput8"), ModDllOverrides.RegistryOverrides()).value)
    }

    @Test fun readsOnlyTargetRegistrySectionsWithoutModifyingFile() {
        val reg = file("user.reg")
        val original = """
            WINE REGISTRY Version 2
            [Software\\Wine\\DllOverrides] 1
            "dinput8"="builtin,native"
            "*winhttp"=""
            [Software\\Wine\\AppDefaults\\game.exe\\DllOverrides] 1
            "version"="builtin"
            [Software\\Wine\\AppDefaults\\other.exe\\DllOverrides] 1
            "dsound"="builtin"
        """.trimIndent()
        reg.writeText(original)
        val registry = ModDllOverrides.readRegistry(reg, "GAME.exe")
        assertFalse(registry.preserves("dinput8"))
        assertTrue(registry.preserves("winhttp"))
        assertTrue(registry.preserves("version"))
        assertFalse(registry.preserves("dsound"))
        assertEquals(original, reg.readText())
    }
}
