package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamGridDBTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun deleteGameImages_removesOnlySavedSteamGridDbImages() {
        val folder = tempFolder.newFolder("game")
        val saved = listOf(
            "steamgriddb_grid_hero.png",
            "steamgriddb_grid_capsule.jpg",
            "steamgriddb_hero.webp",
            "STEAMGRIDDB_LOGO.PNG",
        )
        val kept = listOf(
            "cover.png",
            "coverh.jpg",
            "coverv.webp",
            "game.extracted.ico",
            ".gamenative",
            "steamgriddb_notes.txt",
            "game.exe",
        )
        (saved + kept).forEach { File(folder, it).writeText("x") }
        File(folder, "sub").mkdirs()
        File(folder, "sub/steamgriddb_hero.png").writeText("x")

        SteamGridDB.deleteGameImages(folder)

        assertEquals((kept + "sub").sorted(), folder.list()!!.sorted())
        assertEquals(listOf("steamgriddb_hero.png"), File(folder, "sub").list()!!.toList())
    }
}
