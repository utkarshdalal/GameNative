package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityRetryPolicyTest {
    @Test fun respectsServerMinimumAndBoundsExtraDelay() {
        assertEquals(10_000L, CompatibilityRetryPolicy.withJitter(10_000L, 0.0))
        assertEquals(11_000L, CompatibilityRetryPolicy.withJitter(10_000L, 0.5))
        assertEquals(12_000L, CompatibilityRetryPolicy.withJitter(10_000L, 1.0))
        assertEquals(70_000L, CompatibilityRetryPolicy.withJitter(60_000L, 1.0))
        assertEquals(310_000L, CompatibilityRetryPolicy.withJitter(300_000L, 1.0))
    }

    @Test fun parsesLongRetryAfterSecondsDatesAndMissingValuesWithoutOverflow() {
        assertEquals(600_000L, CompatibilityRetryPolicy.retryAfterMillis("600", 0L))
        assertEquals(7_200_000L, CompatibilityRetryPolicy.retryAfterMillis("7200", 0L))
        assertEquals(0L, CompatibilityRetryPolicy.retryAfterMillis("0", 0L))
        val now = java.time.Instant.parse("2026-09-27T10:00:00Z").toEpochMilli()
        assertEquals(600_000L, CompatibilityRetryPolicy.retryAfterMillis("Sun, 27 Sep 2026 10:10:00 GMT", now))
        assertEquals(10_000L, CompatibilityRetryPolicy.retryAfterMillis("Sun, 27 Sep 2026 09:00:00 GMT", now))
        assertEquals(10_000L, CompatibilityRetryPolicy.retryAfterMillis("Sun, 27 Sep 2026 10:00:00 GMT", now))
        for (value in listOf(null, "", "invalid", "-1")) {
            assertEquals(10_000L, CompatibilityRetryPolicy.retryAfterMillis(value, now))
        }
        assertEquals(Long.MAX_VALUE, CompatibilityRetryPolicy.retryAfterMillis(Long.MAX_VALUE.toString(), now))
        assertEquals(Long.MAX_VALUE, CompatibilityRetryPolicy.deadline(now, Long.MAX_VALUE))
    }
}
