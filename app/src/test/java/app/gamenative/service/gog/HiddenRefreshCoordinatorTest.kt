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
    }

    @Test
    fun canCommitAllowsTheFirstGeneration() {
        val coordinator = HiddenRefreshCoordinator()
        val generation = coordinator.begin()

        assertTrue(coordinator.canCommit(generation))
    }

    @Test
    fun markCommittedMakesOlderGenerationsIneligible() {
        val coordinator = HiddenRefreshCoordinator()
        val first = coordinator.begin()
        val second = coordinator.begin()

        coordinator.markCommitted(second)

        assertFalse(coordinator.canCommit(first))
        assertFalse(coordinator.canCommit(second))
        assertTrue(coordinator.canCommit(coordinator.begin()))
    }

    @Test
    fun failedNewerRefreshDoesNotInvalidateOlderGeneration() {
        val coordinator = HiddenRefreshCoordinator()
        val first = coordinator.begin()
        coordinator.begin() // newer refresh that never commits (e.g. fetch failure)

        assertTrue(coordinator.canCommit(first))
    }
}
