package app.gamenative.service.cloud

import app.gamenative.data.GameSource
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import timber.log.Timber

// in-flight close-time cloud syncs, so an uninstall can't delete a game out from under its own upload.
// the close sync uploads STRAIGHT OUT of the install dir while the user is already back in the library;
// deleting mid-upload leaves cloud with a half-sent leveldb (new tables beside the PREVIOUS manifest).
//
// the join is deliberately unbounded: the upload caps itself (retries, per-request timeouts), and
// amputating a cloud save to keep uninstall snappy is exactly the trade this prevents.
//
// keyed by container id, not the bare numeric id: stores number their games independently.
object CloseSyncTracker {

    // a SET per key: overlapping exits of the same game each hold their own sync, and the delete must
    // outlast all of them, not just the newest.
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    fun keyOf(source: GameSource, numericId: Int): String = "${source.name}_$numericId"

    // registered synchronously by the caller, BEFORE any suspension, so an uninstall that starts
    // right after exit still sees it. the caller completes it once the close-time sync is done.
    fun reserve(appId: String): CompletableJob = Job().also { track(appId, it) }

    fun track(appId: String, job: Job) {
        jobs.compute(appId) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(job) } }
        job.invokeOnCompletion {
            jobs.computeIfPresent(appId) { _, set -> set.apply { remove(job) }.takeIf { it.isNotEmpty() } }
        }
    }

    fun inFlight(appId: String): List<Job> = jobs[appId]?.filter { it.isActive }.orEmpty()

    // call BEFORE deleting anything the sync reads. failure/cancel of the sync also completes the join --
    // we only need it to stop touching the files. cancellation of the CALLER propagates, so a cancelled
    // uninstall never goes on to delete.
    suspend fun awaitIdle(appId: String) {
        var pending = inFlight(appId)
        if (pending.isEmpty()) return
        Timber.tag("CloseSyncTracker").i(
            "appId=%s: waiting for %d in-flight close-time cloud sync(s) before deleting its files",
            appId,
            pending.size,
        )
        while (pending.isNotEmpty()) {
            pending.forEach { it.join() }
            pending = inFlight(appId)
        }
        Timber.tag("CloseSyncTracker").i("appId=%s: close-time sync settled, delete may proceed", appId)
    }
}
