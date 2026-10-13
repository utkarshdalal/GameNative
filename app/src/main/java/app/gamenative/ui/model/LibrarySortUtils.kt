package app.gamenative.ui.model

internal object LibrarySortUtils {

    fun <T> compatibilityComparator(
        name: (T) -> String,
        isInstalled: (T) -> Boolean,
        summary: (T) -> app.gamenative.data.CommunityCompatibilitySummary?,
    ): Comparator<T> = compareBy<T> { if (isInstalled(it)) 0 else 1 }
        .thenBy { entry ->
            if (isInstalled(entry)) 0 else summary(entry)?.takeIf { it.verdictLoaded }?.verdict?.sortPriority ?: 6
        }.thenBy { name(it).lowercase() }

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
