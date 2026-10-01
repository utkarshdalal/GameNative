package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefManagerHiddenGamesDefaultsTest {

    @Test
    fun showHiddenGamesByDefaultDefaultsToTrue() {
        installFakePrefManager(FakeDataStore()).use {
            assertTrue(PrefManager.showHiddenGamesByDefault)
        }
    }
}
