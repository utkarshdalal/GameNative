package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import java.util.Collections
import timber.log.Timber

// titles that write through Html5FsBridge keep their real saves on disk; their chromium LS/IDB is
// scratch, and syncing it would pollute cloud or burn the CURRENT-poll on empty shells. once a
// container is fs-authoritative, save sync routes to the FsBridge strategy regardless of profile.
//
// the on-disk marker survives process restarts so next-launch inbound sync can skip leveldb work.
// ROUTING_ENABLED=false forces leveldb rewrite anyway -- for checking whether this routing hides a bug.
object Html5FsAuthoritative {

    @Volatile
    @JvmField
    var ROUTING_ENABLED: Boolean = true

    private val inMemoryUsed: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    // called on every mutating fs op; the disk touch happens once per container per process.
    fun markUsed(context: Context, containerId: String) {
        if (!inMemoryUsed.add(containerId)) return
        runCatching {
            val f = markerFile(context, containerId)
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile()
            Timber.tag(TAG).i("marked fs-authoritative: containerId=%s marker=%s", containerId, f.absolutePath)
        }.onFailure { Timber.tag(TAG).w(it, "markUsed persist failed for containerId=%s", containerId) }
    }

    fun isFsAuthoritative(context: Context, containerId: String): Boolean {
        if (!ROUTING_ENABLED) return false
        if (containerId in inMemoryUsed) return true
        val diskHit = markerFile(context, containerId).isFile
        if (diskHit) inMemoryUsed.add(containerId)
        return diskHit
    }

    // uninstall cleanup.
    fun clear(context: Context, containerId: String) {
        inMemoryUsed.remove(containerId)
        runCatching { markerFile(context, containerId).delete() }
    }

    private fun markerFile(context: Context, containerId: String): File =
        File(context.filesDir, "html5/fs-used/$containerId")

    private const val TAG = "Html5FsAuthoritative"
}
