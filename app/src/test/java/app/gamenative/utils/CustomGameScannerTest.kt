package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomGameScannerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun getGameName_withoutMatch_isFolderName() {
        val folder = tempFolder.newFolder("Hollow Knight v1.5.78")
        GameMetadataManager.write(folder, GameMetadata(appId = 42))

        assertEquals("Hollow Knight v1.5.78", CustomGameScanner.getGameName(folder))
    }

    @Test
    fun getGameName_withMatch_isMatchedName() {
        val folder = tempFolder.newFolder("Hollow Knight v1.5.78")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight", gameId = 11)

        assertEquals("Hollow Knight", CustomGameScanner.getGameName(folder))
    }

    @Test
    fun getGameName_afterMatchRemoved_isFolderName() {
        val folder = tempFolder.newFolder("Hollow Knight v1.5.78")
        GameMetadataManager.setMatch(folder, appId = 42, name = "Hollow Knight", gameId = 11)
        GameMetadataManager.setMatch(folder, appId = 42, name = null, gameId = null)

        assertEquals("Hollow Knight v1.5.78", CustomGameScanner.getGameName(folder))
    }

    @Test
    fun getGameName_withoutMetadataFile_isFolderName() {
        assertEquals("Hades", CustomGameScanner.getGameName(tempFolder.newFolder("Hades")))
    }
}
