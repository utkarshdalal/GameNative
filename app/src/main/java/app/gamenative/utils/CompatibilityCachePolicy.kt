package app.gamenative.utils

/** Cache freshness is not evidence recency. Never extend the evidence age on a failed fetch. */
internal object CompatibilityCachePolicy {
    const val FRESH_MS = 6 * 60 * 60 * 1000L
    const val LAST_KNOWN_MS = 24 * 60 * 60 * 1000L

    fun isFresh(timestamp: Long, now: Long): Boolean = now - timestamp in 0 until FRESH_MS

    fun canDisplay(timestamp: Long, now: Long): Boolean = now - timestamp in 0 until LAST_KNOWN_MS

    /** Remove only unusable entries; a six-hour-old result remains available for offline fallback. */
    fun <T> pruneExpired(
        responses: MutableMap<String, T>,
        timestamps: MutableMap<String, Long>,
        now: Long,
    ): Int {
        var removed = 0
        val iterator = timestamps.iterator()
        while (iterator.hasNext()) {
            val (name, timestamp) = iterator.next()
            if (!canDisplay(timestamp, now)) {
                iterator.remove()
                responses.remove(name)
                removed++
            }
        }
        return removed
    }
}
