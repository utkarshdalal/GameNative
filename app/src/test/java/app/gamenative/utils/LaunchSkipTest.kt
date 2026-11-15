package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchSkipTest {
    @Test fun anOlderStepCannotWithdrawANewerOffer() {
        val older = Any()
        val newer = Any()
        var skipped = 0
        LaunchSkip.offer(older) {}
        LaunchSkip.offer(newer) { skipped++ }

        LaunchSkip.withdraw(older)

        val action = LaunchSkip.action.value
        assertNotNull(action)
        action!!()
        action()
        assertEquals(1, skipped)
        assertNull(LaunchSkip.action.value)
        LaunchSkip.withdraw(newer)
    }

    @Test fun theOwnerWithdrawsItsOffer() {
        val owner = Any()
        LaunchSkip.offer(owner) {}

        LaunchSkip.withdraw(owner)

        assertNull(LaunchSkip.action.value)
    }
}
