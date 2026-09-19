package app.gamenative.service.gog

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.gamenative.PrefManager
import app.gamenative.data.GOGGame
import app.gamenative.events.AndroidEvent
import app.gamenative.PluviaApp
import app.gamenative.db.PluviaDatabase
import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.installFakePrefManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GOGServiceHiddenSyncTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private var database: PluviaDatabase? = null
    private var prefScope: AutoCloseable? = null

/** Restores shared state and releases test resources. */
    @After
    fun tearDown() {
        GOGService.credentialClearerForLogout = { serviceContext ->
            GOGService.clearStoredCredentials(serviceContext)
        }
        GOGService.logoutStopper = { GOGService.stop() }
        prefScope?.close()
        database?.close()
    }

/** Verifies the behavior described by this test: disabling Hidden Games Requests Both Uninitialized Sources Through Service. */
    @Test
    fun disablingHiddenGamesRequestsBothUninitializedSourcesThroughService() {
        withService { service ->
            val requested = AtomicReference<Set<GogHiddenSource>?>(null)
            val refreshStarted = CountDownLatch(1)
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = { sources ->
                requested.set(sources)
                refreshStarted.countDown()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)

            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
            assertEquals(
                setOf(GogHiddenSource.GOG_COM, GogHiddenSource.GALAXY),
                requested.get(),
            )
        }
    }

/** Verifies the behavior described by this test: disabling Hidden Games Requests Only The Uninitialized Source Through Service. */
    @Test
    fun disablingHiddenGamesRequestsOnlyTheUninitializedSourceThroughService() {
        withService { service ->
            val requested = AtomicReference<Set<GogHiddenSource>?>(null)
            val refreshStarted = CountDownLatch(1)
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 9L to 0L }
            service.hiddenRefreshForInitialization = { sources ->
                requested.set(sources)
                refreshStarted.countDown()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)

            assertTrue(refreshStarted.await(2, TimeUnit.SECONDS))
            assertEquals(setOf(GogHiddenSource.GALAXY), requested.get())
        }
    }

/** Verifies the behavior described by this test: disabling Hidden Games Does Not Refresh When Both Sources Are Initialized. */
    @Test
    fun disablingHiddenGamesDoesNotRefreshWhenBothSourcesAreInitialized() {
        withService { service ->
            val refreshCalls = AtomicInteger(0)
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 9L to 10L }
            service.hiddenRefreshForInitialization = {
                refreshCalls.incrementAndGet()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)
            Thread.sleep(150)

            assertEquals(0, refreshCalls.get())
        }
    }

/** Verifies the behavior described by this test: disabling Hidden Games Does Not Refresh Without Credentials. */
    @Test
    fun disablingHiddenGamesDoesNotRefreshWithoutCredentials() {
        withService { service ->
            val refreshCalls = AtomicInteger(0)
            service.hiddenCredentialsAvailableForInitialization = { false }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = {
                refreshCalls.incrementAndGet()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)
            Thread.sleep(150)

            assertEquals(0, refreshCalls.get())
        }
    }

/** Verifies the behavior described by this test: disabling Hidden Games Does Not Refresh While Full Sync Is Active. */
    @Test
    fun disablingHiddenGamesDoesNotRefreshWhileFullSyncIsActive() {
        withService { service ->
            val refreshCalls = AtomicInteger(0)
            service.hiddenInitializationSyncActive = { true }
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = {
                refreshCalls.incrementAndGet()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)
            Thread.sleep(150)

            assertEquals(0, refreshCalls.get())
        }
    }

/** Verifies the behavior described by this test: enabling Hidden Games Does Not Start Initialization. */
    @Test
    fun enablingHiddenGamesDoesNotStartInitialization() {
        withService { service ->
            val refreshCalls = AtomicInteger(0)
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = {
                refreshCalls.incrementAndGet()
                GogHiddenRefreshResult(emptyMap())
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = true)
            Thread.sleep(150)

            assertEquals(0, refreshCalls.get())
        }
    }

