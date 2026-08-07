package app.gamenative.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.gamenative.PrefManager
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.mockito.Mockito

/** In-memory [DataStore] so unit tests can observe [PrefManager] writes. */
class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial.toMutablePreferences())
    private val mutex = Mutex()

    /** Number of completed `updateData` calls; used to await asynchronous [PrefManager] writes. */
    val updates = MutableStateFlow(0)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        // Serialize like the real DataStore so concurrent edit() calls cannot lose keys.
        return mutex.withLock {
            val updated = transform(state.value).toMutablePreferences()
            state.value = updated
            updates.value += 1
            updated
        }
    }
}

/** Waits until at least [expectedUpdates] writes have landed in [FakeDataStore]. */
fun FakeDataStore.awaitUpdateCount(expectedUpdates: Int = 1) {
    runBlocking { updates.first { it >= expectedUpdates } }
}

/**
 * Replaces [PrefManager]'s backing store with [fake] so preference defaults, reads, and writes are
 * deterministic in unit tests.
 */
fun installFakePrefManager(fake: FakeDataStore) {
    val context = Mockito.mock(Context::class.java)
    val filesDir = File(System.getProperty("java.io.tmpdir"), "gamenative-pref-test-${System.nanoTime()}")
    filesDir.mkdirs()
    Mockito.`when`(context.filesDir).thenReturn(filesDir)
    Mockito.`when`(context.dataDir).thenReturn(filesDir)
    Mockito.`when`(context.applicationContext).thenReturn(context)

    PrefManager.init(context)

    val dataStoreField = PrefManager::class.java.getDeclaredField("dataStore")
    dataStoreField.isAccessible = true
    dataStoreField.set(PrefManager, fake)
}
