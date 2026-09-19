package app.gamenative.service.gog

import org.junit.Assert.assertEquals
import org.junit.Test

class GogHiddenSyncModelsTest {

/** Verifies the behavior described by this test: initialization Policy Requests Both When Neither Source Has Succeeded. */
    @Test
    fun initializationPolicyRequestsBothWhenNeitherSourceHasSucceeded() {
        assertEquals(
            setOf(GogHiddenSource.GOG_COM, GogHiddenSource.GALAXY),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(0L, 0L),
        )
    }

/** Verifies the behavior described by this test: initialization Policy Requests Only Galaxy When Website Source Is Initialized. */
    @Test
    fun initializationPolicyRequestsOnlyGalaxyWhenWebsiteSourceIsInitialized() {
        assertEquals(
            setOf(GogHiddenSource.GALAXY),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(1L, 0L),
        )
    }

/** Verifies the behavior described by this test: initialization Policy Requests Only GOG.com When Galaxy Source Is Initialized. */
    @Test
    fun initializationPolicyRequestsOnlyGogComWhenGalaxySourceIsInitialized() {
        assertEquals(
            setOf(GogHiddenSource.GOG_COM),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(0L, 1L),
        )
    }

/** Verifies the behavior described by this test: initialization Policy Requests Nothing When Both Sources Are Initialized. */
    @Test
    fun initializationPolicyRequestsNothingWhenBothSourcesAreInitialized() {
        assertEquals(
            emptySet<GogHiddenSource>(),
            GogHiddenSyncPolicy.sourcesNeedingInitialization(1L, 1L),
        )
    }
}
