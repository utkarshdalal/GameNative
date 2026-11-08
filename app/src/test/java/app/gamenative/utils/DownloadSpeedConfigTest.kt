package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier tables feed two different consumers: the native engines use `maxDownloads` as an
 * adaptive-window CEILING, while the legacy Kotlin fallbacks need a plain request count. This test
 * pins both mappings so a future edit cannot silently turn 256 into a request count again, or
 * change the established fixed-tier limits.
 */
class DownloadSpeedConfigTest {

    @Test
    fun native_window_ceiling_matches_the_tier_table() {
        assertEquals(6, DownloadSpeedConfig.nativeMaxDownloadsFor(8))
        assertEquals(16, DownloadSpeedConfig.nativeMaxDownloadsFor(16))
        assertEquals(32, DownloadSpeedConfig.nativeMaxDownloadsFor(24))
        assertEquals(128, DownloadSpeedConfig.nativeMaxDownloadsFor(32))
        assertEquals(
            256,
            DownloadSpeedConfig.nativeMaxDownloadsFor(DownloadSpeedConfig.ADAPTIVE_SPEED),
        )
        assertEquals("unknown tiers fall back to the slow window", 6,
            DownloadSpeedConfig.nativeMaxDownloadsFor(1_000))
    }

    @Test
    fun fallback_requests_preserve_fixed_tiers_and_cap_adaptive() {
        assertEquals(6, DownloadSpeedConfig.fallbackRequestsFor(8))
        assertEquals(16, DownloadSpeedConfig.fallbackRequestsFor(16))
        assertEquals(32, DownloadSpeedConfig.fallbackRequestsFor(24))
        assertEquals(128, DownloadSpeedConfig.fallbackRequestsFor(32))
        // Adaptive's 256 is a window ceiling, never a request count.
        assertEquals(32, DownloadSpeedConfig.fallbackRequestsFor(DownloadSpeedConfig.ADAPTIVE_SPEED))
        assertTrue(
            DownloadSpeedConfig.fallbackRequestsFor(DownloadSpeedConfig.ADAPTIVE_SPEED) <= 32,
        )
        assertEquals(6, DownloadSpeedConfig.fallbackRequestsFor(1_000))
    }

    @Test
    fun plain_http_parallel_requests_use_the_tier_value_and_cap_adaptive() {
        assertEquals(8, DownloadSpeedConfig.parallelRequestsFor(8))
        assertEquals(16, DownloadSpeedConfig.parallelRequestsFor(16))
        assertEquals(24, DownloadSpeedConfig.parallelRequestsFor(24))
        assertEquals(32, DownloadSpeedConfig.parallelRequestsFor(32))
        assertEquals(32, DownloadSpeedConfig.parallelRequestsFor(DownloadSpeedConfig.ADAPTIVE_SPEED))
        assertEquals(1, DownloadSpeedConfig.parallelRequestsFor(0))
    }
}
