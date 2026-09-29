package app.gamenative.service.gog

/** Identifies one authoritative GOG hidden-state source. */
enum class GogHiddenSource {
    GOG_COM,
    GALAXY,
}

/** A complete, validated snapshot of observations from one hidden-state source. */
data class GogHiddenSnapshot(
    val source: GogHiddenSource,
    val observations: Map<String, Boolean>,
)

/** The result of attempting one hidden-state source refresh. */
sealed interface GogHiddenSourceResult {
    /** The source snapshot was reconciled and its success timestamp was persisted. */
    data class Success(
        val observedCount: Int,
    ) : GogHiddenSourceResult

    /** The source attempt did not fully complete; Room may contain a committed reconciliation. */
    data class Failure(
        val error: Throwable,
    ) : GogHiddenSourceResult
}

/** Results for only the hidden sources requested by a caller. */
data class GogHiddenRefreshResult(
    val results: Map<GogHiddenSource, GogHiddenSourceResult>,
)

/** The outcome of the ownership/details portion of a full synchronization. */
sealed interface GogLibraryRefreshOutcome {
    /** The library refresh completed and processed [gamesProcessed] owned entries. */
    data class Success(
        val gamesProcessed: Int,
    ) : GogLibraryRefreshOutcome

    /** The library refresh failed. */
    data class Failure(
        val error: Throwable,
    ) : GogLibraryRefreshOutcome
}

/** Independent library and hidden outcomes from one complete GOG synchronization. */
data class GogFullSyncResult(
    val library: GogLibraryRefreshOutcome,
    val hidden: GogHiddenRefreshResult,
)

/** Selects only the hidden sources that have never completed initialization. */
object GogHiddenSyncPolicy {
    /** Returns the hidden sources that still need an initial successful synchronization. */
    fun sourcesNeedingInitialization(
        lastSuccessfulGogComHiddenSync: Long,
        lastSuccessfulGalaxyHiddenSync: Long,
    ): Set<GogHiddenSource> = buildSet {
        if (lastSuccessfulGogComHiddenSync == 0L) add(GogHiddenSource.GOG_COM)
        if (lastSuccessfulGalaxyHiddenSync == 0L) add(GogHiddenSource.GALAXY)
    }
}
