package app.gamenative.service.cloud

import app.gamenative.data.GameSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// uninstall can delete the install dir while the close-time upload is still reading it, aborting
// the sync with the manifest missing. awaitIdle is what stops the delete from starting until the
// upload is done with those files.
class CloseSyncTrackerTest {

    @Test
    fun awaitIdle_waitsForAnInFlightSync() = runTest {
        val uploadReachedManifest = CompletableDeferred<Unit>()
        val deleteStarted = CompletableDeferred<Unit>()
        var deletedWhileUploading = false

        val sync = async {
            uploadReachedManifest.complete(Unit)
            delay(50)
            // the delete must not have run yet.
            deletedWhileUploading = deleteStarted.isCompleted
        }
        CloseSyncTracker.track(APP_ID, sync)

        uploadReachedManifest.await()
        CloseSyncTracker.awaitIdle(APP_ID)
        deleteStarted.complete(Unit)

        sync.await()
        assertFalse("delete ran while the upload still had files open", deletedWhileUploading)
    }

    @Test
    fun awaitIdle_returnsImmediatelyWhenNothingInFlight() = runTest {
        CloseSyncTracker.awaitIdle("STEAM_offset1")
        assertNull(CloseSyncTracker.inFlight("STEAM_offset1"))
    }

    @Test
    fun awaitIdle_returnsWhenTheSyncFailed() = runTest {
        // a sync that blew up still released the files, so the delete must proceed, not hang.
        // CompletableDeferred rather than a throwing async: a failing child would take the test's
        // own scope down with it, which says nothing about the tracker.
        val failed = CompletableDeferred<Unit>()
        CloseSyncTracker.track("STEAM_offset2", failed)
        failed.completeExceptionally(IllegalStateException("upload died"))

        CloseSyncTracker.awaitIdle("STEAM_offset2")
        assertTrue(failed.isCompleted)
    }

    @Test
    fun completedSyncIsNotReportedInFlight() = runBlocking {
        val done = Job().apply { complete() }
        CloseSyncTracker.track("STEAM_offset3", done)
        assertNull("a settled sync must not gate a delete", CloseSyncTracker.inFlight("STEAM_offset3"))
    }

    @Test
    fun aSecondSyncIsNotEvictedWhenTheFirstCompletes() = runBlocking {
        // exit -> relaunch -> exit again in one process: the first sync completing must not clear
        // the entry the second one just wrote, or the delete would sail past a live upload.
        val first = Job()
        val second = Job()
        CloseSyncTracker.track("STEAM_offset4", first)
        CloseSyncTracker.track("STEAM_offset4", second)

        first.complete()

        assertSame("second sync must still gate the delete", second, CloseSyncTracker.inFlight("STEAM_offset4"))
        second.complete()
        assertNull(CloseSyncTracker.inFlight("STEAM_offset4"))
    }

    @Test
    fun awaitIdle_returnsWhenTheSyncWasCancelled() = runTest {
        // offline/cancelled sync released the files too -- the delete must proceed, not hang.
        val cancelled = Job()
        CloseSyncTracker.track("STEAM_offset5", cancelled)
        cancelled.cancel()

        CloseSyncTracker.awaitIdle("STEAM_offset5")
        assertTrue(cancelled.isCompleted)
    }

    @Test
    fun trackedSyncsAreScopedPerApp() = runBlocking<Unit> {
        // deleting game A while game B is mid-upload must not wait on B, and vice versa.
        val other = Job()
        CloseSyncTracker.track("STEAM_offset6", other)

        assertNull("unrelated appId must not see another game's sync", CloseSyncTracker.inFlight("STEAM_offset7"))
        assertSame(other, CloseSyncTracker.inFlight("STEAM_offset6"))
        other.complete()
    }

    @Test
    fun storesDoNotCollideOnTheSameNumericId() {
        // Steam and GOG number their games independently, so a map keyed on the bare int could let
        // one store's delete join -- or worse, miss -- another store's upload. canonical ids keep
        // them apart.
        val steam = Job()
        val gog = Job()
        CloseSyncTracker.track("STEAM_$COLLIDING_NUMERIC", steam)
        CloseSyncTracker.track("GOG_$COLLIDING_NUMERIC", gog)

        assertSame(steam, CloseSyncTracker.inFlight("STEAM_$COLLIDING_NUMERIC"))
        assertSame(gog, CloseSyncTracker.inFlight("GOG_$COLLIDING_NUMERIC"))

        steam.complete()
        assertNull("steam settling must not release the GOG entry", CloseSyncTracker.inFlight("STEAM_$COLLIDING_NUMERIC"))
        assertSame("GOG upload still gates its own delete", gog, CloseSyncTracker.inFlight("GOG_$COLLIDING_NUMERIC"))
        gog.complete()
    }

    @Test
    fun keyOfBuildsTheCanonicalContainerId() {
        assertEquals("STEAM_440", CloseSyncTracker.keyOf(GameSource.STEAM, 440))
        assertEquals("GOG_1252295864", CloseSyncTracker.keyOf(GameSource.GOG, 1252295864))
        assertEquals("EPIC_12", CloseSyncTracker.keyOf(GameSource.EPIC, 12))
    }

    @Test
    fun everyStoreUsesTheSameRegistry() = runTest {
        // parity: Steam, GOG and Epic all upload at exit and all have a reachable delete path.
        listOf("STEAM_1", "GOG_2", "EPIC_3").forEach { id ->
            val sync = Job()
            CloseSyncTracker.track(id, sync)
            assertSame(sync, CloseSyncTracker.inFlight(id))
            sync.complete()
            CloseSyncTracker.awaitIdle(id)
            assertNull(CloseSyncTracker.inFlight(id))
        }
    }

    private companion object {
        const val APP_ID = "STEAM_379210"
        const val COLLIDING_NUMERIC = 379210
    }
}
