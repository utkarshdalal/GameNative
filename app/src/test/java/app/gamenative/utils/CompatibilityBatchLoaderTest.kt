package app.gamenative.utils

import app.gamenative.utils.GameCompatibilityService.GameCompatibilityResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CompatibilityBatchLoaderTest {
    private val cache = mutableMapOf<String, GameCompatibilityResponse>()
    private var requests = 0
    private var now = 100L

    private fun loader(
        fetch: suspend (List<String>, String) -> Map<String, GameCompatibilityResponse>?,
        clock: () -> Long = System::currentTimeMillis,
        wait: suspend (Long) -> Unit = { delay(it) },
        timeoutMillis: Long = 45_000L,
        retryDelay: (Long) -> Long = { CompatibilityRetryPolicy.withJitter(it) },
    ) = CompatibilityBatchLoader(
        cache::get, cache::putAll, { names, gpu -> requests++; fetch(names, gpu) }, {}, clock, wait, timeoutMillis, retryDelay,
    )
    private fun response(name: String, state: String = "Works") =
        GameCompatibilityResponse(name, state, "gpu")

    @Test fun twentyThousandGamesUseBoundedBatchesAndCachedRevisitsMakeNoRequests() = runBlocking {
        var active = 0
        var maximumActive = 0
        val loader = loader({ names, _ ->
            active++
            maximumActive = maxOf(maximumActive, active)
            assertTrue(names.size <= 100)
            yield()
            val results = names.associateWith(::response)
            active--
            results
        })
        val names = (0 until 20_001).map { "Game $it" }
        assertTrue(loader.refresh(names, "GPU"))
        assertEquals(201, requests)
        assertEquals(1, maximumActive)
        assertEquals(names.size, cache.size)
        assertTrue(loader.refresh(names.reversed(), "GPU"))
        assertTrue(loader.refresh(names.takeLast(50), "GPU"))
        assertEquals(201, requests)
        assertFalse(loader.isRefreshing())
    }

    @Test fun concurrentDetailAndLibraryRequestsReuseTheSameCache() = runBlocking {
        val loader = loader({ names, _ ->
            kotlinx.coroutines.yield()
            names.associateWith(::response)
        })
        listOf(async { loader.refresh(listOf("A", "B"), "GPU") }, async { loader.refresh(listOf("A"), "GPU") }).awaitAll()
        assertEquals(1, requests)
    }

    @Test fun failuresAreNotUnknownAndDoNotHammerTheRemainingBatches() = runBlocking {
        val loader = loader({ _, _ ->
            null
        }, clock = { now })
        val names = (1..251).map { "Game $it" }
        assertFalse(loader.refresh(names, "GPU"))
        assertTrue(cache.isEmpty())
        assertTrue(loader.hasFailed(names.last()))
        assertFalse(loader.refresh(names, "GPU"))
        assertEquals(1, requests)
        now += 60_000
        loader.refresh(names, "GPU")
        assertEquals(2, requests)
    }

    @Test fun missingEntriesAreNotInventedButExplicitUntestedIsCached() = runBlocking {
        val loader = loader({ _, _ ->
            mapOf("Untested" to response("Untested", "Untested"))
        })
        assertFalse(loader.refresh(listOf("Missing", "Untested"), "GPU"))
        assertFalse(cache.containsKey("Missing"))
        assertTrue(loader.hasFailed("Missing"))
        assertEquals("Untested", cache["Untested"]?.state)
    }

    @Test fun honorsRateLimitThenRetriesWithoutPublishingAnInterimVerdict() = runBlocking {
        val waits = mutableListOf<Long>()
        val loader = loader({ names, _ ->
            if (requests <= 2) throw GameCompatibilityService.RateLimited(10_000L)
            names.associateWith(::response)
        }, wait = { waits += it }, retryDelay = { it + 123L })
        assertTrue(loader.refresh(listOf("Game"), "GPU"))
        assertEquals(listOf(10_123L, 10_123L), waits)
        assertEquals(3, requests)
    }

    @Test fun failedManualRefreshPreservesCachedVerdictButReportsFailure() = runBlocking {
        cache["Game"] = response("Game", "Broken")
        val loader = loader({ _, _ -> null })
        assertFalse(loader.refresh(listOf("Game"), "GPU", force = true))
        assertEquals("Broken", cache["Game"]?.state)
    }

    @Test fun cancellationPropagatesWithoutMarkingUntested() = runBlocking {
        val loader = loader({ _, _ -> throw CancellationException() })
        try {
            loader.refresh(listOf("Game"), "GPU")
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(cache.isEmpty())
            assertFalse(loader.isLoading("Game"))
            assertFalse(loader.isRefreshing())
        }
    }

    @Test fun aDetailRequestDoesNotWaitForTheWholeLibrary() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<List<String>>()
        val loader = loader({ names, _ ->
            batches += names
            if (batches.size == 1) {
                started.complete(Unit)
                release.await()
            }
            names.associateWith(::response)
        })
        val library = async { loader.refresh((1..250).map { "Game $it" }, "GPU") }
        started.await()
        assertTrue(loader.isLoading("Game 250"))
        val detail = async { loader.refresh(listOf("Detail"), "GPU") }
        yield()
        release.complete(Unit)
        assertTrue(detail.await())
        assertTrue(library.await())
        assertEquals(listOf("Detail"), batches[1])
        assertFalse(loader.isRefreshing())
    }

    @Test fun timeoutStopsCheckingAndKeepsAlreadyPublishedBatches() = runBlocking {
        val loader = loader({ names, _ ->
            if (requests > 1) delay(10_000)
            names.associateWith(::response)
        }, timeoutMillis = 30L)
        assertFalse(loader.refresh((1..250).map { "Game $it" }, "GPU"))
        assertEquals(100, cache.size)
        assertTrue(loader.hasFailed("Game 250"))
        assertFalse(loader.isLoading("Game 250"))
        assertFalse(loader.isRefreshing())
    }

    @Test fun unexpectedFetchErrorDoesNotKillSubsequentRefreshes() = runBlocking {
        var fail = true
        val loader = loader({ names, _ ->
            if (fail) error("Bad response")
            names.associateWith(::response)
        })
        assertFalse(loader.refresh(listOf("Game"), "GPU"))
        assertFalse(loader.isRefreshing())
        fail = false
        assertTrue(loader.refresh(listOf("Game"), "GPU", force = true))
        assertFalse(loader.hasFailed("Game"))
    }

    @Test fun longRateLimitSurvivesNewPassesOtherConsumersAndForcedRefresh() = runBlocking {
        val loader = loader({ names, _ ->
            if (requests == 1) throw GameCompatibilityService.RateLimited(600_000L)
            names.associateWith(::response)
        }, clock = { now }, retryDelay = { it }, wait = { fail("Long cooldown must not hold the loader mutex") })
        assertFalse(loader.refresh(listOf("A"), "GPU"))
        assertFalse(loader.isRefreshing())
        now += 60_000L
        assertFalse(loader.refresh(listOf("A"), "GPU"))
        assertFalse(loader.refresh(listOf("B"), "GPU", force = true))
        assertEquals(1, requests)
        now = 600_101L
        assertTrue(loader.refresh(listOf("A", "B"), "GPU"))
        assertEquals(2, requests)
    }

    @Test fun queuedConsumerRechecksRateLimitAfterAcquiringTheBatchLock() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = loader({ _, _ ->
            started.complete(Unit)
            release.await()
            throw GameCompatibilityService.RateLimited(600_000L)
        }, retryDelay = { it })
        val first = async { loader.refresh(listOf("A"), "GPU") }
        started.await()
        val queued = async { loader.refresh(listOf("B"), "GPU", force = true) }
        yield()
        release.complete(Unit)
        assertFalse(first.await())
        assertFalse(queued.await())
        assertEquals(1, requests)
    }

    @Test fun retryBudgetCoversAllAttemptsRatherThanRestartingEachTime() = runBlocking {
        val waits = mutableListOf<Long>()
        val loader = loader({ _, _ ->
            throw GameCompatibilityService.RateLimited(30_000L)
        }, clock = { now }, wait = {
            waits += it
            now += it
        }, retryDelay = { it })
        assertFalse(loader.refresh(listOf("A"), "GPU"))
        assertEquals(2, requests)
        assertEquals(listOf(30_000L), waits)
        now += 1_000L
        assertFalse(loader.refresh(listOf("B"), "GPU", force = true))
        assertEquals(2, requests)
    }

    @Test fun queuedConsumersReuseFailureCooldownWithoutBlockingOtherNames() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val batches = mutableListOf<List<String>>()
        val loader = loader({ names, _ ->
            batches += names
            if (batches.size == 1) {
                started.complete(Unit)
                release.await()
                null
            } else {
                names.associateWith(::response)
            }
        })
        val first = async { loader.refresh(listOf("A"), "GPU") }
        started.await()
        val queued = async { loader.refresh(listOf("A", "B"), "GPU") }
        yield()
        release.complete(Unit)
        assertFalse(first.await())
        assertFalse(queued.await())
        assertEquals(listOf(listOf("A"), listOf("B")), batches)
        assertTrue(loader.hasFailed("A"))
        assertEquals("Works", cache["B"]?.state)
        assertFalse(loader.isRefreshing())
    }

    @Test fun explicitRetryCanRecoverAQueuedFailureWithoutLeavingAFailureFlag() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = loader({ names, _ ->
            if (requests == 1) {
                started.complete(Unit)
                release.await()
                null
            } else {
                names.associateWith(::response)
            }
        })
        val first = async { loader.refresh(listOf("A"), "GPU") }
        started.await()
        val forced = async { loader.refresh(listOf("A"), "GPU", force = true) }
        yield()
        release.complete(Unit)
        assertFalse(first.await())
        assertTrue(forced.await())
        assertEquals(2, requests)
        assertFalse(loader.hasFailed("A"))
    }

    @Test fun oldRunOnlyPayloadCannotOverwriteAKnownVerdict() = runBlocking {
        cache["Game"] = response("Game", "Broken")
        val loader = loader({ _, _ ->
            GameCompatibilityService.parseCompatibilityResponse("""{"Game":{"total":42,"gpu":42}}""", listOf("Game"))
        })
        assertFalse(loader.refresh(listOf("Game"), "GPU", force = true))
        assertEquals("Broken", cache["Game"]?.state)
    }
}
