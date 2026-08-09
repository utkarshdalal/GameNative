package app.gamenative.ui.data

import app.gamenative.PrefManager
import app.gamenative.data.GOGGame
import app.gamenative.data.HiddenGameFilter
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUntil
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCountsTest {

    @Test
    fun persistsPostHiddenVisibilityCounts() {
        installFakePrefManager(FakeDataStore()).use {
            // Fixture: hidden games that the default (off) setting filters out.
            val gogGames = listOf(
                GOGGame(id = "g1", hidden = true),
                GOGGame(id = "g2", hidden = false),
                GOGGame(id = "g3", hidden = false),
            )
            val visibleGogCount = gogGames.count {
                HiddenGameFilter.passesGog(isHidden = it.hidden, showHiddenByDefault = false)
            }

            val hiddenSteamAppIds = setOf(570)
            val steamAppIds = listOf(440, 570)
            val visibleSteamCount = steamAppIds.count { appId ->
                HiddenGameFilter.passesSteam(
                    appId = appId,
                    hiddenAppIds = hiddenSteamAppIds,
                    showHiddenByDefault = false,
                    hiddenCollectionSelected = false,
                )
            }

            LibraryCounts.persist(
                customGames = 0,
                steamGames = visibleSteamCount,
                gogGames = visibleGogCount,
                gogInstalledGames = 1,
                epicGames = 0,
                epicInstalledGames = 0,
                amazonInstalledGames = 0,
            )

            // Wait on actual values (not a raw write count) so PrefManager.init's async cleanup
            // writes cannot satisfy the wait early.
            awaitUntil {
                PrefManager.steamGamesCount == visibleSteamCount &&
                    PrefManager.gogGamesCount == visibleGogCount &&
                    PrefManager.gogInstalledGamesCount == 1
            }

            assertEquals(0, PrefManager.customGamesCount)
            assertEquals(visibleSteamCount, PrefManager.steamGamesCount)
            assertEquals(visibleGogCount, PrefManager.gogGamesCount)
            assertEquals(1, PrefManager.gogInstalledGamesCount)
            assertEquals(0, PrefManager.epicGamesCount)
            assertEquals(0, PrefManager.epicInstalledGamesCount)
            assertEquals(0, PrefManager.amazonInstalledGamesCount)
        }
    }
}
