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

// dev-only sink for the diagnostic shim's localStorage / indexedDB trace. a per-container file
// rather than Timber so runs can be pulled via adb and diffed.
// log() re-checks the flag in case the interface is ever exposed in a release build.
@Singleton
class Html5DiagnosticBridge @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // off the JS thread so loadUrl is never blocked.
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

        private const val ROTATE_BYTES: Long = 10_000_000L
    }
}
