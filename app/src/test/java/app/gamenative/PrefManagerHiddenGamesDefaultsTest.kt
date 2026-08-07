package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefManagerHiddenGamesDefaultsTest {

    @Test
    fun showHiddenGamesByDefaultDefaultsToFalse() {
        installFakePrefManager(FakeDataStore())
        assertFalse(PrefManager.showHiddenGamesByDefault)
    }

    @Test
    fun libraryGogHiddenIdsDefaultsToEmpty() {
        installFakePrefManager(FakeDataStore())
        assertTrue(PrefManager.libraryGogHiddenIds.isEmpty())
    }
}
