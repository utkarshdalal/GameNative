package app.gamenative.data

import app.gamenative.PrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cache of GOG product IDs the user has hidden on GOG.
 *
 * Semantics of [hiddenIds]:
 * - `null` means hidden metadata has not been loaded yet, so filtering fails open.
 * - `emptySet` means a successful sync found no hidden games.
 * - otherwise the set holds the hidden product IDs from the last successful sync.
 */
object GogHiddenRepository {
    private val _hiddenIds = MutableStateFlow<Set<String>?>(null)
    val hiddenIds: StateFlow<Set<String>?> = _hiddenIds.asStateFlow()

    /** Loads persisted hidden IDs. With no persisted cache the flow stays null (fail open). */
    fun loadFromCache() {
        val cached = PrefManager.libraryGogHiddenIds
        _hiddenIds.value = if (cached.isEmpty()) null else cached
    }

    /** Publishes a successful sync result and persists it. */
    fun update(ids: Set<String>) {
        PrefManager.libraryGogHiddenIds = ids
        _hiddenIds.value = ids
    }

    /** Clears both in-memory and persisted state (e.g. on GOG logout). */
    fun clear() {
        PrefManager.libraryGogHiddenIds = emptySet()
        _hiddenIds.value = null
    }
}
