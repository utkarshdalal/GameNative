package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.html5.host.WebViewOrigin
import java.io.File
import timber.log.Timber

// TEMPORARY one-shot boot pass -- delete once dev devices have run it. older inbound sync leaked
// every origin of a Wine LS copy into the shared WebView LS: PC-form origins (file://,
// chrome-extension://) and dead GameNative origins (retired schemes, stale loopback ports). live
// loopback and third-party origins (login pages) stay.
//
// MUST run before any WebView opens -- chromium holds the leveldb lock once one exists.
object Html5LsOriginCleanup {

    private const val TAG = "Html5LsOriginCleanup"

    fun runOnce(context: Context) {
        wipeLegacyProfiles(File(context.dataDir, "app_webview"))
        cleanup(
            lsDir = File(context.dataDir, "app_webview/Default/Local Storage/leveldb"),
            marker = File(context.filesDir, "html5/ls-origin-cleanup-v1"),
            currentPort = WebViewOrigin.ensurePortAllocated(),
        )
    }

    // marker is written only after a successful purge, so a lock failure retries next boot.
    internal fun cleanup(lsDir: File, marker: File, currentPort: Int): Int {
        if (marker.isFile) return 0
        val purged = LevelDbRewriter.purgeLsOrigins(lsDir) { isLeaked(it, currentPort) }
        marker.parentFile?.mkdirs()
        marker.writeText("1")
        Timber.tag(TAG).i("purged %d leaked LS key(s)", purged)
        return purged
    }

    // old multi-profile builds left `Profile N` / `Profile-<id>` stores (hundreds of MB) that nothing
    // opens anymore. no marker needed -- once deleted there is nothing left to list.
    internal fun wipeLegacyProfiles(appWebview: File): Int {
        val profiles = appWebview.listFiles { f -> f.isDirectory && f.name.startsWith("Profile") }.orEmpty()
        profiles.forEach { dir ->
            if (!dir.deleteRecursively()) Timber.tag(TAG).w("failed to delete %s", dir.name)
        }
        if (profiles.isNotEmpty()) Timber.tag(TAG).i("wiped %d legacy WebView profile dir(s)", profiles.size)
        return profiles.size
    }

    internal fun isLeaked(origin: String, currentPort: Int): Boolean = when {
        OriginCodec.isLocalhostOrigin(origin, currentPort) -> false
        OriginCodec.isAppGeneratedOrigin(origin) -> true
        origin == "file://" || origin.startsWith("chrome-extension://") -> true
        else -> false
    }
}
