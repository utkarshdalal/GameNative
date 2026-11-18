package app.gamenative.utils

import app.gamenative.PrefManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HltbCacheTest {

    private val sampleStats = HltbService.Stats(
        mainHours = "10.0",
        mainPlusHours = "15.0",
        completeHours = "25.0",
        allStylesHours = "12.0",
        gameId = 42,
    )

    @Before
    fun setUp() {
        mockkObject(PrefManager)
        every { PrefManager.hltbCache } returns "{}"
        every { PrefManager.hltbCache = any() } just runs
        HltbCache.reset()
    }

    @After
    fun tearDown() {
        unmockkObject(PrefManager)
    }

    @Test
    fun get_returnsStoredStatsForNormalizedKeys() {
        HltbCache.put("Hollow Knight!", sampleStats)

        listOf("Hollow Knight!", "hollow knight", "HOLLOW KNIGHT", "Hollow Knight")
            .forEach { key ->
                assertNotNull(HltbCache.get(key))
                assertEquals(sampleStats, HltbCache.get(key))
            }
    }

    @Test
    fun get_returnsNullForMissingEntry() {
        assertNull(HltbCache.get("Unknown Game"))
    }

    @Test
    fun put_evictsOldestWhenCapReached() {
        repeat(HltbCache.MAX_ENTRIES) { index ->
            HltbCache.put("Game $index", sampleStats)
        }
        assertNotNull(HltbCache.get("Game 0"))

        HltbCache.put("Overflow Game", sampleStats)

        assertNull(HltbCache.get("Game 0"))
        assertNotNull(HltbCache.get("Overflow Game"))
    }

    @Test
    fun reset_clearsAllEntries() {
        HltbCache.put("Halo", sampleStats)
        HltbCache.reset()
        assertNull(HltbCache.get("Halo"))
    }
}
