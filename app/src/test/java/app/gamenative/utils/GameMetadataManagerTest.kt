package app.gamenative.utils

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameMetadataManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun metadataJson(folder: File) = JSONObject(File(folder, ".gamenative").readText())

    @Test
    fun setMatch_storesMatchAndKeepsAppIdAndImageMetadata() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.write(
            folder,
            GameMetadata(appId = 42, steamgriddbFetched = true, releaseDate = 1_600_000_000L, steamgriddbGameId = 5),
        )

        assertTrue(GameMetadataManager.setMatch(folder, appId = 99, name = "Hollow Knight"))

        // The saved images, and the release date that came with them, are still game 5's until replaced
        assertEquals(
            GameMetadata(
                appId = 42,
                steamgriddbFetched = true,
                releaseDate = 1_600_000_000L,
                matchedName = "Hollow Knight",
                steamgriddbGameId = 5,
            ),
            GameMetadataManager.read(folder),
        )
    }

    @Test
    fun setMatch_withoutMetadataFile_usesGivenAppId() {
        val folder = tempFolder.newFolder("game")

        assertTrue(GameMetadataManager.setMatch(folder, appId = 7, name = "Hades"))

        assertEquals(GameMetadata(appId = 7, matchedName = "Hades"), GameMetadataManager.read(folder))
    }

    @Test
    fun setMatch_withNullName_removesMatch() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbFetched = true))
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight")

        assertTrue(GameMetadataManager.setMatch(folder, appId = 42, name = null))

        assertEquals(GameMetadata(appId = 42, steamgriddbFetched = true), GameMetadataManager.read(folder))
        assertFalse(metadataJson(folder).has("matchedName"))
    }

    @Test
    fun update_afterMatch_keepsMatch() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight")

        // What SteamGridDB.fetchGameImages does after downloading artwork
        GameMetadataManager.update(
            folder,
            appId = 42,
            steamgriddbFetched = true,
            releaseDate = 1_500_000_000L,
            steamgriddbGameId = 5,
        )

        assertEquals(
            GameMetadata(
                appId = 42,
                steamgriddbFetched = true,
                releaseDate = 1_500_000_000L,
                matchedName = "Hollow Knight",
                steamgriddbGameId = 5,
            ),
            GameMetadataManager.read(folder),
        )
    }

    @Test
    fun update_withoutSteamGridDbGameId_keepsSavedOne() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.update(folder, appId = 42, steamgriddbGameId = 5)

        GameMetadataManager.update(folder, appId = 42, steamgriddbFetched = false)

        assertEquals(5, GameMetadataManager.read(folder)!!.steamgriddbGameId)
    }

    @Test
    fun setSteamGridDBGame_recordsGameAndItsReleaseDate() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight")

        GameMetadataManager.setSteamGridDBGame(folder, gameId = 5, releaseDate = 1_500_000_000L)

        assertEquals(
            GameMetadata(appId = 42, releaseDate = 1_500_000_000L, matchedName = "Hollow Knight", steamgriddbGameId = 5),
            GameMetadataManager.read(folder),
        )
    }

    @Test
    fun setSteamGridDBGame_withoutReleaseDate_dropsThePreviousGamesDate() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.write(folder, GameMetadata(appId = 42, releaseDate = 1_500_000_000L, steamgriddbGameId = 4))

        GameMetadataManager.setSteamGridDBGame(folder, gameId = 5, releaseDate = 0)

        assertEquals(GameMetadata(appId = 42, steamgriddbGameId = 5), GameMetadataManager.read(folder))
    }

    @Test
    fun setSteamGridDBGame_withoutMetadataFile_doesNothing() {
        val folder = tempFolder.newFolder("game")

        GameMetadataManager.setSteamGridDBGame(folder, gameId = 5, releaseDate = 1_500_000_000L)

        assertFalse(File(folder, ".gamenative").exists())
    }

    @Test
    fun write_keepsKeysItDoesNotKnow() {
        val folder = tempFolder.newFolder("game")
        File(folder, ".gamenative").writeText("""{"appId":42,"futureField":"kept"}""")

        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbFetched = true))

        val json = metadataJson(folder)
        assertEquals("kept", json.getString("futureField"))
        assertTrue(json.getBoolean("steamgriddbFetched"))
    }

    @Test
    fun read_legacyPlainIntegerFile_migratesToJson() {
        val folder = tempFolder.newFolder("game")
        File(folder, ".gamenative").writeText("42")

        assertEquals(GameMetadata(appId = 42), GameMetadataManager.read(folder))
        assertEquals(42, metadataJson(folder).getInt("appId"))
    }

    @Test
    fun read_blankMatchedNameAndZeroSteamGridDbGameId_areAbsent() {
        val folder = tempFolder.newFolder("game")
        File(folder, ".gamenative").writeText("""{"appId":42,"matchedName":"  ","steamgriddbGameId":0}""")

        assertEquals(GameMetadata(appId = 42), GameMetadataManager.read(folder))
    }
}
