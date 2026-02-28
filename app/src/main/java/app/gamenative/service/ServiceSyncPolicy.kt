package app.gamenative.service

object ServiceSyncPolicy {

    fun shouldSyncOnColdStart(
        hasPerformedInitialSync: Boolean,
        lastSyncTimestamp: Long,
        syncThrottleMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!hasPerformedInitialSync) return true
        return now - lastSyncTimestamp >= syncThrottleMillis
    }

    fun shouldSyncForAction(
        action: String?,
        manualSyncAction: String,
        autoSyncAction: String,
        hasPerformedInitialSync: Boolean,
        lastSyncTimestamp: Long,
        syncThrottleMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        return when (action) {
            manualSyncAction -> true
            autoSyncAction -> true
            null -> shouldSyncOnColdStart(
                hasPerformedInitialSync = hasPerformedInitialSync,
                lastSyncTimestamp = lastSyncTimestamp,
                syncThrottleMillis = syncThrottleMillis,
                now = now,
            )
            else -> false
        }
    }

    fun remainingThrottleMinutes(
        lastSyncTimestamp: Long,
        syncThrottleMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val remainingMs = (lastSyncTimestamp + syncThrottleMillis - now).coerceAtLeast(0L)
        return remainingMs / 1000 / 60
    }
}
