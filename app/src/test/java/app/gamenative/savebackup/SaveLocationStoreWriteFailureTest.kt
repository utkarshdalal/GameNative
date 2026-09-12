package app.gamenative.savebackup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import app.gamenative.enums.PathType
import app.gamenative.utils.FakeDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Example tests for [DataStoreSaveLocationStore] write-failure behavior — game-save-backup Task 3.3
 * (Requirement 3.11).
 *
 * These are plain JUnit 4 example tests (no Robolectric, no `PrefManager.init`). The store's
 * `suspend` `put`/`get` are driven with [runBlocking]. Two behaviors are pinned:
 *
 *  1. A failing `put` surfaces the error to the caller instead of swallowing it — `put` awaits
 *     `dataStore.edit { ... }` directly (see [DataStoreSaveLocationStore]'s write-path doc), so a
 *     write that throws propagates out of `put`.
 *  2. After a failed `put`, any previously persisted value is left unchanged — because the failing
 *     write never mutates the backing store, a subsequent `get` still observes the prior value.
 */
class SaveLocationStoreWriteFailureTest {

    /**
     * A [DataStore] test double whose write path ([updateData], which `edit` delegates to) always
     * throws. Reads delegate to an inner [FakeDataStore] so a store built on this double can still
     * serve any value that was persisted through the delegate directly.
     */
    private class FailingDataStore(
        private val delegate: FakeDataStore = FakeDataStore(),
        private val error: Throwable = java.io.IOException("simulated write failure"),
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = delegate.data

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            throw error
        }
    }

    private val sampleLocation = SaveLocation(PathType.WinSavedGames, "MyGame/Slot1")

    // Requirement 3.11: a failing put surfaces the error to the caller (it is not swallowed).
    @Test
    fun putSurfacesWriteFailure() = runBlocking {
        val store = DataStoreSaveLocationStore(FailingDataStore())

        val thrown = assertThrows(java.io.IOException::class.java) {
            runBlocking { store.put("STEAM_440", sampleLocation) }
        }
        assertEquals("simulated write failure", thrown.message)
    }

    // Requirement 3.11: after a failed put, any previously persisted value is left unchanged.
    @Test
    fun failedPutLeavesPreviousValueUnchanged() = runBlocking {
        val appId = "GOG_1207658924"
        val previous = SaveLocation(PathType.WinMyDocuments, "Saves/A")
        val attempted = SaveLocation(PathType.WinAppDataRoaming, "Saves/B")

        // A shared backing store that accepts the first (good) write but is then wrapped by a
        // failing layer for the second write. The failing layer reads through to the same backing
        // data, so get() reflects the durably-persisted prior value.
        val backing = FakeDataStore()

        // 1) Persist V1 successfully through a normal store over the backing data.
        val goodStore = DataStoreSaveLocationStore(backing)
        goodStore.put(appId, previous)
        assertEquals(previous, goodStore.get(appId))

        // 2) Attempt to persist V2 through a store whose writes fail but whose reads delegate to the
        //    same backing data.
        val failingStore = DataStoreSaveLocationStore(FailingDataStore(backing))
        assertThrows(java.io.IOException::class.java) {
            runBlocking { failingStore.put(appId, attempted) }
        }

        // 3) The prior value is intact: neither the failing store nor a fresh store over the same
        //    backing data observes V2.
        assertEquals(previous, failingStore.get(appId))
        assertEquals(previous, DataStoreSaveLocationStore(backing).get(appId))
    }
}
