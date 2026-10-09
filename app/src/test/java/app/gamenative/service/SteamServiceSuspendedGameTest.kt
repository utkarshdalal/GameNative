package app.gamenative.service

import app.gamenative.data.GameProcessInfo
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SteamServiceSuspendedGameTest {
    private val game = GameProcessInfo(appId = 220, processes = emptyList())
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(StandardTestDispatcher(scheduler))
    private var previousService: SteamService? = null

    // The app ids Steam was told are being played, one list per notification
    private val notified = mutableListOf<List<Int>>()

    @Before
    fun setUp() {
        previousService = SteamService.instance
        val service = mockk<SteamService>()
        // The countdown runs on the service's scope, so it follows the test clock
        SteamService::class.java.getDeclaredField("scope").apply { isAccessible = true }.set(service, scope)
        SteamService.instance = service
        mockkObject(SteamService.Companion)
        coEvery { SteamService.notifyRunningProcesses(*anyVararg()) } coAnswers {
            notified += firstArg<Array<GameProcessInfo>>().map { it.appId }
        }
        ActiveGameRegistry.set(game)
    }

    @After
    fun tearDown() {
        ActiveGameRegistry.clear()
        // The suspended state lives in the companion, so reset it for the next test
        SteamService.onGameProcessesResumed()
        scope.cancel()
        unmockkObject(SteamService.Companion)
        SteamService.instance = previousService
    }

    @Test
    fun `short suspension keeps the game playing`() {
        SteamService.onGameProcessesSuspended()
        passTime(59_999)
        SteamService.onGameProcessesResumed()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList<List<Int>>(), notified)
    }

    @Test
    fun `long suspension stops the playtime until the game resumes`() {
        SteamService.onGameProcessesSuspended()
        passTime(60_000)
        assertEquals(listOf(emptyList<Int>()), notified)

        SteamService.onGameProcessesResumed()
        scheduler.advanceUntilIdle()
        assertEquals(listOf(emptyList(), listOf(220)), notified)
    }

    @Test
    fun `suspending again does not start another countdown`() {
        SteamService.onGameProcessesSuspended()
        passTime(30_000)
        SteamService.onGameProcessesSuspended()
        passTime(30_000)
        assertEquals(listOf(emptyList<Int>()), notified)

        // Nor once the playtime has stopped
        SteamService.onGameProcessesSuspended()
        passTime(120_000)
        assertEquals(listOf(emptyList<Int>()), notified)
    }

    @Test
    fun `countdown of a game that exited does not touch the next one`() {
        SteamService.onGameProcessesSuspended()
        ActiveGameRegistry.clear()
        ActiveGameRegistry.set(GameProcessInfo(appId = 440, processes = emptyList()))
        passTime(60_000)

        assertEquals(emptyList<List<Int>>(), notified)
    }

    @Test
    fun `nothing is sent without an active game`() {
        ActiveGameRegistry.clear()
        SteamService.onGameProcessesSuspended()
        passTime(60_000)
        SteamService.onGameProcessesResumed()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList<List<Int>>(), notified)
    }

    private fun passTime(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }
}
