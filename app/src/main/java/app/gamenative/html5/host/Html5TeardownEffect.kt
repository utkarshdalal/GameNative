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
import app.gamenative.html5.shim.SteamworksJsBridge
import app.gamenative.runtime.WebViewContainer
import com.winlator.inputcontrols.ExternalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber

// input-bus wiring + exit teardown for the WebView. keyed on containerId (same as before) so a
// back -> relaunch of the SAME title rebuilds it. live UI state (quick-menu open, manual-resume
// waiting) is read via providers so the long-lived listeners see CURRENT values, not the value
// captured at composition. the onDispose teardown ORDER is load-bearing (save-sync race): flush
// OPFS -> capture greenworks snapshot -> destroy WebView -> emit WebViewDestroyed (runs
// syncOutbound under runBlocking) BEFORE onExit, so the wine save dir is fully rewritten before
// autocloud scans. do not reorder.
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
    steamworksBridgeScope: CoroutineScope,
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
            // route through activity dispatcher so NavHost pops.
            (context as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
        }
        PluviaApp.events.on<AndroidEvent.BackPressed, Unit>(onBack)

        // controller input bus -- route gamepad-sourced events to Html5InputController.
        // consumption is narrowed in Html5InputController: non-BACK gamepad keys pass
        // through so WebView's native onKeyDown dispatches DOM keydown (RMMV keyboard-fallback
        // compatibility until the shim takes over).
        // when QuickMenu is OPEN, return false on gamepad events so they fall
        // through to Compose's focus system (dpad navigates rows, A activates focused item).
        // mirrors XServerScreen.kt ("Let Compose focus system handle keyboard and gamepad
        // navigation/selection while menu is visible.").
        // R3 stays handled by html5InputController.onKeyEvent so the user can re-press R3 to
        // dismiss -- Bypass the synthesizer/bridge while menu is visible to avoid dispatching
        // DOM keydowns into the WebView at the same time as menu navigation. back button is
        // the menu open/close hotkey (QuickMenu's own BackHandler closes when open).
        val onKeyEvent: (AndroidEvent.KeyEvent) -> Boolean = { ev ->
            val isGamepad = ExternalController.isGameController(ev.event.device)
            // wine parity (XServerScreen.kt): when manual-resume widget is up,
            // controller A / keyboard ENTER / controller START dismiss the widget and resume the
            // WebView. consume so the press doesn't reach JS / synth path / quick-menu nav.
            // gate matches the widget visibility check: manual mode + paused + no menu UI.
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
                isQuickMenuOpen() -> false // let Compose focus system handle dpad/A navigation
                else -> html5InputController.onKeyEvent(ev.event)
            }
        }
        val onMotionEvent: (AndroidEvent.MotionEvent) -> Boolean = { ev ->
            val isGamepad = ExternalController.isGameController(ev.event?.device)
            val e = ev.event
            // log every joystick MotionEvent arriving at WebViewScreen
            // so future "no analog response" reports have a logcat trail to start from.
            if (e != null && isGamepad) {
                Timber.tag("Html5Input").d(
                    "MotionEvent in: source=%d action=%d deviceId=%d",
                    e.source, e.action, e.deviceId,
                )
            }
            // same-as-onKeyEvent -- QuickMenu open = drop motion events so
            // analog stick / hat-axis dpad navigates Compose focus instead of synthesizing
            // arrow-key DOM events into the WebView.
            when {
                !isGamepad || e == null -> false
                isQuickMenuOpen() -> false
                else -> html5InputController.onMotionEvent(e)
            }
        }
        PluviaApp.events.on<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
        PluviaApp.events.on<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)

        // webView.loadUrl moved to a gated LaunchedEffect (see WebViewScreen)
        // so it runs AFTER Html5SaveSyncService.syncInbound completes -- closes the race
        // where the renderer's cloud-read IPC fired before localStorage was populated.

        onDispose {
            Timber.tag("WebViewScreen").d("onDispose — kicking off async teardown")
            // detach event listeners + clear globals IMMEDIATELY so a re-entry into
            // WebViewScreen (back→re-launch same title) doesn't see stale subscribers or globals
            // while the background teardown coroutine is still finishing flush+snapshot+destroy.
            PluviaApp.events.off<AndroidEvent.KeyEvent, Boolean>(onKeyEvent)
            PluviaApp.events.off<AndroidEvent.MotionEvent, Boolean>(onMotionEvent)
            PluviaApp.events.off<AndroidEvent.BackPressed, Unit>(onBack)
            PluviaApp.inputControlsView = null
            // capture refs the launched coroutine needs. webView is held by the lambda capture;
            // the launched job has process-lifetime scope but lasts seconds at most so the ref
            // is bounded.
            val capturedWebView = webView
            val capturedC3Setup = c3Setup
            val capturedSteamworksBridge = steamworksBridge
            val capturedSteamworksBridgeScope = steamworksBridgeScope
            val capturedHtml5InputController = html5InputController
            val capturedHtml5InputSynthesizer = html5InputSynthesizer
            val capturedZipFile = zipFile
            val capturedTpatchOverlays = tpatchOverlays
            val capturedElectronSetup = electronSetup
            val capturedAppId = appId
            val capturedOnExit = onExit
            val capturedViewModel = viewModel
            // teardown sequence runs on a launched coroutine so the flush + snapshot awaits
            // (up to 30s + 5s respectively) DON'T hold the main applier thread. without this,
            // a slow OPFS flush or unresponsive renderer would trigger ANR during exit.
            // NonCancellable wrap ensures the coroutine runs to completion even if the launcher's
            // scope is cancelled by some upstream pressure. evaluateJavascript + WebView teardown
            // ops hop to Main inside; latch awaits stay on IO.
            PluviaApp.appScope.launch(NonCancellable) {
                try {
                    // exit-boundary OPFS → install-dir flush BEFORE we touch the WebView. only
                    // runs for pack:c3+workerShim. no-op when capturedC3Setup is null.
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
                    // greenworks LS snapshot capture BEFORE webView.destroy(). WebViewDestroyed
                    // event fires AFTER destroy(); evaluateJavascript from inside the event
                    // subscriber would hit a dead WebView. capture HERE, stash JSON on the bridge
                    // for the post-destroy runBlocking upload to read via consumeGreenworksOutboundSnapshot.
                    // gated on WebViewContainer.greenworksCloudObserved -- non-greenworks containers
                    // don't pay the cost.
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
                            // 5s budget -- small JSON marshalling (greenworks files << OPFS).
                            if (!capturedSteamworksBridge.awaitGreenworksSnapshot(5_000L)) {
                                Timber.tag("Html5GreenworksCloud").w("snapshot capture timed out (5s)")
                            }
                        }.onFailure { Timber.tag("Html5GreenworksCloud").w(it, "snapshot capture failed") }
                    }
                    // WebView teardown -- MUST be on Main thread per WebView API contract.
                    withContext(Dispatchers.Main) {
                        runCatching { capturedHtml5InputController.cleanup() }
                        runCatching { capturedWebView.stopLoading() }
                        runCatching { capturedWebView.loadUrl("about:blank") }
                        runCatching { (capturedWebView.parent as? ViewGroup)?.removeView(capturedWebView) }
                        runCatching { capturedWebView.destroy() }
                    }
                    // close ZipFile AFTER WebView is destroyed so any in-flight zip reads finish.
                    runCatching { capturedZipFile?.close() }
                    capturedTpatchOverlays.forEach { overlay -> runCatching { overlay.close() } }
                    capturedElectronSetup?.close()
                    // fire WebViewDestroyed AFTER destroy() so save-sync sees released leveldb lock.
                    // emit BEFORE onExit. emit runs syncOutbound under runBlocking so the wine
                    // path is fully rewritten before exitSteamApp's autocloud coroutine begins
                    // scanning -- previously onExit-first caused spurious conflict dialogs.
                    PluviaApp.events.emit(AndroidEvent.WebViewDestroyed)
                    runCatching { capturedOnExit(null) }
                    runCatching { capturedViewModel.html5SaveSyncService.clearActive() }
                    if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
                        runCatching { capturedViewModel.html5DiagnosticBridge.detach() }
                    }
                    // Wine parity: do NOT force-show system UI here. PluviaMain emits
                    // SetSystemUIVisibility(!hideStatusBarWhenNotInGame) on the navigation away,
                    // so the user's "hide status bar when not in game" preference is respected.
                    runCatching { capturedSteamworksBridgeScope.cancel() }
                    runCatching { capturedHtml5InputSynthesizer.reset() }
                } catch (t: Throwable) {
                    Timber.tag("WebViewScreen").e(t, "async teardown failed")
                }
            }
        }
    }
}
