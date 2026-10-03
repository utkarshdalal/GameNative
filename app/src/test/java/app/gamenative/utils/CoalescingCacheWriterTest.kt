package app.gamenative.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingCacheWriterTest {
    private fun withWriterScope(test: suspend (CoroutineScope) -> Unit) = runBlocking {
        withTimeout(5_000L) {
            val scope = CoroutineScope(coroutineContext + Job())
            try { test(scope) } finally { scope.cancel() }
        }
    }

    @Test fun coalescesBurstsAndSnapshotsTheLatestValueIncludingClear() = withWriterScope { scope ->
        val permits = Channel<Unit>(Channel.UNLIMITED)
        val writes = Channel<Int>(Channel.UNLIMITED)
        var value = 1
        val writer = CoalescingCacheWriter(
            scope, { value }, { writes.send(it) }, { throw it }, wait = { permits.receive() },
        )
        writer.schedule()
        yield()
        repeat(20) {
            value++
            writer.schedule()
        }
        permits.send(Unit)
        assertEquals(21, writes.receive())
        yield()
        assertEquals(true, writes.tryReceive().isFailure)
        value = 0 // A clear must not leave a previously queued snapshot on disk.
        writer.schedule()
        permits.send(Unit)
        assertEquals(0, writes.receive())
    }

    @Test fun updatesDuringPersistenceAreWrittenAfterTheInFlightWrite() = withWriterScope { scope ->
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val writes = Channel<Int>(Channel.UNLIMITED)
        var value = 1
        val writer = CoalescingCacheWriter(
            scope, { value }, {
                if (it == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                writes.send(it)
            }, { throw it }, wait = {},
        )
        writer.schedule()
        firstStarted.await()
        value = 2
        writer.schedule()
        value = 3
        writer.schedule()
        releaseFirst.complete(Unit)
        assertEquals(1, writes.receive())
        assertEquals(3, writes.receive())
    }

    @Test fun failedWriteDoesNotKillFuturePersistence() = withWriterScope { scope ->
        val failed = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Int>()
        var attempts = 0
        val writer = CoalescingCacheWriter(
            scope, { 42 }, {
                if (attempts++ == 0) error("Disk unavailable")
                saved.complete(it)
            }, { failed.complete(Unit) }, wait = {},
        )
        writer.schedule()
        failed.await()
        writer.schedule()
        assertEquals(42, saved.await())
    }
}
