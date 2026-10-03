package app.gamenative.utils

import app.gamenative.data.CommunityRatingDistribution
import app.gamenative.data.GameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BulkGameStatsCacheTest {
    private val data = mapOf(
        GameSource.STEAM to mapOf(
            "Game" to DeviceGameStatsService.DeviceGameStats(10, 60, 2, 100, CommunityRatingDistribution(oneStar = 3, fiveStar = 2)),
        ),
    )

    @Test fun roundTripsFullHistogramAndDoesNotRefetchFreshData() = runBlocking {
        var stored = "{}"
        val cache = BulkGameStatsCache({ stored }, { stored = it }, "legacy/gpu")
        assertTrue(cache.refresh { data })
        val restored = BulkGameStatsCache({ stored }, { stored = it }, "legacy/gpu")
        restored.initialize()
        assertEquals(data, restored.getAll())
        var fetched = false
        assertTrue(
            restored.refresh {
                fetched = true
                emptyMap()
            },
        )
        assertFalse(fetched)
    }

    @Test fun scopeAndSchemaChangesInvalidateOldCache() = runBlocking {
        var stored = "{}"
        BulkGameStatsCache({ stored }, { stored = it }, "legacy").refresh { data }
        assertTrue(BulkGameStatsCache({ stored }, {}, "modern").also { it.initialize() }.getAll().isEmpty())
        stored = stored.replace("\"schema\":2", "\"schema\":1")
        assertFalse(BulkGameStatsCache({ stored }, {}, "legacy").also { it.initialize() }.isAvailable())
    }

    @Test fun emptySuccessIsCachedButFailureDoesNotEraseEvidence() = runBlocking {
        var stored = "{}"
        var now = 100L
        val cache = BulkGameStatsCache({ stored }, { stored = it }, "test", { now })
        cache.refresh { data }
        now += BulkGameStatsCache.TTL_MS
        assertFalse(cache.refresh { null })
        assertEquals(data, cache.getAll())
        assertTrue(cache.refresh(force = true) { emptyMap() })
        assertTrue(cache.isAvailable())
        assertTrue(cache.getAll().isEmpty())
        cache.clear()
        assertFalse(cache.isAvailable())
    }

    @Test fun repeatedPageOpensRespectFailureCooldownAndExplicitRefreshBypassesIt() = runBlocking {
        var now = 100L
        var calls = 0
        val cache = BulkGameStatsCache({ "{}" }, {}, "test", { now }, { 60_100L })
        suspend fun attempt(force: Boolean = false) = cache.refresh(force) {
            calls++
            null
        }
        assertFalse(attempt())
        repeat(10) { assertFalse(attempt()) }
        assertEquals(1, calls)
        now += 60_100L
        assertFalse(attempt())
        assertEquals(2, calls)
        assertFalse(attempt(force = true))
        assertEquals(3, calls)
    }

    @Test fun readersDoNotWaitForPersistenceAndClearCannotBeOverwrittenByAnOlderSave() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var stored = "{}"
        val cache = BulkGameStatsCache({ stored }, {
            if (it != "{}") {
                writing.complete(Unit)
                release.await()
            }
            stored = it
        }, "test")
        val refresh = async(Dispatchers.IO) { cache.refresh { data } }
        writing.await()
        withTimeout(2_000L) {
            assertEquals(data, async(Dispatchers.Default) { cache.getAll() }.await())
            // Already-initialized consumers must not join an in-flight network/save operation.
            async(Dispatchers.Default) { cache.initialize() }.await()
        }
        val clear = async { cache.clear() }
        release.complete(Unit)
        assertTrue(refresh.await())
        clear.await()
        assertEquals("{}", stored)
        assertFalse(cache.isAvailable())
    }

    @Test fun diskInitializationRunsWithoutBlockingReaders() = runBlocking {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cache = BulkGameStatsCache({
            reading.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            "{}"
        }, {}, "test")
        val init = async(Dispatchers.IO) { cache.initialize() }
        check(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
        try {
            withTimeout(2_000L) {
                assertTrue(async(Dispatchers.Default) { cache.getAll().isEmpty() }.await())
            }
        } finally {
            release.countDown()
        }
        init.await()
    }

    @Test fun explicitRefreshNeverBypassesServerCooldown() = runBlocking {
        var now = 100L
        var calls = 0
        val cache = BulkGameStatsCache({ "{}" }, {}, "test", { now })
        suspend fun attempt(force: Boolean) = cache.refresh(force) {
            if (++calls == 1) throw GameCompatibilityService.RateLimited(600_000L)
            data
        }
        assertFalse(attempt(false))
        now += 60_000L
        assertFalse(attempt(false))
        assertFalse(attempt(true))
        assertEquals(1, calls)
        now = 600_101L
        assertTrue(attempt(false))
        assertEquals(2, calls)
    }
}
