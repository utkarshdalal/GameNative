package app.gamenative.ui.data

import app.gamenative.data.LibraryItem
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class LibraryStatePublicationTest {
    @Test fun publicationDoesNotCompareTwentyThousandMetadataItemsOnTheUiThread() {
        installFakePrefManager(FakeDataStore()).use {
            var reads = 0
            fun items() = object : AbstractList<LibraryItem>() {
                override val size = 20_001
                override fun get(index: Int): LibraryItem {
                    reads++
                    error("State publication must not traverse library items")
                }
            }
            val original = LibraryState(appInfoList = items())
            val state = MutableStateFlow(original)
            val next = original.copy(libraryRevision = original.libraryRevision + 1, appInfoList = items())
            assertTrue(state.compareAndSet(original, next))
            assertSame(next, state.value)
            assertEquals(0, reads)
        }
    }

    @Test fun unchangedStateStillConflatesAndOtherUiUpdatesArePreserved() {
        installFakePrefManager(FakeDataStore()).use {
            val original = LibraryState()
            val state = MutableStateFlow(original)
            state.value = original.copy()
            assertSame(original, state.value)
            state.value = state.value.copy(searchQuery = "Portal")
            assertEquals("Portal", state.value.searchQuery)
            state.value = state.value.copy(libraryRevision = 1)
            assertEquals("Portal", state.value.searchQuery)
        }
    }
}
