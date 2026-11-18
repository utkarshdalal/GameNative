package app.gamenative.html5.savesync

import android.webkit.JavascriptInterface

// page side of the launch-time localStorage restore (shims/ls-restore.js); calls land on the WebView binder thread.
class Html5LocalStorageRestoreBridge(
    private val appId: String,
    private val service: Html5SaveSyncService,
) {
    // restore JSON staged by syncInbound, or "" when there is none or it was already handed out this launch.
    @JavascriptInterface
    fun take(): String = service.takeLsRestore(appId).orEmpty()

    @JavascriptInterface
    fun applied() {
        service.markLsRestoreApplied(appId)
    }
}
