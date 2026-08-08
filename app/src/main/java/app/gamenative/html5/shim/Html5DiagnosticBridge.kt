package app.gamenative.html5.shim

import android.content.Context
import android.webkit.JavascriptInterface
import app.gamenative.FeatureGate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

// host-side sink for the dev-only diagnostic JS shim. JS calls
// android.Html5DiagnosticBridge.log(eventJson) for every localStorage / indexedDB
// operation; we append to <filesDir>/html5-logs/<containerId>/save-trace.jsonl off the
// JS thread.

// not a Timber-only sink -- per-game file is the key affordance (easier to pull via adb + diff
// across runs than chasing a jumbled logcat). log dir path now
// parallels app_webview/Profile-<containerId>/ for uniform adb navigation.

// attach(containerId) is called by WebViewScreen BEFORE loadUrl; detach() fires in onDispose.
// log() is a no-op if flag is off OR no containerId attached -- defensive, since a release
// build could call the @JavascriptInterface if it somehow ended up exposed.
@Singleton
class Html5DiagnosticBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // marshal writes off the JS thread so loadUrl is never blocked. supervisor so a write
    // failure doesn't kill the scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var currentContainerId: String? = null

    fun attach(containerId: String) {
        currentContainerId = containerId
    }

    fun detach() {
        currentContainerId = null
    }

    @JavascriptInterface
    fun log(eventJson: String) {
        if (!FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) return
        val containerId = currentContainerId ?: return
        scope.launch {
            runCatching { appendLog(containerId, eventJson) }
                .onFailure { Timber.tag(TAG).w(it, "diagnostic log write failed") }
        }
    }

    // visible-for-testing: direct synchronous write path + rotation. returns the target file
    // so tests can assert on its state without polling the coroutine.
    internal fun appendLog(containerId: String, line: String): File {
        val dir = File(context.filesDir, "html5-logs/$containerId")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "save-trace.jsonl")
        if (f.length() > ROTATE_BYTES) {
            val old = File(dir, "save-trace.jsonl.old")
            if (old.exists()) old.delete()
            f.renameTo(old)
        }
        f.appendText("$line\n")
        return f
    }

    companion object {
        private const val TAG = "Html5DiagnosticBridge"

        // rotate at 10MB -- dev-only log, keeps APK logs dir bounded per container.
        private const val ROTATE_BYTES: Long = 10_000_000L
    }
}
