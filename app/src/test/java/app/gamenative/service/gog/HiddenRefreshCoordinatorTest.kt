package app.gamenative.service.gog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenRefreshCoordinatorTest {

    @Test
    fun beginAssignsIncreasingGenerations() {
        val coordinator = HiddenRefreshCoordinator()

        val first = coordinator.begin()
        val second = coordinator.begin()

        assertTrue(second > first)
        assertTrue(coordinator.isLatest(second))
        assertFalse(coordinator.isLatest(first))
    }

    @Test
    fun isLatestTrueForTheOnlyGeneration() {
        val coordinator = HiddenRefreshCoordinator()
        val generation = coordinator.begin()

        assertTrue(coordinator.isLatest(generation))
    }
}
