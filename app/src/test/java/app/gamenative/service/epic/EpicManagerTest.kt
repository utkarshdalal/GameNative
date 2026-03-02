package app.gamenative.service.epic

import app.gamenative.db.dao.EpicGameDao
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Unit tests for EpicManager.parseGameFromCatalog()
 *
 * Tests parsing of Epic catalog API responses for:
 * - Non-third-party games
 * - Ubisoft games
 * - EA games
 */
class EpicManagerTest {

    private lateinit var epicManager: EpicManager

    @Before
    fun setup() {
        val mockDao = mock(EpicGameDao::class.java)
        epicManager = EpicManager(mockDao)
    }

    private fun loadJsonResource(filename: String): String {
        return javaClass.classLoader
            ?.getResourceAsStream("epic/$filename")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: throw IllegalArgumentException("Could not load resource: $filename")
    }

    // ========== parseGameFromCatalog Tests ==========

    @Test
    fun `parse non-Ubisoft non-EA game correctly - Darksiders`() {
        val json = loadJsonResource("darksiders_catalog.json")
        val catalogObj = JSONObject(json)
        val gameData = catalogObj.getJSONObject("8c04901974534bd0818f747952b0a19b")

        val game = epicManager.parseGameFromCatalog(gameData, "Darksiders2")

        // Basic info
        assertEquals("8c04901974534bd0818f747952b0a19b", game.catalogId)
        assertEquals("Darksiders2", game.appName)
        assertEquals("Darksiders II Deathinitive Edition", game.title)
        assertEquals("091d95ea332843498122beee1a786d71", game.namespace)
        assertEquals("THQ Nordic GmbH", game.developer)

        // Images
        assertTrue(game.artSquare.contains("Storefront_Landscape"))
        assertTrue(game.artCover.contains("Storefront_Portrait"))

        // Third party - should be empty for non-third-party games
        assertEquals("", game.thirdPartyManagedApp)
        assertFalse(game.isEAManaged)

        // Custom attributes
        assertTrue(game.canRunOffline)
        assertFalse(game.cloudSaveEnabled)

        // Not DLC
        assertFalse(game.isDLC)
        assertEquals("", game.baseGameAppName)
    }

    @Test
    fun `parse Ubisoft game correctly - Watch Dogs`() {
        val json = loadJsonResource("watchdogs_catalog.json")
        val catalogObj = JSONObject(json)
        val gameData = catalogObj.getJSONObject("6dc445f656de4e029834b2d32b6a2f77")

        val game = epicManager.parseGameFromCatalog(gameData, "WatchDogs")

        // Basic info
        assertEquals("6dc445f656de4e029834b2d32b6a2f77", game.catalogId)
        assertEquals("WatchDogs", game.appName)
        assertEquals("Watch Dogs", game.title)
        assertEquals("ecebf45065bc4993abfe0e84c40ff18e", game.namespace)
        assertEquals("Ubisoft Entertainment", game.developer)

        // Images
        assertTrue(game.artSquare.contains("WDOG_STD_Store_Landscape"))
        assertTrue(game.artCover.contains("WDOG_STD_Store_Portrait"))

        // Third party - Ubisoft
        // Should pick UbisoftConnect (thirdPartyManagedProvider) first
        assertEquals("UbisoftConnect", game.thirdPartyManagedApp)
        assertFalse(game.isEAManaged)

        // Custom attributes
        assertTrue(game.canRunOffline)

        // Not DLC
        assertFalse(game.isDLC)
    }

    @Test
    fun `parse EA game correctly - Dragon Age`() {
        val json = loadJsonResource("dragonage_catalog.json")
        val catalogObj = JSONObject(json)
        val gameData = catalogObj.getJSONObject("1860f41341d1499fa4a06ae064340bbe")

        val game = epicManager.parseGameFromCatalog(gameData, "DragonAgeInquisition")

        // Basic info
        assertEquals("1860f41341d1499fa4a06ae064340bbe", game.catalogId)
        assertEquals("DragonAgeInquisition", game.appName)
        assertEquals("Dragon Age: Inquisition – Game of the Year Edition", game.title)
        assertEquals("afe2527e29b94db48b2eef984e34d81a", game.namespace)
        assertEquals("Electronic Arts", game.developer)

        // Images
        assertTrue(game.artSquare.contains("dragon-age-inquisition"))
        assertTrue(game.artCover.contains("dragon-age-inquisition"))

        // Third party - EA
        assertEquals("The EA App", game.thirdPartyManagedApp)
        assertTrue(game.isEAManaged)

        // Executable name
        assertEquals("DragonAgeInquisition.exe", game.executable)

        // Not DLC
        assertFalse(game.isDLC)
    }

    @Test
    fun `verify third party priority order - Ubisoft`() {
        // Ubisoft has both thirdPartyManagedProvider and partnerLinkType
        // Should prioritize thirdPartyManagedProvider (second in listOfNotNull)
        val json = loadJsonResource("watchdogs_catalog.json")
        val catalogObj = JSONObject(json)
        val gameData = catalogObj.getJSONObject("6dc445f656de4e029834b2d32b6a2f77")

        val game = epicManager.parseGameFromCatalog(gameData, "WatchDogs")

        assertEquals("UbisoftConnect", game.thirdPartyManagedApp)
    }
}
