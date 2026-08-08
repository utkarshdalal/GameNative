package app.gamenative.html5.profile

import android.content.Context
import java.io.File
import timber.log.Timber

// delete app_webview/Default/Local Storage + app_webview/Default/IndexedDB
// subtrees exactly once. NO other Default/* subtree is referenced (not contamination sources;
// preserving auth + caches). flag flips true only on both-subtree success (partial wipe =>
// next boot retries).

// object-with-lambdas shape (no PrefManager dep) keeps this testable without Robolectric-
// instantiating PluviaApp (which hits Hilt / Timber.plant / ContainerMigrator / posthog).
object DefaultProfileWiper {

    // returns true if wipe ran and completed fully; false if skipped or partial.
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
