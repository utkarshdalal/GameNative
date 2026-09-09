package app.gamenative.savebackup

import app.gamenative.enums.PathType
import app.gamenative.utils.FakeDataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Example tests for [DataStoreSaveLocationStore.remove] — the "reset save backup settings" support
 * on the container side.
 *
 * Plain JUnit 4 example tests (no Robolectric, no `PrefManager.init`). The store's `suspend`
 * `put`/`get`/`remove` are driven with [runBlocking] over an in-memory [FakeDataStore]. Two
 * behaviors are pinned:
 *
 *  1. `remove` clears a previously persisted location so a subsequent `get` returns `null`, letting
 *     the resolver fall back to auto-resolution / the container browser next time.
 *  2. `remove` on an absent key is a harmless no-op, and only affects the targeted key.
 */
class SaveLocationStoreRemoveTest {

    @Test
    fun removeClearsPersistedLocation() = runBlocking {
        val store = DataStoreSaveLocationStore(FakeDataStore())
        val appId = "STEAM_440"
        store.put(appId, SaveLocation(PathType.WinSavedGames, "MyGame/Slot1"))
        assertEquals(SaveLocation(PathType.WinSavedGames, "MyGame/Slot1"), store.get(appId))

        store.remove(appId)

        assertNull("remove should clear the persisted location", store.get(appId))
    }

    @Test
    fun removeOnAbsentKeyIsNoOpAndScopedToKey() = runBlocking {
        val store = DataStoreSaveLocationStore(FakeDataStore())
        val kept = "GOG_1207658924"
        store.put(kept, SaveLocation(PathType.WinMyDocuments, "Saves/A"))

        // Removing a key that was never set does nothing and leaves other keys intact.
        store.remove("EPIC_absent")
        assertNull(store.get("EPIC_absent"))
        assertEquals(SaveLocation(PathType.WinMyDocuments, "Saves/A"), store.get(kept))
    }
}
