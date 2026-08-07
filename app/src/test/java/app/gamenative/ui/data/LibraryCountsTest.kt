package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUpdateCount
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCountsTest {

    @Test
    fun persistsPostHiddenVisibilityCounts() {
        val fake = FakeDataStore()
        installFakePrefManager(fake)

        LibraryCounts.persist(
            customGames = 1,
            steamGames = 2,
            gogGames = 3,
            gogInstalledGames = 4,
            epicGames = 5,
            epicInstalledGames = 6,
            amazonInstalledGames = 7,
        )

        fake.awaitUpdateCount(7)
        assertEquals(1, PrefManager.customGamesCount)
        assertEquals(2, PrefManager.steamGamesCount)
        assertEquals(3, PrefManager.gogGamesCount)
        assertEquals(4, PrefManager.gogInstalledGamesCount)
        assertEquals(5, PrefManager.epicGamesCount)
        assertEquals(6, PrefManager.epicInstalledGamesCount)
        assertEquals(7, PrefManager.amazonInstalledGamesCount)
    }
}
