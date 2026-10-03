package app.gamenative.html5.profile

import android.content.Context
import java.io.File
import timber.log.Timber

// one-shot wipe of Default/Local Storage + Default/IndexedDB. other Default/* subtrees are left
// alone (auth + caches). flag flips only when both succeed, so a partial wipe retries next boot.
// flag passed as lambdas (no PrefManager dep) so tests don't need Robolectric/PluviaApp.
object DefaultProfileWiper {

    fun wipeIfNeeded(
        context: Context,
        flagRead: () -> Boolean,
        flagWrite: (Boolean) -> Unit,
    ): Boolean {
        if (flagRead()) return false

        val appWebview = File(context.dataDir, "app_webview/Default")
        val lsDir = File(appWebview, "Local Storage")
        val idbDir = File(appWebview, "IndexedDB")

        val lsOk = runCatching {
            if (lsDir.exists()) lsDir.deleteRecursively() else true
        }.onFailure {
            Timber.tag("DefaultProfileWiper").w(it, "wipe Default/Local Storage failed")
        }.getOrDefault(false)

        val idbOk = runCatching {
            if (idbDir.exists()) idbDir.deleteRecursively() else true
        }.onFailure {
            Timber.tag("DefaultProfileWiper").w(it, "wipe Default/IndexedDB failed")
        }.getOrDefault(false)

        return if (lsOk && idbOk) {
            flagWrite(true)
            Timber.tag("DefaultProfileWiper").i("one-shot Default/ wipe complete")
            true
        } else {
            Timber.tag("DefaultProfileWiper").w(
                "one-shot Default/ wipe incomplete: lsOk=%s idbOk=%s — next boot retries",
                lsOk,
                idbOk,
            )
            false
        }
    }
}
