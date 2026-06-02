package app.gamenative.html5.savesync

import android.content.Context
import java.io.File
import timber.log.Timber

// boot-time defensive pass. chromium WebView writes crashpad minidumps + metadata to
// cacheDir/WebView/Crashpad/ (verified against system WebView 109 on Adreno 830 / Android 14).
// app_webview/{Default,Profile-*}/Crashpad/ doesn't exist on this build, but is included
// defensively below since other WebView channels may pick that location.
//
// crash-trail error chromium logs on renderer death:
//   [ERROR:directory_reader_posix.cc] opendir /<dataDir>/cache/WebView/Crashpad/attachments/...
//
// WebView SDK exposes no knob to disable crashpad -- it's an internal chromium feature
// wired to MetricsServiceClient at native init, and the on/off decision sits behind
// switches (--disable-crashpad / kEnableCrashReporter) we can't reach from app code.
// without cleanup, the directory accumulates indefinitely (90MB+ within a session is
// realistic). SyncFileFilter prevents these from round-tripping through cloud, but local
// disk fills up across launches.
//
// MUST run before any WebView opens -- chromium takes locks on its data tree at first
// WebView creation; deleting under it after that races with the renderer.
object Html5CrashpadCleanup {

    private const val TAG = "Html5CrashpadCleanup"

    data class CleanupResult(
        val scanned: Int,
        val deleted: Int,
        val bytesFreed: Long,
    )

    fun wipe(context: Context): CleanupResult {
        // primary path on system WebView 109.x. app_webview/{Default,Profile-*}/Crashpad/
        // included as a defensive fallback -- different WebView channel/version combos may
        // pick the alternate location, and the cost of an extra isDirectory check is negligible.
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
