package app.gamenative.service.cloud

import app.gamenative.data.GameSource
import java.util.concurrent.ConcurrentHashMap
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

    private val jobs = ConcurrentHashMap<String, Job>()

    fun keyOf(source: GameSource, numericId: Int): String = "${source.name}_$numericId"

    fun track(appId: String, job: Job) {
        jobs[appId] = job
        // remove(key, value) so a later sync's job isn't dropped by an earlier one completing.
        job.invokeOnCompletion { jobs.remove(appId, job) }
    }

    fun inFlight(appId: String): Job? = jobs[appId]?.takeIf { it.isActive }

    // call BEFORE deleting anything the sync reads. failure/cancel also completes the join -- we only
    // need it to stop touching the files.
    suspend fun awaitIdle(appId: String) {
        val job = inFlight(appId) ?: return
        Timber.tag("CloseSyncTracker").i(
            "appId=%s: waiting for the in-flight close-time cloud sync before deleting its files",
            appId,
        )
        runCatching { job.join() }
            .onFailure { Timber.tag("CloseSyncTracker").w(it, "appId=%s: join failed, proceeding", appId) }
        Timber.tag("CloseSyncTracker").i("appId=%s: close-time sync settled, delete may proceed", appId)
    }
}
