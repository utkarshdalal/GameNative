package app.gamenative.utils

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamGridDBTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun folderWithImages(): File = tempFolder.newFolder("game").also { folder ->
        listOf("steamgriddb_grid_hero.png", "steamgriddb_hero.png", "cover.png").forEach { File(folder, it).writeText("x") }
    }

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

    @Test
    fun replaceGameImages_withoutSearchResult_keepsCurrentImages() = runBlocking {
        val folder = folderWithImages()
        mockkObject(SteamGridDB)
        coEvery { SteamGridDB.searchGame("Hollow Knight") } returns null
        var changes = 0

        SteamGridDB.replaceGameImages("Hollow Knight", folder) { changes++ }

        assertTrue(File(folder, "steamgriddb_hero.png").exists())
        assertEquals(0, changes)
        coVerify(exactly = 0) { SteamGridDB.fetchGameImages(any(), any(), any()) }
    }

    @Test
    fun replaceGameImages_withSearchResult_deletesOldImagesBeforeFetching() = runBlocking {
        val folder = folderWithImages()
        val match = SteamGridDB.GameSearchResult(gameId = 5, name = "Hollow Knight", releaseDate = 0)
        mockkObject(SteamGridDB)
        coEvery { SteamGridDB.searchGame("Hollow Knight") } returns match
        var filesWhenFetching: List<String>? = null
        coEvery { SteamGridDB.fetchGameImages("Hollow Knight", folder.absolutePath, match) } coAnswers {
            filesWhenFetching = folder.list()!!.sorted()
            SteamGridDB.ImageFetchResult(null, null, null)
        }
        var changes = 0

        SteamGridDB.replaceGameImages("Hollow Knight", folder) { changes++ }

        assertEquals(listOf("cover.png"), filesWhenFetching)
        assertEquals(2, changes)
    }
}
