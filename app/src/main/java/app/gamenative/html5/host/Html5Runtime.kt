package app.gamenative.html5.host

import android.content.Context
import androidx.webkit.WebViewCompat
import app.gamenative.R
import app.gamenative.html5.savesync.Html5CrashpadCleanup
import app.gamenative.html5.savesync.Html5LeveldbHealth
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

// html5 process setup, run on first html5 use (pre-launch sync, WebView launch, a runtime flip), never at app
// start, so wine-only users pay nothing.
object Html5Runtime {

    private const val TAG = "Html5Runtime"

    // for work that must outlive the WebView screen (teardown flush).
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val initLock = Mutex()

    // null = not attempted yet. a failure is sticky for the process: the WebView and the port won't change.
    @Volatile private var available: Boolean? = null

    // false = html5 can't run this session; the reason snackbar is shown on every call that returns false,
    // so each launch attempt says why.
    suspend fun ensureInitialized(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val ok = initLock.withLock { available ?: init(appContext).also { available = it } }
        if (!ok) SnackbarManager.show(failureMessage(appContext))
        ok
    }

    private fun init(context: Context): Boolean {
        if (!ChromiumVersionGate.isSupported(context)) return false
        // a drifting port would orphan saves under the wrong leveldb origin, so a failed bind disables html5.
        WebViewOrigin.init(context)
        WebViewOrigin.initFailureMessage()?.let {
            Timber.tag(TAG).w("html5 port init failed: %s", it)
            return false
        }
        // both rewrite chromium's data tree, which is only safe before chromium opens it. another WebView (login,
        // OAuth, video) may already have run this session; skip until a session where html5 comes first.
        if (webViewLoadedInProcess(context)) {
            Timber.tag(TAG).i("a WebView already ran this session -- leveldb/crashpad maintenance deferred")
        } else {
            // chromium never auto-repairs LocalStorage; one force-stop mid-compaction silently drops all later writes.
            runCatching { Html5LeveldbHealth.repairIfWedged(context) }
                .onFailure { Timber.tag(TAG).e(it, "Html5LeveldbHealth scan failed") }
            // chromium has no knob to disable crashpad dumps; SyncFileFilter keeps them out of cloud.
            runCatching { Html5CrashpadCleanup.wipe(context) }
                .onFailure { Timber.tag(TAG).e(it, "Html5CrashpadCleanup wipe failed") }
        }
        // a start() failure must never block the launch.
        runCatching {
            EntryPointAccessors
                .fromApplication(context, ContainerUtils.Html5SaveSyncEntryPoint::class.java)
                .html5SaveSyncService()
                .start()
        }.onFailure { Timber.tag(TAG).e(it, "failed to start Html5SaveSyncService") }
        Timber.tag(TAG).i("html5 runtime initialized")
        return true
    }

    private fun failureMessage(context: Context): String =
        WebViewOrigin.initFailureMessage()
            ?: context.getString(
                R.string.webview_unsupported_chromium,
                ChromiumVersionGate.getMajor(context)?.toString() ?: "unknown",
                ChromiumVersionGate.MIN_MAJOR,
            )

    // the provider apk shows up in our memory map once any WebView has been created in this process.
    private fun webViewLoadedInProcess(context: Context): Boolean {
        val pkg = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.packageName }.getOrNull()
            ?: return true
        return runCatching { File("/proc/self/maps").useLines { lines -> lines.any { pkg in it } } }
            .getOrDefault(true)
    }
}
