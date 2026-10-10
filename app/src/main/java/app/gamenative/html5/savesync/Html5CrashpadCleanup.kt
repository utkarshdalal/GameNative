package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import timber.log.Timber

// WebView has no app-reachable switch to disable crashpad, and its minidumps accumulate without
// bound (90MB+ per session is realistic). SyncFileFilter keeps them out of cloud; this keeps them
// off local disk.
//
// MUST run before any WebView opens -- deleting under a live chromium data tree races the renderer.
object Html5CrashpadCleanup {

    private const val TAG = "Html5CrashpadCleanup"

    data class CleanupResult(
        val scanned: Int,
        val deleted: Int,
        val bytesFreed: Long,
    )

    fun wipe(context: Context): CleanupResult {
        // cacheDir is where WebView 109 writes; the app_webview locations cover other WebView builds.
        val candidates = listOfNotNull(
            File(context.cacheDir, "WebView/Crashpad"),
            *(File(context.dataDir, "app_webview").listFiles { f ->
                f.isDirectory && (f.name == "Default" || f.name.startsWith("Profile-"))
            }?.map { File(it, "Crashpad") }?.toTypedArray() ?: emptyArray()),
        )

        var scanned = 0
        var deleted = 0
        var bytesFreed = 0L

        for (crashpad in candidates) {
            if (!crashpad.isDirectory) continue
            scanned++
            val size = crashpad.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (crashpad.deleteRecursively()) {
                deleted++
                bytesFreed += size
            } else {
                Timber.tag(TAG).w("failed to delete %s", crashpad.absolutePath)
            }
        }

        if (deleted > 0) {
            Timber.tag(TAG).i("boot wipe: cleared %d crashpad dir(s), freed %d bytes", deleted, bytesFreed)
        } else {
            Timber.tag(TAG).d("boot wipe: nothing to clean (scanned=%d)", scanned)
        }

        return CleanupResult(scanned = scanned, deleted = deleted, bytesFreed = bytesFreed)
    }
}
