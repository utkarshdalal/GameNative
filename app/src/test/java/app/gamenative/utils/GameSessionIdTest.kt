package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GameSessionIdTest {

    @Before
    fun reset() {
        GameSessionId.bind()
        GameSessionId.end()
    }

    @Test
    fun launchedIdIsBoundAndReportedAtExit() {
        val launched = GameSessionId.newLaunch()
        assertNull(GameSessionId.active())
        assertEquals(launched, GameSessionId.bind())
        assertEquals(launched, GameSessionId.active())
        GameSessionId.end()
        assertNull(GameSessionId.active())
    }

    @Test
    fun launchDuringRunningSessionDoesNotLeakIntoItsExit() {
        val first = GameSessionId.newLaunch()
        GameSessionId.bind()
        val second = GameSessionId.newLaunch()
        assertNotEquals(first, second)
        assertEquals(first, GameSessionId.active())
        GameSessionId.end()
        assertEquals(second, GameSessionId.bind())
    }

    @Test
    fun rebindOfRunningSessionKeepsItsId() {
        val id = GameSessionId.newLaunch()
        GameSessionId.bind()
        assertEquals(id, GameSessionId.bind())
    }

    @Test
    fun sessionWithoutLaunchEventGetsFreshId() {
        val old = GameSessionId.newLaunch()
        GameSessionId.bind()
        GameSessionId.end()
        val fresh = GameSessionId.bind()
        assertNotNull(fresh)
        assertNotEquals(old, fresh)
    }
}
