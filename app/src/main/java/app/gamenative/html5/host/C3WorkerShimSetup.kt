package app.gamenative.html5.host

import android.content.Context
import android.webkit.WebView
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.html5.savesync.OpfsFlushController
import app.gamenative.html5.savesync.OpfsMirrorBridge
import java.io.File
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// pack:c3 worker-shim: shuttles bytes between c3's worker-side OPFS and the wine save dir. one per launch.
// the OPFS-SAH availability fallback (flip to wine) lives in WebViewScreen, which owns the Container.
class C3WorkerShimSetup(
    private val containerId: String,
    private val installPath: String,
    private val saveSyncService: Html5SaveSyncService,
) {
    private val flushController: OpfsFlushController = OpfsFlushController()

    val mirrorBridge: OpfsMirrorBridge = OpfsMirrorBridge(
        containerId = containerId,
        // the wine save dir is only known once pullInstallToOpfs resolves it; installPath until then.
        rootResolver = { saveSyncService.getActiveMirrorRoot() ?: File(installPath) },
        isInboundReadyResolver = { saveSyncService.getActiveMirrorRoot() != null },
        shouldOverwriteOnHydrateResolver = { saveSyncService.getWineHasFreshBytes() },
        onFlushDone = flushController::signalFlushDone,
    )

    fun attachToWebView(webView: WebView) {
        webView.addJavascriptInterface(mirrorBridge, BRIDGE_NAME)
    }

    // no-op unless the active strategy is OpfsMirror.
    suspend fun pullInstallToOpfs(appId: String) {
        saveSyncService.pullInstallToOpfs(appId)
    }

    // exit flush, bounded by timeoutMs: a partial flush beats blocking webView.destroy() forever.
    // test-only single-shot form; prod uses kickOffExitFlush + awaitFlush so the wait parks off Main.
    fun flushOnExit(webView: WebView, timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        runCatching {
            webView.evaluateJavascript(EXIT_FLUSH_JS, null)
            if (!flushController.awaitFlush(timeoutMs)) {
                Timber.tag(TAG).w("flush timeout waiting for markFlushDone (%dms)", timeoutMs)
            }
        }.onFailure { Timber.tag(TAG).w(it, "exit flush failed") }
    }

    // MUST be called on Main.
    fun kickOffExitFlush(webView: WebView) {
        runCatching { webView.evaluateJavascript(EXIT_FLUSH_JS, null) }
            .onFailure { Timber.tag(TAG).w(it, "exit flush kick-off failed") }
    }

    // blocks the calling thread -- call off Main. the signal arrives from JS on a binder thread.
    fun awaitFlush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
        flushController.awaitFlush(timeoutMs)

    companion object {
        private const val TAG = "Html5WorkerShim"
        private const val BRIDGE_NAME = "__gnOpfsMirrorBridge"

        // a multi-MB save takes several seconds to base64 + marshal over the JS bridge.
        internal const val DEFAULT_FLUSH_TIMEOUT_MS = 30_000L

        fun isActive(profile: EngineProfile?): Boolean =
            profile?.engine == EnginePackId.C3 && profile.workerShim

        fun isSupported(context: Context): Boolean =
            ChromiumVersionGate.isOpfsSahSupported(context)

        private val EXIT_FLUSH_JS = """
            (async function () {
                function dlog(msg) { try { console.log('Html5WorkerShim: flush ' + msg); } catch (_e) {} }
                try {
                    dlog('start');
                    // flushable subset: workers that announced gnFsActive (opened
                    // an SAH or queued a pendingWrite). dispatch/job workers without
                    // SAHs are excluded so c3's switch-default doesn't log
                    // "unknown message 'gnFlushNow'". also filters dead transient
                    // workers that registered the listener at boot then terminated.
                    var workers = self.__gnFlushableWorkers ? Array.from(self.__gnFlushableWorkers) : [];
                    if (workers.length === 0) {
                        dlog('no-workers');
                        if (typeof __gnOpfsMirrorBridge !== 'undefined') __gnOpfsMirrorBridge.markFlushDone();
                        return;
                    }
                    // reset counters; ProxiedWorker bumps them as 'gnFlushFile' /
                    // 'gnFlushDone' messages arrive from each worker. each worker
                    // self-reads its OWN held SAHs synchronously and base64-posts
                    // bytes back to main, where the message listener relays to
                    // __gnOpfsMirrorBridge.writeInstallFile.
                    self.__gnFlushFilesWritten = 0;
                    self.__gnFlushBytesWritten = 0;
                    self.__gnFlushWorkerState = new Map();
                    dlog('triggering ' + workers.length + ' worker(s)');
                    for (var i = 0; i < workers.length; i++) {
                        try { workers[i].postMessage({ type: 'gnFlushNow' }); } catch (_e) {}
                    }
                    // exit when every worker we've heard from has received==manifest.
                    // dead workers (no manifest) are absent from the map and don't
                    // gate exit. only magic: 50ms settle window after first manifest
                    // arrives -- gives stragglers time to manifest before we declare
                    // alive-set complete. without this, a slow worker could manifest
                    // AFTER we've already exited and we'd lose its files.

                    // safety nets:
                    // - 500ms "no manifest at all" cap (all workers dead -- exit fast)
                    // - 25s hard deadline (leaves 5s under Kotlin's 30s awaitFlush)
                    var start = Date.now();
                    var firstManifestT = null;
                    while ((Date.now() - start) < 25000) {
                        var anyManifest = false;
                        var allComplete = true;
                        self.__gnFlushWorkerState.forEach(function (st) {
                            if (st.manifest !== null) {
                                anyManifest = true;
                                if (st.received < st.manifest) allComplete = false;
                            } else {
                                allComplete = false;
                            }
                        });
                        if (anyManifest && firstManifestT === null) firstManifestT = Date.now();
                        if (anyManifest && allComplete && (Date.now() - firstManifestT) > 50) break;
                        if (!anyManifest && (Date.now() - start) > 500) break;
                        await new Promise(function (r) { setTimeout(r, 25); });
                    }
                    var heard = self.__gnFlushWorkerState.size;
                    dlog('done heard=' + heard + '/' + workers.length +
                         ' files=' + self.__gnFlushFilesWritten + ' bytes=' + self.__gnFlushBytesWritten);
                    __gnOpfsMirrorBridge.logFlush(self.__gnFlushFilesWritten, self.__gnFlushBytesWritten);
                    __gnOpfsMirrorBridge.markFlushDone();
                } catch (e) {
                    try { console.warn('Html5WorkerShim: flush err ' + (e && e.message)); } catch (_e) {}
                    try { __gnOpfsMirrorBridge.markFlushDone(); } catch (_e) {}
                }
            })();
        """.trimIndent()
    }
}
