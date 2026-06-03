package app.gamenative.ui.model

internal object LibrarySortUtils {

    fun normalizeLastPlayedMillis(lastPlayed: Long): Long {
        if (lastPlayed <= 0L) return 0L
        return if (lastPlayed < 10_000_000_000L) lastPlayed * 1000L else lastPlayed
    }

    fun steamLastPlayedOrInstallFallback(
        steamLastPlayed: Long,
        isInstalled: Boolean,
        installedLastPlayed: Long,
    ): Long {
        val normalizedLastPlayed = normalizeLastPlayedMillis(steamLastPlayed)
        return when {
            normalizedLastPlayed > 0L -> normalizedLastPlayed
            isInstalled -> installedLastPlayed
            else -> 0L
        }
    }

    fun <T> recentlyPlayedComparator(
        name: (T) -> String,
        isInstalled: (T) -> Boolean,
        lastPlayed: (T) -> Long,
    ): Comparator<T> {
        return compareBy<T> { entry ->
            if (isInstalled(entry)) 0 else 1
        }.thenByDescending { entry ->
            lastPlayed(entry)
        }.thenBy { entry ->
            name(entry).lowercase()
        }
    }
}
