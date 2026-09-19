package app.gamenative

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test

class PrefManagerHiddenSyncTimestampsTest {

/** Verifies the behavior described by this test: hidden Source Timestamps Default To Zero. */
    @Test
    fun hiddenSourceTimestampsDefaultToZero() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

/** Verifies the behavior described by this test: hidden Source Timestamps Default To Zero When Data Store Read Fails. */
    @Test
    fun hiddenSourceTimestampsDefaultToZeroWhenDataStoreReadFails() = runBlocking {
        installFakePrefManager(FailingDataStore()).use {
            assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

/** Verifies the behavior described by this test: hidden Source Timestamps Are Persisted Independently. */
    @Test
    fun hiddenSourceTimestampsArePersistedIndependently() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            PrefManager.setLastSuccessfulGogComHiddenSync(11L)
            PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

            assertEquals(11L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(22L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

/** Verifies the behavior described by this test: clearing Hidden Source Timestamps Resets Both Sources. */
    @Test
    fun clearingHiddenSourceTimestampsResetsBothSources() = runBlocking {
        installFakePrefManager(FakeDataStore()).use {
            PrefManager.setLastSuccessfulGogComHiddenSync(11L)
            PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

            PrefManager.clearHiddenSyncTimestamps()

            assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
            assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
        }
    }

    private class FailingDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            throw IOException("read failed")
        }

/** Implements the in-memory DataStore update contract used by the test. */
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            throw AssertionError("writes are not expected")
        }
    }
}
