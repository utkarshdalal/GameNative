package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import java.util.Collections
import timber.log.Timber

// runtime sniff for whether a
// container's title uses the Node fs bridge. titles that call fs.* (via Html5FsBridge) write
// their canonical save state to disk through the bridge; chromium LS/IDB for those titles is
// runtime scratch, not save data. sync'ing it would pollute cloud and/or waste the 10s
// CURRENT-poll on empty shells. when a container is fs-authoritative, SaveSyncService routes
// to FsBridge strategy regardless of the profile's declared mechanism.

// signal: any mutating Html5FsBridge call flips an in-memory flag + touches a persistent
// marker under <filesDir>/html5/fs-used/<containerId>. the marker survives process restarts
// so next-launch inbound-sync can short-circuit before any leveldb work.

// ROUTING_ENABLED is the kill switch -- flip to `false` (one line change) to force leveldb
// rewrite to run regardless of fs-usage signals. intended for regression testing if
// fs-authoritative routing masks a real bug.
object Html5FsAuthoritative {

    @Volatile
    @JvmField
    var ROUTING_ENABLED: Boolean = true

    private val inMemoryUsed: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    // idempotent. called from Html5FsBridge on every mutating op; we only pay the disk touch
    // once per container per process since set.add returns false on duplicate.
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

    // test + debug aid. clears both in-memory + disk marker. not used by production code.
    fun clearForTest(context: Context, containerId: String) {
        inMemoryUsed.remove(containerId)
        runCatching { markerFile(context, containerId).delete() }
    }

    private fun markerFile(context: Context, containerId: String): File =
        File(context.filesDir, "html5/fs-used/$containerId")

    private const val TAG = "Html5FsAuthoritative"
}
