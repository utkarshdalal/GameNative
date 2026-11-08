package app.gamenative.optimize

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineHintTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(vararg paths: String): File {
        val root = tmp.newFolder()
        for (path in paths) {
            val f = File(root, path)
            if (path.endsWith("/")) {
                f.mkdirs()
            } else {
                f.parentFile?.mkdirs()
                f.writeText("x")
            }
        }
        return root
    }

    @Test
    fun unrealFromTheShippingExeOrEngineBinaries() {
        assertEquals(EngineHint.UNREAL, EngineHint.detect(tree("Soulstonia.exe", "Soulstonia/Binaries/Win64/Soulstonia-Win64-Shipping.exe")))
        assertEquals(EngineHint.UNREAL, EngineHint.detect(tree("Game/Binaries/WinGDK/Game-WinGDK-Shipping.exe")))
        assertEquals(EngineHint.UNREAL, EngineHint.detect(tree("Engine/Binaries/ThirdParty/", "Game.exe")))
    }

    @Test
    fun unityFromThePlayerDllOrDataFolder() {
        assertEquals(EngineHint.UNITY, EngineHint.detect(tree("Game.exe", "UnityPlayer.dll")))
        assertEquals(EngineHint.UNITY, EngineHint.detect(tree("Game.exe", "Game_Data/globalgamemanagers")))
    }

    @Test
    fun sourceGodotAndIdTech() {
        assertEquals(EngineHint.SOURCE, EngineHint.detect(tree("hl2.exe", "hl2/gameinfo.txt")))
        assertEquals(EngineHint.SOURCE, EngineHint.detect(tree("game/bin/engine.dll", "game.exe")))
        assertEquals(EngineHint.GODOT, EngineHint.detect(tree("Game.exe", "Game.pck")))
        assertEquals(EngineHint.IDTECH, EngineHint.detect(tree("DOOMx64.exe", "base/gameresources.resources")))
        assertEquals(EngineHint.IDTECH, EngineHint.detect(tree("DOOMEternalx64vk.exe", "base/DOOMConfig.local")))
    }

    @Test
    fun deeperThanThreeFoldersOrNothingTellsIsNull() {
        assertNull(EngineHint.detect(tree("a/b/c/d/UnityPlayer.dll", "Game.exe")))
        assertNull(EngineHint.detect(tree("Game.exe", "data/level.bin")))
        assertNull(EngineHint.detect(tree("data/game.pck")))
        assertNull(EngineHint.detect(null))
        assertNull(EngineHint.detect(File(tmp.root, "missing")))
    }

    @Test
    fun unrealWinsOverAUnityLookingDataFolder() {
        assertEquals(EngineHint.UNREAL, EngineHint.detect(tree("Game/Binaries/Win64/Game-Win64-Shipping.exe", "Extra_Data/x")))
    }
}
