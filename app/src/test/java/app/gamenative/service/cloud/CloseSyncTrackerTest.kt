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
        assertTrue(CloseSyncTracker.inFlight("STEAM_offset1").isEmpty())
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
        assertTrue("a settled sync must not gate a delete", CloseSyncTracker.inFlight("STEAM_offset3").isEmpty())
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

        assertEquals("second sync must still gate the delete", listOf(second), CloseSyncTracker.inFlight("STEAM_offset4"))
        second.complete()
        assertTrue(CloseSyncTracker.inFlight("STEAM_offset4").isEmpty())
    }

    @Test
    fun awaitIdle_waitsForAnEarlierSyncWhenALaterOneFinishesFirst() = runTest {
        // overlapping exits: the later sync bails at once (one already running) but the earlier one is still
        // uploading. the delete must wait for BOTH, not just the newest entry.
        val earlier = Job()
        val later = Job()
        CloseSyncTracker.track("STEAM_offset8", earlier)
        CloseSyncTracker.track("STEAM_offset8", later)
        later.complete()

        val delete = async { CloseSyncTracker.awaitIdle("STEAM_offset8") }
        delay(50)
        assertFalse("delete ran while the earlier sync was still uploading", delete.isCompleted)

        earlier.complete()
        delete.await()
        assertTrue(CloseSyncTracker.inFlight("STEAM_offset8").isEmpty())
    }

    @Test
    fun awaitIdle_propagatesCancellationOfTheCaller() = runTest {
        // a cancelled uninstall must not fall through to its delete.
        val sync = Job()
        CloseSyncTracker.track("STEAM_offset9", sync)
        var fellThrough = false

        val delete = async {
            CloseSyncTracker.awaitIdle("STEAM_offset9")
            fellThrough = true
        }
        delay(50)
        delete.cancel()
        delete.join()

        assertTrue(delete.isCancelled)
        assertFalse("delete continued after its caller was cancelled", fellThrough)
        sync.complete()
    }

    @Test
    fun reserve_gatesTheDeleteUntilCompleted() = runBlocking {
        val closeSync = CloseSyncTracker.reserve("GOG_offset10")
        assertEquals(listOf(closeSync), CloseSyncTracker.inFlight("GOG_offset10"))
        closeSync.complete()
        assertTrue(CloseSyncTracker.inFlight("GOG_offset10").isEmpty())
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

        assertTrue("unrelated appId must not see another game's sync", CloseSyncTracker.inFlight("STEAM_offset7").isEmpty())
        assertEquals(listOf(other), CloseSyncTracker.inFlight("STEAM_offset6"))
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

        assertEquals(listOf(steam), CloseSyncTracker.inFlight("STEAM_$COLLIDING_NUMERIC"))
        assertEquals(listOf(gog), CloseSyncTracker.inFlight("GOG_$COLLIDING_NUMERIC"))

        steam.complete()
        assertTrue("steam settling must not release the GOG entry", CloseSyncTracker.inFlight("STEAM_$COLLIDING_NUMERIC").isEmpty())
        assertEquals("GOG upload still gates its own delete", listOf(gog), CloseSyncTracker.inFlight("GOG_$COLLIDING_NUMERIC"))
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
            assertEquals(listOf(sync), CloseSyncTracker.inFlight(id))
            sync.complete()
            CloseSyncTracker.awaitIdle(id)
            assertTrue(CloseSyncTracker.inFlight(id).isEmpty())
        }
    }

    private companion object {
        const val APP_ID = "STEAM_379210"
        const val COLLIDING_NUMERIC = 379210
    }
}
