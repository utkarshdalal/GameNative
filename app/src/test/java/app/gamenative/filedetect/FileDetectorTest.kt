package app.gamenative.filedetect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDetectorTest {
    private val detector get() = Fixtures.detector

    @Test
    fun `empty list gives empty result`() {
        assertEquals(emptyMap<String, Int>(), detector.matches(emptyList()))
        assertTrue(detector.detect(emptyList()).isEmpty)
    }

    @Test
    fun `godot is not deduced for unrelated pck files`() {
        val cases = listOf(
            listOf("Game.exe", "Sound/Game.pck"),
            listOf("Folder/Game", "Sound/Game.pck"),
            listOf("Folder/Game.exe", "Game.pck"),
        )
        for (files in cases) {
            val matches = detector.matches(files, filterEvidence = false)
            assertEquals("Incorrectly matched Godot for $files", mapOf("Evidence.PCK" to 1), matches)
            assertTrue(detector.detect(files).isEmpty)
        }
    }

    @Test
    fun `godot is deduced from exe and pck pairs`() {
        assertTrue(FileDetector.isEngineGodot(listOf("Game.exe", "Game.pck")))
        assertTrue(FileDetector.isEngineGodot(listOf("bin/game.x86_64", "bin/game.pck")))
        assertTrue(FileDetector.isEngineGodot(listOf("Folder/Game", "Folder/Game.pck")))
        assertTrue(FileDetector.isEngineGodot(listOf("data.pck", "other.exe")))
        assertTrue(FileDetector.isEngineGodot(listOf("A.app/Contents/MacOS/A", "A.app/Contents/Resources/A.pck")))
        assertFalse(FileDetector.isEngineGodot(listOf("MacOS/A", "Resources/A.pck")))
        assertFalse(FileDetector.isEngineGodot(listOf("data.pck", "extra.pck")))
        assertFalse(FileDetector.isEngineGodot(listOf("Game.exe", "Other.pck")))
        assertFalse(FileDetector.isEngineGodot(listOf("Game.exe")))

        val godot = detector.detect(listOf("Game.exe", "Game.pck"))
        assertEquals(listOf("Godot"), godot.engines)
        val mac = detector.detect(listOf("MyGame.app/Contents/MacOS/MyGame", "MyGame.app/Contents/Resources/MyGame.pck"))
        assertEquals(listOf("Godot"), mac.engines)
    }

    @Test
    fun `evidence keys are dropped by default`() {
        val files = listOf("game.exe", "game.pck")
        assertFalse(detector.matches(files).keys.any { it.startsWith("Evidence.") })
        assertTrue(detector.matches(files, filterEvidence = false).containsKey("Evidence.PCK"))
    }

    @Test
    fun `deduced engine is written with count one`() {
        val counts = detector.matches(listOf("a.u", "b.u", "c.u"))
        assertEquals(mapOf("Engine.Unreal" to 1), counts)
    }

    @Test
    fun `detect sorts by count then name and matching is case insensitive`() {
        val files = listOf("BIN/STEAMWORKS.NET.DLL", "bin/UnityPlayer.dll", "Game_Data/globalgamemanagers.assets", "x/EasyAntiCheat/EasyAntiCheat_x64.dll", "Game_Data/Managed/Unity.Entities.dll", "Game_Data/Plugins/lib_burst_generated.dll")
        val d = detector.detect(files)
        assertTrue("Unity" in d.engines, d.toString())
        assertTrue("SteamworksNET" in d.sdks, d.toString())
        assertEquals(listOf("SteamworksNET", "UnityBurst", "UnityEntities"), d.sdks)
        assertTrue("EasyAntiCheat" in d.antiCheat, d.toString())
        val counts = detector.matches(files)
        val sdkCounts = counts.filterKeys { it.startsWith("SDK.") }
        val expectedOrder = sdkCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key.removePrefix("SDK.") }
        assertEquals(expectedOrder, d.sdks)
    }

    @Test
    fun `200k synthetic paths run under two seconds`() {
        val exts = listOf("dll", "exe", "pak", "png", "ogg", "txt", "json", "bin", "dat", "so", "pck", "wad", "cfg", "u", "assets")
        val rnd = java.util.Random(42)
        val paths = ArrayList<String>(200_000)
        for (i in 0 until 200_000) {
            val depth = 1 + rnd.nextInt(4)
            val sb = StringBuilder()
            for (d in 0 until depth) sb.append("Folder").append(rnd.nextInt(50)).append('/')
            sb.append("File").append(i)
            if (rnd.nextInt(10) != 0) sb.append('.').append(exts[rnd.nextInt(exts.size)])
            paths.add(sb.toString())
        }
        paths.add("UnityPlayer.dll")
        paths.add("Game/Binaries/Win64/Game-Win64-Shipping.exe")
        paths.add("Steamworks.NET.dll")

        detector.matches(paths.subList(0, 5_000))
        val start = System.nanoTime()
        val result = detector.matches(paths)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        println("200k paths matched in ${elapsedMs} ms -> $result")
        assertTrue(result.containsKey("Engine.Unity"))
        assertTrue(result.containsKey("Engine.Unreal"))
        assertTrue(result.containsKey("SDK.SteamworksNET"))
        assertTrue("Took ${elapsedMs} ms", elapsedMs < 2_000)
    }
}
