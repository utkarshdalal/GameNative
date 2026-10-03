package app.gamenative.html5.host

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.gamenative.PluviaApp
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import timber.log.Timber

// WebView suspend/resume, matching the Wine runtime's suspendPolicy behavior.
internal class Html5SuspendController(
    val manualResumeMode: Boolean,
    val resumeFromManual: () -> Unit,
)

@Composable
internal fun rememberHtml5SuspendController(
    context: android.content.Context,
    containerId: String,
    appId: String,
    webView: WebView,
    anyMenuUiOpen: Boolean,
): Html5SuspendController {
    // suspendPolicy lives on the wine Container for both runtimes; MANUAL matches its default.
    val suspendPolicy = remember(containerId) {
        runCatching {
            ContainerUtils
                .getContainer(context, appId)
                .suspendPolicy
        }.onFailure { Timber.tag("WebViewScreen").w(it, "wine container lookup for suspendPolicy failed; defaulting to manual") }
            .getOrNull()
            ?.let { Container.normalizeSuspendPolicy(it) }
            ?: Container.SUSPEND_POLICY_MANUAL
    }
    // lets MainActivity.onPause/onResume drive the WebView the same way it drives the Wine environment.
    DisposableEffect(webView, suspendPolicy) {
        PluviaApp.setActiveSuspendPolicy(suspendPolicy)
        PluviaApp.activeWebView = webView
        SteamService.keepAlive = true
        Timber.tag("WebViewScreen").d("suspendPolicy resolved (from wine container): %s", suspendPolicy)
        onDispose {
            PluviaApp.activeWebView = null
            PluviaApp.clearActiveSuspendState()
            SteamService.keepAlive = false
        }
    }
    val neverSuspend = suspendPolicy.equals(Container.SUSPEND_POLICY_NEVER, ignoreCase = true)
    val manualResumeMode = suspendPolicy.equals(Container.SUSPEND_POLICY_MANUAL, ignoreCase = true)

    val resumeFromManual: () -> Unit = {
        PluviaApp.isOverlayPaused = false
        runCatching { webView.onResume() }
            .onFailure { Timber.tag("WebViewScreen").w(it, "webView.onResume failed") }
        // the real 'focus' from QuickMenu-close was swallowed to keep focus-driven engines muted, so
        // synthesize one now or they never unmute.
        webView.evaluateJavascript(
            "window.__gnManualPaused = false; try { window.dispatchEvent(new Event('focus')); } catch (e) {}",
            null,
        )
        webView.evaluateJavascript(RESUME_MEDIA_JS, null)
        webView.post { webView.requestFocusFromTouch() }
    }

    // isOverlayPaused is set on every open so MainActivity.onResume can treat it as "menu in front".
    // on close, manual mode leaves it set (resume widget shows). act on real transitions only: the
    // initial composition fires with false and would otherwise show the widget.
    var prevAnyMenuOpen by remember(containerId) { mutableStateOf(false) }
    LaunchedEffect(anyMenuUiOpen) {
        if (anyMenuUiOpen == prevAnyMenuOpen) return@LaunchedEffect
        if (anyMenuUiOpen) {
            if (!neverSuspend) {
                // MUST precede webView.onPause: evaluateJavascript won't run once JS is suspended.
                webView.evaluateJavascript(PAUSE_MEDIA_JS, null)
                // manual only: swallow QuickMenu-close's 'focus' so focus-driven engines stay muted until
                // resume. other modes resume on close and must NOT arm it.
                if (manualResumeMode) {
                    webView.evaluateJavascript("window.__gnManualPaused = true;", null)
                }
                runCatching { webView.onPause() }
                    .onFailure { Timber.tag("WebViewScreen").w(it, "webView.onPause failed") }
                PluviaApp.isOverlayPaused = true
            }
        } else if (!neverSuspend && !manualResumeMode) {
            PluviaApp.isOverlayPaused = false
            runCatching { webView.onResume() }
                .onFailure { Timber.tag("WebViewScreen").w(it, "webView.onResume failed") }
            // after onResume, mirroring the pause order.
            webView.evaluateJavascript(RESUME_MEDIA_JS, null)
            webView.post { webView.requestFocusFromTouch() }
        }
        prevAnyMenuOpen = anyMenuUiOpen
    }

    return Html5SuspendController(manualResumeMode, resumeFromManual)
}