/** Verifies the behavior described by this test: failed Initialization Can Be Retried By ALater Setting Event. */
    @Test
    fun failedInitializationCanBeRetriedByALaterSettingEvent() {
        withService { service ->
            val refreshCalls = AtomicInteger(0)
            val firstRefresh = CountDownLatch(1)
            val secondRefresh = CountDownLatch(1)
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = {
                when (refreshCalls.incrementAndGet()) {
                    1 -> firstRefresh.countDown()
                    2 -> secondRefresh.countDown()
                }
                throw IllegalStateException("hidden source unavailable")
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)
            assertTrue(firstRefresh.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)

            assertTrue(secondRefresh.await(2, TimeUnit.SECONDS))
            assertEquals(2, refreshCalls.get())
        }
    }

/** Verifies the behavior described by this test: toggle Initialization Forwards Source Failures To The Service Logger. */
    @Test
    fun toggleInitializationForwardsSourceFailuresToTheServiceLogger() {
        withService { service ->
            val logged = AtomicReference<GogHiddenRefreshResult?>(null)
            val loggedResult = CountDownLatch(1)
            val expected = GogHiddenRefreshResult(
                mapOf(
                    GogHiddenSource.GALAXY to GogHiddenSourceResult.Failure(
                        IllegalStateException("Galaxy unavailable"),
                    ),
                ),
            )
            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 9L }
            service.hiddenRefreshForInitialization = { expected }
            service.hiddenIssueLogger = { result ->
                logged.set(result)
                loggedResult.countDown()
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)

            assertTrue(loggedResult.await(2, TimeUnit.SECONDS))
            assertEquals(expected, logged.get())
        }
    }

/** Verifies the behavior described by this test: service Unsubscribes From Hidden Games Setting Events On Destroy. */
    @Test
    fun serviceUnsubscribesFromHiddenGamesSettingEventsOnDestroy() {
        val eventClass = AndroidEvent.HiddenGamesSettingChanged::class
        val listenersBefore = PluviaApp.events.listeners[eventClass]?.size ?: 0

        withService {
            assertEquals(listenersBefore + 1, PluviaApp.events.listeners[eventClass]?.size)
        }

        assertEquals(listenersBefore, PluviaApp.events.listeners[eventClass]?.size ?: 0)
    }

/** Verifies the behavior described by this test: full Sync Preserves Composite Hidden Result Across The Service Boundary. */
    @Test
    fun fullSyncPreservesCompositeHiddenResultAcrossTheServiceBoundary() {
        withService { service ->
            val expected = GogHiddenRefreshResult(
                mapOf(
                    GogHiddenSource.GOG_COM to GogHiddenSourceResult.Failure(
                        IllegalStateException("gog.com unavailable"),
                    ),
                ),
            )
            val received = AtomicReference<GogHiddenRefreshResult?>(null)
            val resultHandled = CountDownLatch(1)
            service.fullSyncRunner = {
                GogFullSyncResult(
                    library = GogLibraryRefreshOutcome.Success(gamesProcessed = 4),
                    hidden = expected,
                )
            }
            service.hiddenIssueLogger = { result ->
                received.set(result)
                resultHandled.countDown()
            }

            service.onStartCommand(
                Intent(context, GOGService::class.java).apply {
                    action = "app.gamenative.GOG_MANUAL_SYNC"
                },
                0,
                1,
            )

            assertTrue(resultHandled.await(2, TimeUnit.SECONDS))
            assertEquals(expected, received.get())
        }
    }

