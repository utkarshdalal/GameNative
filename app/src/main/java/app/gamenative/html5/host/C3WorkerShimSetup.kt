package app.gamenative.html5.host

import android.content.Context
import android.webkit.WebView
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.savesync.Html5SaveSyncService
import app.gamenative.html5.savesync.OpfsFlushController
import app.gamenative.html5.savesync.OpfsMirrorBridge
// ChromiumVersionGate lives in this same package -- no explicit import needed.
import java.io.File
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// pack:c3 + workerShim setup. owns the OPFS flush controller + mirror bridge that
// shuttle bytes between c3's worker-side OPFS and the wine save dir. lifecycle = the
// WebView; instantiate per launch, dispose via flushOnExit + the wrapped controller/bridge
// dying with the JVM scope.
//
// only the AVAILABILITY gate (ChromiumVersionGate.isOpfsSahSupported) + fallback semantics
// (switch container.runtime to wine + Snackbar + BackPressed) live in WebViewScreen -- the
// Snackbar requires the Compose `context` and the runtime flip needs the wine Container, so
// folding them into the helper would invert the dependency.
class C3WorkerShimSetup(
    private val containerId: String,
    private val installPath: String,
    private val saveSyncService: Html5SaveSyncService,
) {
    private val flushController: OpfsFlushController = OpfsFlushController()

    val mirrorBridge: OpfsMirrorBridge = OpfsMirrorBridge(
        containerId = containerId,
        // bridge writes to wine save dir (cloud-sync target) once Html5SaveSyncService.
        // pullInstallToOpfs caches the resolved path on LaunchedEffect. until then, fall back
        // to installPath so the bridge is functional even before the resolve completes.
        rootResolver = { saveSyncService.getActiveMirrorRoot() ?: File(installPath) },
        isInboundReadyResolver = { saveSyncService.getActiveMirrorRoot() != null },
        shouldOverwriteOnHydrateResolver = { saveSyncService.getWineHasFreshBytes() },
        onFlushDone = flushController::signalFlushDone,
    )

    // register the OPFS mirror bridge as a JS interface. observability gate; the actual
    // byte movement runs in worker bootstrap (worker-bootstrap.js calls __gnOpfsMirrorBridge).
    fun attachToWebView(webView: WebView) {
        webView.addJavascriptInterface(mirrorBridge, BRIDGE_NAME)
    }

    // observability gate; actual byte movement runs in worker bootstrap. short-circuits
    // internally if active strategy isn't OpfsMirror, so it's safe to call from outside --
    // the caller's pack:c3 gate just keeps non-c3 packs from paying the resolveSetup cost.
    suspend fun pullInstallToOpfs(appId: String) {
        saveSyncService.pullInstallToOpfs(appId)
    }

    // exit-boundary OPFS → install-dir flush. CountDownLatch with timeoutMs budget --
    // partial flush is better than no flush. evaluateJavascript walks OPFS, base64-encodes
    // each file, posts to __gnOpfsMirrorBridge.writeInstallFile, then calls markFlushDone()
    // which signals the flush controller. timeout is logged but teardown still proceeds --
    // webView.destroy() must not be blocked indefinitely.
    //
    // wiring: opfsMirrorBridge constructor receives flushController::signalFlushDone as
    // onFlushDone. JS markFlushDone → bridge.markFlushDone → controller.signalFlushDone →
    // latch.countDown. unit tests cover the controller half (OpfsFlushControllerTest);
    // the JS-Kotlin call chain is exercised end-to-end here.
    // legacy single-shot variant: evaluateJavascript + await on the calling thread.
    // exists only for tests; prod callers use kickOffExitFlush + awaitFlush separately so the
    // latch wait can park on a non-Main coroutine (see WebViewScreen.onDispose).
    fun flushOnExit(webView: WebView, timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS) {
        runCatching {
            webView.evaluateJavascript(EXIT_FLUSH_JS, null)
            if (!flushController.awaitFlush(timeoutMs)) {
                Timber.tag(TAG).w("flush timeout waiting for markFlushDone (%dms)", timeoutMs)
            }
        }.onFailure { Timber.tag(TAG).w(it, "exit flush failed") }
    }

    // post the OPFS→install flush JS. MUST be called on Main per WebView API.
    fun kickOffExitFlush(webView: WebView) {
        runCatching { webView.evaluateJavascript(EXIT_FLUSH_JS, null) }
            .onFailure { Timber.tag(TAG).w(it, "exit flush kick-off failed") }
    }

    // park on the flush latch. blocks the CALLING thread (CountDownLatch.await semantics) --
    // call from a non-Main coroutine. signal comes from JS via the OpfsMirror bridge on the
    // binder thread, so Main is free.
    fun awaitFlush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean =
        flushController.awaitFlush(timeoutMs)

    companion object {
        private const val TAG = "Html5WorkerShim"
        private const val BRIDGE_NAME = "__gnOpfsMirrorBridge"

        // 30s budget -- single big save can run several MB; base64 + JS-bridge marshalling
        // at 5 MB takes ~3-4s on Adreno 740. Original 5s was too tight.
        internal const val DEFAULT_FLUSH_TIMEOUT_MS = 30_000L

        // active = pack:c3 + workerShim opt-in. gate uses this AND ChromiumVersionGate.
        fun isActive(profile: EngineProfile?): Boolean =
            profile?.engine == EnginePackId.C3 && profile.workerShim

        // OPFS-SAH support gate. caller short-circuits with snackbar + runtime flip when false.
        fun isSupported(context: Context): Boolean =
            ChromiumVersionGate.isOpfsSahSupported(context)

        // exit flush -- see flushOnExit doc. extracted as a const so the 60-line JS doesn't
        // bloat WebViewScreen.
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
