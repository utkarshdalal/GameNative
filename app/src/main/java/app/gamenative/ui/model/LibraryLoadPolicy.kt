package app.gamenative.ui.model

/** Pure rules for how the library list loads and refreshes. */
internal object LibraryLoadPolicy {

    /**
     * Whether a filter pass shows the loading state (skeletons). Only while nothing is on
     * screen yet: once a list was shown, a refresh keeps it and updates it in place.
     */
    fun showLoading(shownItemCount: Int): Boolean = shownItemCount == 0

    /**
     * The hero slot's content. Once this session's fetch resolved, its pick decides (a featured
     * campaign over a recommendation, or nothing). Until then the slot as last shown stands in,
     * so the card is there with the first list and is replaced in place, not inserted later.
     */
    fun <F : Any, R : Any> heroSlot(
        freshResolved: Boolean,
        freshFeatured: F?,
        freshRecommendation: R?,
        cachedFeatured: F?,
        cachedRecommendation: R?,
    ): Pair<F?, R?> = when {
        freshResolved -> freshFeatured to (if (freshFeatured != null) null else freshRecommendation)
        cachedFeatured != null -> cachedFeatured to null
        else -> null to cachedRecommendation
    }

    /**
     * The frosted pre-consent teaser day rule: never over a featured slot, only before consent,
     * every day until the first "Not now", then that day and one day in three.
     */
    fun isTeaserDay(
        hasFeatured: Boolean,
        showRecommendations: Boolean,
        disclosureShown: Boolean,
        dismissedDay: Long,
        daySeed: Long,
    ): Boolean = !hasFeatured &&
        showRecommendations &&
        !disclosureShown &&
        (dismissedDay == 0L || dismissedDay == daySeed || daySeed % 3 == 0L)
}
