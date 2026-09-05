package app.gamenative.steam.curated

import app.gamenative.PrefManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CuratedListRepositoryTest {

    private var storedCache = ""

    @Before
    fun setUp() = runBlocking {
        mockkObject(PrefManager)
        every { PrefManager.libraryCuratedListsCache } answers { storedCache }
        every { PrefManager.libraryCuratedListsCache = any() } answers {
            storedCache = firstArg()
        }
        CuratedListRepository.resetForTesting()
    }

    @After
    fun tearDown() = runBlocking {
        CuratedListRepository.resetForTesting()
        unmockkObject(PrefManager)
    }

    @Test
    fun emptyCachePublishesEmptyLists() = runBlocking {
        CuratedListRepository.loadFromCache()

        assertEquals(emptyMap<String, Set<Int>>(), CuratedListRepository.curatedLists.value)
    }

    @Test
    fun cacheRoundTripIncludesRefreshTime() {
        val lists = mapOf(CuratedListDescriptor.FOUR_THREE.id to setOf(30, 10, 20))

        val decoded = CuratedListRepository.decodeCache(
            CuratedListRepository.encodeCache(lists, refreshedAtMs = 1234L),
        )

        assertEquals(1234L, decoded?.refreshedAtMs)
        assertEquals(lists, decoded?.lists)
    }

    @Test
    fun oldAndMalformedCacheFormatsAreRejected() {
        assertNull(CuratedListRepository.decodeCache("{\"curated:4-3\":[1,2]}"))
        assertNull(CuratedListRepository.decodeCache("{not valid json"))
        assertNull(
            CuratedListRepository.decodeCache(
                "{\"version\":1,\"refreshedAtMs\":1234,\"lists\":{}}",
            ),
        )
    }

    @Test
    fun failedRefreshKeepsCachedListAndThrottlesAnotherAttempt() = runBlocking {
        val cachedAppIds = setOf(730, 240)
        storedCache = CuratedListRepository.encodeCache(
            lists = mapOf(CuratedListDescriptor.FOUR_THREE.id to cachedAppIds),
            refreshedAtMs = 0L,
        )
        CuratedListRepository.loadFromCache()

        CuratedListRepository.refreshFourThreeIfNeeded(
            fetch = { null },
            nowMs = { 1_000L },
        )

        assertEquals(
            cachedAppIds,
            CuratedListRepository.curatedLists.value?.get(CuratedListDescriptor.FOUR_THREE.id),
        )
        assertFalse(CuratedListRepository.isRefreshDue(nowMs = 1_000L))
        assertTrue(CuratedListRepository.isRefreshDue(nowMs = 1_000L + 24L * 60L * 60L * 1000L))
    }

    @Test
    fun loadingCacheDoesNotClearFailedRefreshBackoff() = runBlocking {
        CuratedListRepository.loadFromCache()
        CuratedListRepository.refreshFourThreeIfNeeded(
            fetch = { null },
            nowMs = { 1_000L },
        )

        CuratedListRepository.loadFromCache()

        assertFalse(CuratedListRepository.isRefreshDue(nowMs = 1_000L))
    }

    @Test
    fun successfulRefreshReplacesSnapshotAndStartsTtl() = runBlocking {
        storedCache = CuratedListRepository.encodeCache(
            lists = mapOf(CuratedListDescriptor.FOUR_THREE.id to setOf(999)),
            refreshedAtMs = 0L,
        )
        CuratedListRepository.loadFromCache()

        CuratedListRepository.refreshFourThreeIfNeeded(
            fetch = { setOf(730, 240) },
            nowMs = { 10_000L },
        )

        val appIds = CuratedListRepository.curatedLists.value
            ?.get(CuratedListDescriptor.FOUR_THREE.id)
        assertEquals(setOf(730, 240), appIds)
        assertFalse(CuratedListRepository.isRefreshDue(nowMs = 10_000L + 23L * 60L * 60L * 1000L))
        assertTrue(CuratedListRepository.isRefreshDue(nowMs = 10_000L + 24L * 60L * 60L * 1000L))
        assertEquals(10_000L, CuratedListRepository.decodeCache(storedCache)?.refreshedAtMs)
    }

    @Test
    fun concurrentRefreshesOnlyFetchOnce() = runBlocking {
        CuratedListRepository.loadFromCache()
        val calls = AtomicInteger()

        coroutineScope {
            List(2) {
                async {
                    CuratedListRepository.refreshFourThreeIfNeeded(
                        fetch = {
                            calls.incrementAndGet()
                            delay(25)
                            null
                        },
                        nowMs = { 1_000L },
                    )
                }
            }.awaitAll()
        }

        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationIsPropagatedWithoutThrottlingNextAttempt() = runBlocking {
        CuratedListRepository.loadFromCache()

        val failure = runCatching {
            CuratedListRepository.refreshFourThreeIfNeeded(
                fetch = { throw CancellationException("cancelled") },
                nowMs = { 1_000L },
            )
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(CuratedListRepository.isRefreshDue(nowMs = 1_000L))
    }

    @Test
    fun futureRefreshTimeDoesNotBlockAnUpdate() = runBlocking {
        storedCache = CuratedListRepository.encodeCache(
            lists = mapOf(CuratedListDescriptor.FOUR_THREE.id to setOf(730)),
            refreshedAtMs = 2_000L,
        )
        CuratedListRepository.loadFromCache()

        assertTrue(CuratedListRepository.isRefreshDue(nowMs = 1_000L))
    }
}
