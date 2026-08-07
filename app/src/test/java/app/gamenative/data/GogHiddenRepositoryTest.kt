package app.gamenative.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.gamenative.PrefManager
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUpdateCount
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GogHiddenRepositoryTest {

    @Before
    fun setUp() {
        installFakePrefManager(FakeDataStore())
        GogHiddenRepository.clear()
    }

    @Test
    fun noCacheStartsAsNull() {
        assertNull(GogHiddenRepository.hiddenIds.value)
    }

    @Test
    fun loadFromCacheWithNoCacheStaysNull() {
        GogHiddenRepository.loadFromCache()
        assertNull(GogHiddenRepository.hiddenIds.value)
    }

    @Test
    fun validCacheRestoresIds() {
        val initial = mutablePreferencesOf(
            stringPreferencesKey("library_gog_hidden_ids") to "1\u001F2",
        )
        installFakePrefManager(FakeDataStore(initial))

        GogHiddenRepository.loadFromCache()

        assertEquals(setOf("1", "2"), GogHiddenRepository.hiddenIds.value)
    }

    @Test
    fun updateChangesFlowAndPersistence() {
        val fake = FakeDataStore()
        installFakePrefManager(fake)

        GogHiddenRepository.update(setOf("3", "4"))

        assertEquals(setOf("3", "4"), GogHiddenRepository.hiddenIds.value)
        fake.awaitUpdateCount()
        assertEquals(setOf("3", "4"), PrefManager.libraryGogHiddenIds)
    }

    @Test
    fun clearResetsFlowAndPersistence() {
        val fake = FakeDataStore()
        installFakePrefManager(fake)
        GogHiddenRepository.update(setOf("3", "4"))

        GogHiddenRepository.clear()

        assertNull(GogHiddenRepository.hiddenIds.value)
        fake.awaitUpdateCount(2)
        assertEquals(emptySet<String>(), PrefManager.libraryGogHiddenIds)
    }
}
