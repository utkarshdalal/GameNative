package app.gamenative.html5.host

import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import app.gamenative.FeatureGate
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.input.Html5InputController
import app.gamenative.html5.input.Html5InputSynthesizer
import app.gamenative.html5.savesync.LocalStorageSnapshot
import app.gamenative.html5.shim.SteamworksJsBridge
import app.gamenative.runtime.WebViewContainer
import com.winlator.inputcontrols.ExternalController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber

// input-bus wiring + exit teardown. UI state comes via providers so long-lived listeners see CURRENT values.
// teardown ORDER is load-bearing: flush OPFS -> capture snapshots -> destroy WebView -> emit
// WebViewDestroyed (runs syncOutbound) BEFORE onExit, so the wine save dir is fully rewritten before
// autocloud scans it. do not reorder.
@Composable
internal fun Html5TeardownEffect(
    containerId: String,
    context: android.content.Context,
    webView: WebView,
    appId: String,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    viewModel: WebViewScreenViewModel,
    html5InputController: Html5InputController,
    html5InputSynthesizer: Html5InputSynthesizer,
    steamworksBridge: SteamworksJsBridge,
    c3Setup: C3WorkerShimSetup?,
    zipFile: ZipFile?,
    tpatchOverlays: List<ZipFile>,
    electronSetup: ElectronAsarSetup?,
    isQuickMenuOpen: () -> Boolean,
    isManualResumeWaiting: () -> Boolean,
    onResumeFromManual: () -> Unit,
) {
    DisposableEffect(containerId) {
        val onBack: (AndroidEvent.BackPressed) -> Unit = {
            // via the activity dispatcher so NavHost pops.
            (context as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBack)

        // while QuickMenu is open, gamepad events fall through to Compose focus navigation instead of
        // being synthesized into the WebView.
        val onKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = { ev ->
            val isGamepad = ExternalController.isGameController(ev.event.device)
            // while the manual-resume widget is up, A / ENTER / START resume and are consumed.
            val waitingForManualResume = isManualResumeWaiting()
            when {
                waitingForManualResume -> when (ev.event.keyCode) {
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_START -> {
                        if (ev.event.action == KeyEvent.ACTION_DOWN && ev.event.repeatCount == 0) {
                            onResumeFromManual()
                        }
                        true
                    }
                    else -> false
                }
                !isGamepad -> false
                isQuickMenuOpen() -> false
                else -> html5InputController.onKeyEvent(ev.event)
            }
        }
        val onMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = { ev ->
            val isGamepad = ExternalController.isGameController(ev.event?.device)
            val e = ev.event
            when {
                !isGamepad || e == null -> false
                isQuickMenuOpen() -> false
                else -> html5InputController.onMotionEvent(e)
            }
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)

        onDispose {
            Timber.tag("WebViewScreen").d("onDispose — kicking off async teardown")
            // detach IMMEDIATELY: a quick relaunch must not see stale subscribers while async teardown runs.
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
            PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBack)
            PluviaApp.inputControlsView = null
            val capturedWebView = webView
            val capturedC3Setup = c3Setup
            val capturedSteamworksBridge = steamworksBridge
            val capturedHtml5InputController = html5InputController
            val capturedHtml5InputSynthesizer = html5InputSynthesizer
            val capturedZipFile = zipFile
            val capturedTpatchOverlays = tpatchOverlays
            val capturedElectronSetup = electronSetup
            val capturedAppId = appId
            val capturedOnExit = onExit
            val capturedViewModel = viewModel
            // off Main so the flush / snapshot awaits (up to 30s + 5s) can't ANR the exit. NonCancellable so
            // teardown always completes.
            PluviaApp.appScope.launch(NonCancellable) {
                try {
                    if (capturedC3Setup != null) {
                        withContext(Dispatchers.Main) {
                            runCatching { capturedC3Setup.kickOffExitFlush(capturedWebView) }
                        }
                        if (!capturedC3Setup.awaitFlush(C3WorkerShimSetup.DEFAULT_FLUSH_TIMEOUT_MS)) {
                            Timber.tag("Html5WorkerShim").w(
                                "flush timeout waiting for markFlushDone (%dms) — bytes may be lost",
                                C3WorkerShimSetup.DEFAULT_FLUSH_TIMEOUT_MS,
                            )
                        }
                    }
                    // captured here because the WebViewDestroyed subscriber runs after destroy(); the
                    // post-destroy upload reads it back via consumeGreenworksOutboundSnapshot.
                    val slug = WebViewScreenViewModel.slugFromAppId(capturedAppId)
                    val webViewContainer = slug?.let { WebViewContainer.load(it) }
                    if (webViewContainer?.greenworksCloudObserved == true) {
                        runCatching {
                            withContext(Dispatchers.Main) {
                                capturedWebView.evaluateJavascript(
                                    """
                                    (function () {
                                        try {
                                            var out = {};
                                            for (var i = 0; i < window.localStorage.length; i++) {
                                                var k = window.localStorage.key(i);
                                                if (k && k.indexOf('gn:gw:') === 0) {
                                                    var v = window.localStorage.getItem(k) || '';
                                                    // base64-of-utf-8 -- round-trips cleanly through
                                                    // the @JavascriptInterface String marshalling.
                                                    out[k.substring(6)] = btoa(unescape(encodeURIComponent(v)));
                                                }
                                            }
                                            __gnSteamworksBridge.captureGreenworksOutboundSnapshot(JSON.stringify(out));
                                        } catch (e) {
                                            try {
                                                __gnSteamworksBridge.captureGreenworksOutboundSnapshot('{}');
                                            } catch (_e) {}
                                        }
                                    })();
                                    """.trimIndent(),
                                    null,
                                )
                            }
                            if (!capturedSteamworksBridge.awaitGreenworksSnapshot(5_000L)) {
                                Timber.tag("Html5GreenworksCloud").w("snapshot capture timed out (5s)")
                            }
                        }.onFailure { Timber.tag("Html5GreenworksCloud").w(it, "snapshot capture failed") }
                    }
                    // chromium commits the page's last LS writes to leveldb only after destroy, so the outbound
                    // would read stale keys. the capture fires beforeunload itself so unload-time saves land
                    // first. null -> outbound reads leveldb.
                    val saveSync = capturedViewModel.html5SaveSyncService
                    val pageLocalStorage = if (saveSync.wantsPageLocalStorage(capturedAppId)) {
                        val result = CompletableDeferred<String?>()
                        withContext(Dispatchers.Main) {
                            runCatching {
                                capturedWebView.evaluateJavascript(LocalStorageSnapshot.CAPTURE_JS) { result.complete(it) }
                            }.onFailure { result.complete(null) }
                        }
                        LocalStorageSnapshot.parse(withTimeoutOrNull(5_000L) { result.await() }).also {
                            if (it == null) Timber.tag("Html5SaveSync").w("page localStorage capture failed appId=%s", capturedAppId)
                        }
                    } else {
                        null
                    }
                    saveSync.offerPageLocalStorage(capturedAppId, pageLocalStorage)
                    withContext(Dispatchers.Main) {
                        runCatching { capturedHtml5InputController.cleanup() }
                        runCatching { capturedWebView.stopLoading() }
                        runCatching { capturedWebView.loadUrl("about:blank") }
                        runCatching { (capturedWebView.parent as? ViewGroup)?.removeView(capturedWebView) }
                        runCatching { capturedWebView.destroy() }
                    }
                    // AFTER destroy so in-flight zip reads finish.
                    runCatching { capturedZipFile?.close() }
                    capturedTpatchOverlays.forEach { overlay -> runCatching { overlay.close() } }
                    capturedElectronSetup?.close()
                    // AFTER destroy (leveldb lock released) and BEFORE onExit: emit runs syncOutbound
                    // synchronously, so the wine save dir is rewritten before autocloud scans it.
                    // the reverse order causes spurious conflict dialogs.
                    PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
                    runCatching { capturedOnExit(null) }
                    runCatching { capturedViewModel.html5SaveSyncService.clearActive() }
                    if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
                        runCatching { capturedViewModel.html5DiagnosticBridge.detach() }
                    }
                    // do NOT force-show system UI here: PluviaMain applies the user's status-bar preference
                    // on navigation away.
                    runCatching { capturedHtml5InputSynthesizer.reset() }
                } catch (t: Throwable) {
                    Timber.tag("WebViewScreen").e(t, "async teardown failed")
                }
            }
        }
    }
}
