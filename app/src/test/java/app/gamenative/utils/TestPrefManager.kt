package app.gamenative.utils

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.gamenative.PrefManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [DataStore] so unit tests can observe [PrefManager] writes. */
class FakeDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial.toMutablePreferences())
    private val mutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        // Serialize like the real DataStore so concurrent edit() calls cannot lose keys.
        return mutex.withLock {
            val updated = transform(state.value).toMutablePreferences()
            state.value = updated
            updated
        }
    }
}

/** Polls [condition] until it returns true or [timeoutMs] elapses; fails the test on timeout. */
fun awaitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() <= deadline) { "Timed out after ${timeoutMs}ms waiting for condition" }
        Thread.sleep(10)
    }
}

/** Restores [PrefManager]'s original backing store when closed. */
class PrefManagerTestScope(
    private val dataStoreField: java.lang.reflect.Field,
    private val originalStore: Any?,
) : AutoCloseable {
    override fun close() {
        dataStoreField.set(PrefManager, originalStore)
    }
}

/**
 * Replaces [PrefManager]'s backing store with [fake] so preference defaults, reads, and writes are
 * deterministic in unit tests. Returns a scope that restores the previous store, preventing the
 * fake from leaking into other tests in the shared JVM.
 */
fun installFakePrefManager(fake: FakeDataStore): PrefManagerTestScope {
    val dataStoreField = PrefManager::class.java.getDeclaredField("dataStore")
    dataStoreField.isAccessible = true
    val originalStore = dataStoreField.get(PrefManager)
    dataStoreField.set(PrefManager, fake)
    return PrefManagerTestScope(dataStoreField, originalStore)
}
