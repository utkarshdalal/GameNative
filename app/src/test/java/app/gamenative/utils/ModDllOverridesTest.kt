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

    @Test fun detectsProxyDllsBesideExecutableByName() {
        val exe = file("Game/Bin/game.exe")
        file("Game/Bin/WINHTTP.DLL")
        file("Game/Bin/dwrite.dll")
        file("Game/Bin/d3d11.dll")
        file("Game/dinput8.dll")
        assertEquals(listOf("winhttp", "dwrite"), ModDllOverrides.detect(exe.parentFile))
        assertTrue(ModDllOverrides.detect(File(temp.root, "missing")).isEmpty())
    }

    @Test fun appendsToExistingValue() {
        val original = "icu=n;xaudio2_7=native,builtin"
        assertEquals("$original;dinput8=n,b;winhttp=n,b", ModDllOverrides.merge(original, listOf("dinput8", "winhttp")))
        assertEquals("icu=n;winhttp=n,b", ModDllOverrides.merge("icu=n;", listOf("winhttp")))
        assertEquals("dinput8=n,b", ModDllOverrides.merge("", listOf("dinput8", "dinput8")))
    }

    @Test fun userValuesWin() {
        for (name in listOf("winhttp", "*winhttp", "C:\\Game\\winhttp.dll")) {
            for (separator in listOf(",", " ", "\t", ", \t")) {
                val original = "DINPUT8.dll$separator$name=b;version="
                assertEquals(original, ModDllOverrides.merge(original, listOf("dinput8", "winhttp", "version")))
                assertEquals(listOf("dsound"), ModDllOverrides.missing(original, listOf("winhttp", "dsound")))
            }
        }
    }

    @Test fun resolvesRelativeAndDrivePathsWithWindowsCase() {
        val exe = file("Game/Bin/Game.exe")
        val drives = mapOf("A" to File(temp.root, "Game"), "D" to File(temp.root, "Game"))
        for (path in listOf("bin/game.EXE", ".\\Bin\\Game.exe", "d:\\bin\\game.exe", "\"A:\\Bin\\Game.exe\"")) {
            assertEquals(exe.canonicalFile, ModDllOverrides.resolveExecutable(path, drives)?.canonicalFile)
        }
    }

    @Test fun resolvesDriveCAndIgnoresWindowsDirectory() {
        val driveC = File(temp.root, "prefix/drive_c")
        val exe = file("prefix/drive_c/Games/Game/game.exe")
        file("prefix/drive_c/windows/system32/tool.exe")
        assertEquals(exe.canonicalFile, ModDllOverrides.resolveExecutable("C:\\Games\\Game\\game.exe", emptyMap(), driveC)?.canonicalFile)
        assertNull(ModDllOverrides.resolveExecutable("C:\\Windows\\System32\\tool.exe", emptyMap(), driveC))
    }

    @Test fun rejectsUnknownDrivesAndMissingFiles() {
        file("Game/game.exe")
        val drives = mapOf("A" to File(temp.root, "Game"))
        for (path in listOf("Z:\\game.exe", "../Game/game.exe", "launch.bat", "missing.exe", "")) {
            assertNull(path, ModDllOverrides.resolveExecutable(path, drives))
        }
    }
}
