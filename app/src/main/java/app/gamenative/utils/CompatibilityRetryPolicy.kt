package app.gamenative.utils

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

internal object CompatibilityRetryPolicy {
    fun deadline(now: Long, delayMillis: Long): Long =
        now + delayMillis.coerceIn(0L, (Long.MAX_VALUE - now.coerceAtLeast(0L)).coerceAtLeast(0L))

    fun retryAfterMillis(header: String?, now: Long = System.currentTimeMillis()): Long {
        header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let {
            return if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000
        }
        return runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now
        }.getOrNull()?.coerceAtLeast(0L) ?: 10_000L
    }

    /** Spread recovery traffic without shortening the server's minimum wait. */
    fun withJitter(minimumMillis: Long, randomFraction: Double = Random.nextDouble()): Long {
        val minimum = minimumMillis.coerceAtLeast(0L)
        val extra = (minimum / 5).coerceAtMost(10_000L)
        return deadline(minimum, (extra * randomFraction.coerceIn(0.0, 1.0)).toLong())
    }
}
