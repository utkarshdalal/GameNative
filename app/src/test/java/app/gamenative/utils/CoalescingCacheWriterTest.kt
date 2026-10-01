package app.gamenative.utils

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingCacheWriterTest {
    @Test fun coalescesBurstsAndSnapshotsTheLatestValueIncludingClear() = runBlocking {
        withTimeout(5_000L) {
            val job = Job()
            val permits = Channel<Unit>(Channel.UNLIMITED)
            val writes = Channel<Int>(Channel.UNLIMITED)
            var value = 1
            val writer = CoalescingCacheWriter(
                CoroutineScope(coroutineContext + job), { value }, { writes.send(it) }, { throw it },
                wait = { permits.receive() },
            )
            try {
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
            } finally {
                job.cancel()
            }
        }
    }

    @Test fun updatesDuringPersistenceAreWrittenAfterTheInFlightWrite() = runBlocking {
        withTimeout(5_000L) {
            val job = Job()
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val writes = Channel<Int>(Channel.UNLIMITED)
            var value = 1
            val writer = CoalescingCacheWriter(
                CoroutineScope(coroutineContext + job), { value }, {
                    if (it == 1) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                    writes.send(it)
                }, { throw it }, wait = {},
            )
            try {
                writer.schedule()
                firstStarted.await()
                value = 2
                writer.schedule()
                value = 3
                writer.schedule()
                releaseFirst.complete(Unit)
                assertEquals(1, writes.receive())
                assertEquals(3, writes.receive())
            } finally {
                job.cancel()
            }
        }
    }

    @Test fun failedWriteDoesNotKillFuturePersistence() = runBlocking {
        withTimeout(5_000L) {
            val job = Job()
            val failed = CompletableDeferred<Unit>()
            val saved = CompletableDeferred<Int>()
            var attempts = 0
            val writer = CoalescingCacheWriter(
                CoroutineScope(coroutineContext + job), { 42 }, {
                    if (attempts++ == 0) error("Disk unavailable")
                    saved.complete(it)
                }, { failed.complete(Unit) }, wait = {},
            )
            try {
                writer.schedule()
                failed.await()
                writer.schedule()
                assertEquals(42, saved.await())
            } finally {
                job.cancel()
            }
        }
    }
}
