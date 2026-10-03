package app.gamenative.ui.model

import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LibraryPublicationTest {
    @Test fun scrollThatStartsDuringRebuildIsRecheckedOnThePublicationThread() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            var scrolling = false
            val checked = CompletableDeferred<Unit>()
            var publications = 0
            assertFalse(scrolling) // Earlier IO-side check is no longer sufficient.
            withContext(ui) { scrolling = true }
            val update = async {
                publishLibraryWhenIdle({
                    checked.complete(Unit)
                    scrolling
                }, ui) {
                    assertFalse(scrolling)
                    publications++
                }
            }
            checked.await()
            withContext(ui) {
                assertEquals(0, publications)
                scrolling = false
            }
            withTimeout(2_000L) { update.await() }
            assertEquals(1, publications)
        }
    }

    @Test fun cancelledRebuildNeverPublishesAfterScrollingStops() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
            var scrolling = true
            val checked = CompletableDeferred<Unit>()
            var published = false
            val update = async {
                publishLibraryWhenIdle({
                    checked.complete(Unit)
                    scrolling
                }, ui) { published = true }
            }
            checked.await()
            update.cancelAndJoin()
            withContext(ui) { scrolling = false }
            assertFalse(published)
        }
    }
}