/** Verifies the behavior described by this test: manual Full Sync Cancels Toggle Initialization Before Entering Full Sync. */
    @Test
    fun manualFullSyncCancelsToggleInitializationBeforeEnteringFullSync() {
        withService { service ->
            val toggleStarted = CountDownLatch(1)
            val toggleFinished = CountDownLatch(1)
            val fullSyncStarted = CountDownLatch(1)
            val toggleGate = CompletableDeferred<Unit>()
            val hiddenSyncActive = AtomicBoolean(false)
            val overlapDetected = AtomicBoolean(false)
            val toggleCancelled = AtomicBoolean(false)
            val fullSyncObservedToggleFinished = AtomicBoolean(false)

            service.hiddenCredentialsAvailableForInitialization = { true }
            service.hiddenInitializationTimestamps = { 0L to 0L }
            service.hiddenRefreshForInitialization = {
                hiddenSyncActive.set(true)
                toggleStarted.countDown()
                try {
                    toggleGate.await()
                } catch (error: CancellationException) {
                    toggleCancelled.set(true)
                    throw error
                } finally {
                    hiddenSyncActive.set(false)
                    toggleFinished.countDown()
                }
                GogHiddenRefreshResult(emptyMap())
            }
            service.hiddenIssueLogger = {}
            service.fullSyncRunner = {
                fullSyncObservedToggleFinished.set(
                    toggleFinished.await(0, TimeUnit.MILLISECONDS),
                )
                if (hiddenSyncActive.get()) overlapDetected.set(true)
                fullSyncStarted.countDown()
                GogFullSyncResult(
                    library = GogLibraryRefreshOutcome.Success(gamesProcessed = 1),
                    hidden = GogHiddenRefreshResult(emptyMap()),
                )
            }

            emitHiddenGamesSettingChanged(showHiddenGamesByDefault = false)
            assertTrue(toggleStarted.await(2, TimeUnit.SECONDS))

            service.onStartCommand(
                Intent(context, GOGService::class.java).apply {
                    action = "app.gamenative.GOG_MANUAL_SYNC"
                },
                0,
                1,
            )

            assertTrue(fullSyncStarted.await(2, TimeUnit.SECONDS))
            assertTrue(toggleFinished.await(0, TimeUnit.MILLISECONDS))
            assertTrue(toggleCancelled.get())
            assertTrue(fullSyncObservedToggleFinished.get())
            assertFalse(overlapDetected.get())
        }
    }

/** Verifies the behavior described by this test: logout Cleans Database And Timestamps Even When Credential Deletion Fails. */
    @Test
    fun logoutCleansDatabaseAndTimestampsEvenWhenCredentialDeletionFails() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PluviaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database = db
        prefScope = installFakePrefManager(FakeDataStore())
        db.gogGameDao().insertAll(
            listOf(
                GOGGame(
                    id = "installed",
                    title = "Installed",
                    isInstalled = true,
                    installPath = "/games/installed",
                    gogComHidden = true,
                    galaxyHidden = true,
                ),
                GOGGame(
                    id = "non-installed",
                    title = "Non-installed",
                    gogComHidden = true,
                    galaxyHidden = true,
                ),
            ),
        )
        PrefManager.setLastSuccessfulGogComHiddenSync(11L)
        PrefManager.setLastSuccessfulGalaxyHiddenSync(22L)

        val manager = GOGManager(db.gogGameDao(), context)
        val cleanupObservedBeforeStop = AtomicBoolean(false)
        GOGService.credentialClearerForLogout = { false }
        GOGService.logoutStopper = {
            cleanupObservedBeforeStop.set(
                runBlocking {
                    val installed = db.gogGameDao().getById("installed")
                    installed != null &&
                        !installed.gogComHidden &&
                        !installed.galaxyHidden &&
                        PrefManager.getLastSuccessfulGogComHiddenSync() == 0L &&
                        PrefManager.getLastSuccessfulGalaxyHiddenSync() == 0L
                },
            )
        }

        val result = withService { service ->
            service.gogManager = manager
            runBlocking { GOGService.logout(context) }
        }

        assertTrue(result.isSuccess)
        assertTrue(cleanupObservedBeforeStop.get())
        assertEquals(null, db.gogGameDao().getById("non-installed"))
        assertFalse(db.gogGameDao().getById("installed")!!.hidden)
        assertEquals(0L, PrefManager.getLastSuccessfulGogComHiddenSync())
        assertEquals(0L, PrefManager.getLastSuccessfulGalaxyHiddenSync())
    }

/** Verifies the behavior described by this test: emit Hidden Games Setting Changed. */
    private fun emitHiddenGamesSettingChanged(showHiddenGamesByDefault: Boolean) {
        PluviaApp.events.emit(AndroidEvent.HiddenGamesSettingChanged(showHiddenGamesByDefault))
    }

    private fun <T> withService(block: (GOGService) -> T): T {
        val controller = Robolectric.buildService(GOGService::class.java).create()
        return try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }
}
