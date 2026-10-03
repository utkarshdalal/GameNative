package app.gamenative.utils

import org.junit.Assert.*
import org.junit.Test

class CompatibilityCachePolicyTest {
    @Test fun pruningKeepsFreshAndStaleFallbacksUntilTheExistingDeadline() {
        val now = CompatibilityCachePolicy.LAST_KNOWN_MS + 10_000L
        val responses = mutableMapOf("fresh" to Any(), "stale" to Any(), "lastKnown" to Any(), "expired" to Any(), "future" to Any())
        val original = responses.toMap()
        val timestamps = mutableMapOf(
            "fresh" to now, "stale" to now - CompatibilityCachePolicy.FRESH_MS,
            "lastKnown" to now - CompatibilityCachePolicy.LAST_KNOWN_MS + 1,
            "expired" to now - CompatibilityCachePolicy.LAST_KNOWN_MS, "future" to now + 1,
        )
        assertEquals(2, CompatibilityCachePolicy.pruneExpired(responses, timestamps, now))
        assertEquals(setOf("fresh", "stale", "lastKnown"), responses.keys)
        assertEquals(responses.keys, timestamps.keys)
        responses.forEach { (name, response) -> assertSame(original[name], response) }
        assertEquals(0, CompatibilityCachePolicy.pruneExpired(responses, timestamps, now))
        assertEquals(1, CompatibilityCachePolicy.pruneExpired(responses, timestamps, now + 1))
    }

    @Test fun largeValidLibrariesAreNotEvictedByAnArbitraryEntryLimit() {
        val now = CompatibilityCachePolicy.LAST_KNOWN_MS * 2
        val responses = (0 until 50_003).associate { "Game $it" to it }.toMutableMap()
        val timestamps = responses.keys.associateWith { now - CompatibilityCachePolicy.FRESH_MS }.toMutableMap()
        assertEquals(0, CompatibilityCachePolicy.pruneExpired(responses, timestamps, now))
        assertEquals(50_003, responses.size)
        assertEquals(50_003, timestamps.size)
        assertEquals(
            50_003,
            CompatibilityCachePolicy.pruneExpired(
                responses, timestamps,
                now + CompatibilityCachePolicy.LAST_KNOWN_MS,
            ),
        )
        assertTrue(responses.isEmpty())
        assertTrue(timestamps.isEmpty())
    }

    @Test fun freshAndStaleWindowsAreDistinctAndBounded() {
        val written = 1000L
        assertTrue(CompatibilityCachePolicy.isFresh(written, written))
        val expires = written + CompatibilityCachePolicy.FRESH_MS
        assertFalse(CompatibilityCachePolicy.isFresh(written, expires))
        assertTrue(CompatibilityCachePolicy.canDisplay(written, expires))
        assertFalse(CompatibilityCachePolicy.canDisplay(written, written + CompatibilityCachePolicy.LAST_KNOWN_MS))
    }

    @Test fun futureTimestampsAreNeverTrusted() {
        assertFalse(CompatibilityCachePolicy.isFresh(1001, 1000))
        assertFalse(CompatibilityCachePolicy.canDisplay(1001, 1000))
    }
}
