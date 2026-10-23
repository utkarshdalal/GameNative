package app.gamenative.utils

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameMetadataManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun metadataJson(folder: File) = JSONObject(File(folder, ".gamenative").readText())

    @Test
    fun setMatch_storesMatchKeepsAppIdAndDropsPreviousReleaseDate() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbFetched = true, releaseDate = 1_600_000_000L))

        assertTrue(GameMetadataManager.setMatch(folder, appId = 99, name = "Hollow Knight", gameId = 11))

        val metadata = GameMetadataManager.read(folder)!!
        assertEquals(42, metadata.appId)
        assertTrue(metadata.steamgriddbFetched)
        assertNull(metadata.releaseDate)
        assertEquals("Hollow Knight", metadata.matchedName)
        assertEquals(11, metadata.matchedGameId)
    }

    @Test
    fun setMatch_withoutMetadataFile_usesGivenAppId() {
        val folder = tempFolder.newFolder("game")

        assertTrue(GameMetadataManager.setMatch(folder, appId = 7, name = "Hades", gameId = 3))

        assertEquals(GameMetadata(appId = 7, matchedName = "Hades", matchedGameId = 3), GameMetadataManager.read(folder))
    }

    @Test
    fun setMatch_withNullName_removesMatch() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.write(folder, GameMetadata(appId = 42, steamgriddbFetched = true))
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight", gameId = 11)

        assertTrue(GameMetadataManager.setMatch(folder, appId = 42, name = null, gameId = null))

        assertEquals(GameMetadata(appId = 42, steamgriddbFetched = true), GameMetadataManager.read(folder))
        val json = metadataJson(folder)
        assertFalse(json.has("matchedName"))
        assertFalse(json.has("matchedGameId"))
    }

    @Test
    fun setMatch_replacingMatchWithoutGameId_dropsOldGameId() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight", gameId = 11)

        GameMetadataManager.setMatch(folder, appId = 42, name = "Hades", gameId = null)

        val metadata = GameMetadataManager.read(folder)!!
        assertEquals("Hades", metadata.matchedName)
        assertNull(metadata.matchedGameId)
    }

    @Test
    fun update_afterMatch_keepsMatch() {
        val folder = tempFolder.newFolder("game")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight", gameId = 11)

        // What SteamGridDB.fetchGameImages does after downloading artwork
        GameMetadataManager.update(folder, appId = 42, steamgriddbFetched = true, releaseDate = 1_500_000_000L)

        val metadata = GameMetadataManager.read(folder)!!
        assertEquals("Hollow Knight", metadata.matchedName)
        assertEquals(11, metadata.matchedGameId)
        assertTrue(metadata.steamgriddbFetched)
        assertEquals(1_500_000_000L, metadata.releaseDate)
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
    fun read_blankMatchedName_isNoMatch() {
        val folder = tempFolder.newFolder("game")
        File(folder, ".gamenative").writeText("""{"appId":42,"matchedName":"  ","matchedGameId":0}""")

        val metadata = GameMetadataManager.read(folder)!!
        assertNull(metadata.matchedName)
        assertNull(metadata.matchedGameId)
    }
}
