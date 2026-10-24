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

    private var savedFromWhenFetching: Int? = null

    /**
     * Runs replaceGameImages for a SteamGridDB match with id 5 and returns the files present when
     * fetching. A search for [folderNameGameId]'s folder name finds that game id.
     */
    private fun filesWhenReplacing(
        folder: File,
        folderNameGameId: Int? = null,
        onImagesChanged: () -> Unit = {},
    ): List<String>? = runBlocking {
        val match = SteamGridDB.GameSearchResult(gameId = 5, name = "Hollow Knight", releaseDate = 0)
        mockkObject(SteamGridDB)
        coEvery { SteamGridDB.searchGame(any()) } returns null
        coEvery { SteamGridDB.searchGame("Hollow Knight") } returns match
        if (folderNameGameId != null) {
            coEvery { SteamGridDB.searchGame(folder.name) } returns match.copy(gameId = folderNameGameId, name = folder.name)
        }
        var files: List<String>? = null
        coEvery { SteamGridDB.fetchGameImages("Hollow Knight", folder.absolutePath, match) } coAnswers {
            files = folder.list()!!.filter { !it.startsWith(".") }.sorted()
            savedFromWhenFetching = GameMetadataManager.read(folder)?.steamgriddbGameId
            SteamGridDB.ImageFetchResult(null, null, null)
        }
        SteamGridDB.replaceGameImages("Hollow Knight", folder, onImagesChanged)
        files
    }

    private val allImages = listOf("cover.png", "steamgriddb_grid_hero.png", "steamgriddb_hero.png")

    @Test
    fun replaceGameImages_imagesFromAnotherGame_areDeletedBeforeFetching() {
        val folder = folderWithImages()
        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbGameId = 4))
        var changes = 0

        assertEquals(listOf("cover.png"), filesWhenReplacing(folder) { changes++ })
        assertEquals(2, changes)
        // So images saved by a fetch that is then interrupted count as the new game's
        assertEquals(5, savedFromWhenFetching)
    }

    @Test
    fun replaceGameImages_imagesFromSameGame_areKept() {
        val folder = folderWithImages()
        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbGameId = 5))
        var changes = 0

        assertEquals(allImages, filesWhenReplacing(folder) { changes++ })
        assertEquals(1, changes)
    }

    @Test
    fun replaceGameImages_imagesWithoutRecordedGame_countAsTheFolderNamesGame() {
        // Unmatched: the images were fetched for the folder name, which is the name searched now
        val unmatched = folderWithImages()
        GameMetadataManager.write(unmatched, GameMetadata(appId = 42))
        assertEquals(allImages, filesWhenReplacing(unmatched))
        assertEquals(5, savedFromWhenFetching)
    }

    @Test
    fun replaceGameImages_imagesWithoutRecordedGame_afterMatch_areKeptOnlyIfFolderNameFindsSameGame() {
        val sameGame = folderWithImages()
        GameMetadataManager.setMatch(sameGame, appId = 42, name = "Hollow Knight")
        assertEquals(allImages, filesWhenReplacing(sameGame, folderNameGameId = 5))

        val otherGame = tempFolder.newFolder("other").also { folder ->
            listOf("steamgriddb_grid_hero.png", "cover.png").forEach { File(folder, it).writeText("x") }
        }
        GameMetadataManager.setMatch(otherGame, appId = 43, name = "Hollow Knight")
        assertEquals(listOf("cover.png"), filesWhenReplacing(otherGame, folderNameGameId = 4))
    }
}
