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
    fun gameMakerReEngineFrostbiteCryEngineAndIl2Cpp() {
        assertEquals(EngineHint.GAMEMAKER, EngineHint.detect(tree("Game.exe", "data.win")))
        assertEquals(EngineHint.RE_ENGINE, EngineHint.detect(tree("re2.exe", "re_chunk_000.pak")))
        assertEquals(EngineHint.FROSTBITE, EngineHint.detect(tree("bf1.exe", "Data/initfs_Win32", "Data/cas.cat")))
        assertEquals(EngineHint.FROSTBITE, EngineHint.detect(tree("Game.exe", "Data/layout.toc")))
        assertEquals(EngineHint.CRYENGINE, EngineHint.detect(tree("Bin64/Game.exe", "Bin64/CrySystem.dll")))
        assertEquals(EngineHint.CRYENGINE, EngineHint.detect(tree("system.cfg", "Bin64/Game.exe")))
        assertEquals(EngineHint.UNITY, EngineHint.detect(tree("Game.exe", "GameAssembly.dll")))
    }

    @Test
    fun unityMetaComesFromAppInfo() {
        val root = tree("Game.exe", "Game_Data/globalgamemanagers")
        File(root, "Game_Data/app.info").writeText("Red Hook Studios\nDarkest Dungeon II\n")
        assertEquals(EngineHint.Meta("Red Hook Studios", "Darkest Dungeon II"), EngineHint.unityMeta(root))
        val nested = tree("Build/Game_Data/x")
        File(nested, "Build/Game_Data/app.info").writeText("Acme\n")
        assertEquals(EngineHint.Meta("Acme", null), EngineHint.unityMeta(nested))
        assertNull(EngineHint.unityMeta(tree("Game.exe", "Game_Data/x")))
        assertNull(EngineHint.parseAppInfo("\n\n"))
        assertNull(EngineHint.unityMeta(null))
    }

    @Test
    fun unrealWinsOverAUnityLookingDataFolder() {
        assertEquals(EngineHint.UNREAL, EngineHint.detect(tree("Game/Binaries/Win64/Game-Win64-Shipping.exe", "Extra_Data/x")))
    }
}
