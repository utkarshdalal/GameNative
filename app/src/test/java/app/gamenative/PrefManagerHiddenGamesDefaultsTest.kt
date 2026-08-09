package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PrefManagerHiddenGamesDefaultsTest {

    @Test
    fun showHiddenGamesByDefaultDefaultsToFalse() {
        installFakePrefManager(FakeDataStore()).use {
            assertFalse(PrefManager.showHiddenGamesByDefault)
        }
    }
}
