package app.gamenative.utils

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @Test fun recognizesLoaderFamiliesAndRenamedProxiesAcrossReadBoundaries() {
        val evidence = listOf(
            "doorstop_config.ini\u0000DOORSTOP_INVOKE_DLL_PATH".toByteArray(Charsets.UTF_16LE),
            "IsUltimateASILoader\u0000".toByteArray() + "Ultimate ASI Loader".toByteArray(Charsets.UTF_16LE),
            "REFramework entry\u0000reframework_crash.dmp".toByteArray(),
            "MelonLoader.Bootstrap.dll\u0000MelonLoader.NativeHost".toByteArray(Charsets.UTF_16LE),
            "Failed to initialize MelonLoader: \u0000Failed to find MelonLoader Bootstrap".toByteArray(),
            "MelonLoader\\Dependencies\\Bootstrap.dll\u0000--melonloader.basedir".toByteArray(Charsets.UTF_16LE),
        )
        val exe = file("Game/game.exe")
        val dll = File(exe.parentFile, "WINHTTP.DLL")
        for (bytes in evidence) {
            for (offset in listOf(128, 65527)) {
                writeLoaderDll(dll, bytes, offset)
                assertEquals(listOf("winhttp"), ModDllOverrides.detect(exe))
            }
        }
        dll.delete()
        for (name in listOf("dinput8", "winhttp", "version", "winmm", "dsound")) {
            val proxy = writeLoaderDll(File(exe.parentFile, "$name.dll"))
            assertEquals(listOf(name), ModDllOverrides.detect(exe))
            assertTrue(proxy.delete())
        }
    }

    @Test fun bundledDllsStayUntouchedBesideModsAndStaleLoaderFiles() {
        val exe = file("Game/game.exe")
        for (name in listOf("dinput8", "winhttp", "version", "winmm", "dsound")) {
            writeLoaderDll(File(exe.parentFile, "$name.dll"), "ordinary game dependency".toByteArray())
        }
        for (folder in listOf("BepInEx", "MelonLoader", "reframework")) File(exe.parentFile, folder).mkdir()
        for (path in listOf("doorstop_config.ini", "mod.asi", "scripts/mod.asi", "plugins/mod.asi", "update/mod.asi")) file("Game/$path")
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
        val loader = writeLoaderDll(File(exe.parentFile, "winhttp.dll"))
        assertEquals(listOf("winhttp"), ModDllOverrides.detect(exe))
        loader.writeText("original DLL restored")
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
    }

    @Test fun partialAndDifferentLoaderMarkersDoNotQualify() {
        val exe = file("Game/game.exe")
        for (contents in listOf("doorstop_config.ini", "DOORSTOP_INVOKE_DLL_PATH", "doorstop_config.ini\u0000Ultimate ASI Loader")) {
            writeLoaderDll(File(exe.parentFile, "winhttp.dll"), contents.toByteArray(Charsets.UTF_16LE))
            assertTrue(contents, ModDllOverrides.detect(exe).isEmpty())
        }
    }

    @Test fun malformedAndOversizedBinariesAreSkippedWithoutHidingValidLoaders() {
        val exe = file("Game/game.exe")
        writeLoaderDll(File(exe.parentFile, "winhttp.dll"))
        val dll = File(exe.parentFile, "version.dll")
        val valid = writeLoaderDll(dll).readBytes()
        for ((offset, value) in listOf(0 to 0, 0x3c to -1, 64 to 0, 84 to 0)) {
            dll.writeBytes(valid.copyOf().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value) })
            assertEquals(listOf("winhttp"), ModDllOverrides.detect(exe))
        }
        for (length in listOf(0L, 63L, 87L, 32L * 1024 * 1024 + 1)) {
            dll.writeBytes(valid)
            RandomAccessFile(dll, "rw").use { it.setLength(length) }
            assertEquals(listOf("winhttp"), ModDllOverrides.detect(exe))
        }
    }

    @Test fun inspectsOnlyAllowedDllFilesBesideAnExistingExecutable() {
        val exe = file("Game/Bin/game.exe")
        for (path in listOf("Game/winhttp.dll", "Game/Bin/backup/winhttp.dll", "Game/Bin/dxgi.dll", "Game/Bin/d3d11.dll")) {
            writeLoaderDll(File(temp.root, path))
        }
        File(exe.parentFile, "version.dll").mkdir()
        assertTrue(ModDllOverrides.detect(exe).isEmpty())
        assertTrue(ModDllOverrides.detect(null).isEmpty())
        assertTrue(ModDllOverrides.detect(File(temp.root, "missing.exe")).isEmpty())
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

    @Test fun preservesRegistryChoicesIncludingBuiltinFirstInput() {
        val registry = ModDllOverrides.RegistryOverrides(
            global = mapOf("dinput8" to "builtin,native", "winhttp" to "builtin"),
            app = mapOf("version" to ""),
        )
        val result = ModDllOverrides.merge("icu=n", listOf("dinput8", "winhttp", "version", "winmm"), registry)
        assertEquals("icu=n;winmm=n,b", result.value)
        assertEquals(listOf("winmm"), result.added)
        assertEquals(listOf("dinput8", "winhttp", "version"), result.preserved)
    }

    @Test fun preservesGlobalInputChoicesRegardlessOfSpellingOrOrder() {
        for (name in listOf("dinput8", "DINPUT8.dll", "*dinput8", "*", "C:\\Game\\dinput8.dll")) {
            for (order in listOf("b,n", "builtin,native", "builtin, native", "n,b", "b", "")) {
                val registry = ModDllOverrides.RegistryOverrides(global = mapOf(name to order))
                val result = ModDllOverrides.merge("icu=n", listOf("dinput8"), registry)
                assertEquals("$name=$order", "icu=n", result.value)
                assertTrue(result.added.isEmpty())
                assertEquals(listOf("dinput8"), result.preserved)
            }
        }
    }

    @Test fun preservesAppRegistryAndEnvironmentChoices() {
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
        assertTrue(registry.preserves("dinput8"))
        assertTrue(registry.preserves("winhttp"))
        assertTrue(registry.preserves("version"))
        assertFalse(registry.preserves("dsound"))
        assertEquals(original, reg.readText())
    }
}

// Minimal PE DLL fixture shared by detection and launch integration tests.
@JvmOverloads
internal fun writeLoaderDll(
    file: File,
    evidence: ByteArray = "doorstop_config.ini\u0000DOORSTOP_INVOKE_DLL_PATH".toByteArray(Charsets.UTF_16LE),
    offset: Int = 128,
): File = file.apply {
    parentFile.mkdirs()
    val bytes = ByteBuffer.allocate(offset + evidence.size).order(ByteOrder.LITTLE_ENDIAN)
    bytes.putShort(0, 0x5a4d)
    bytes.putInt(0x3c, 64)
    bytes.putInt(64, 0x4550)
    bytes.putShort(86, 0x2000)
    bytes.position(offset)
    bytes.put(evidence)
    writeBytes(bytes.array())
}
