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

// suspend/resume lifecycle for the WebView (Wine-parity port). resolves the per-container
// suspendPolicy from the wine Container, publishes policy + WebView to PluviaApp so
// MainActivity.onPause/onResume can drive webView.onPause/.onResume, and pauses/resumes (incl.
// JS-side audio) when menu UI opens/closes. returns the bits the rest of WebViewScreen needs:
// manualResumeMode (drives the manual-resume widget) + resumeFromManual (the explicit-resume
// action). anyMenuUiOpen is computed by the caller (it depends on the UI flags) and passed in so
// the LaunchedEffect keys on it.
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
    // suspendPolicy lives on the wine Container (single source of truth for both runtimes); fall
    // back to MANUAL if the container is missing (matches Container.java default).
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
    // publish policy + WebView to PluviaApp so MainActivity.onPause/onResume can drive
    // webView.onPause/.onResume alongside the Wine xEnvironment path. all three reset on dispose.
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
        // clear the manual-focus-hold gate and drive a synthetic window 'focus' so
        // focus-driven engines (Impact/CrossCode) unmute NOW -- the real focus that fired on
        // QuickMenu-close was swallowed to keep them muted while paused, so nothing else will.
        webView.evaluateJavascript(
            "window.__gnManualPaused = false; try { window.dispatchEvent(new Event('focus')); } catch (e) {}",
            null,
        )
        // restore JS-side audio paused by PAUSE_MEDIA_JS on menu-open.
        webView.evaluateJavascript(RESUME_MEDIA_JS, null)
        webView.post { webView.requestFocusFromTouch() }
    }

    // pause WebView when menu UI opens, resume when it closes (Wine parity). flip
    // isOverlayPaused=true on every open regardless of mode so MainActivity.onResume can read it
    // as a unified "menu's in front" gate. on close: manual mode keeps it true (widget appears);
    // immediate mode clears it and resumes. gate on TRUE->FALSE transition only -- initial
    // composition fires with false but no menu was opened, so the resume widget would appear.
    var prevAnyMenuOpen by remember(containerId) { mutableStateOf(false) }
    LaunchedEffect(anyMenuUiOpen) {
        if (anyMenuUiOpen == prevAnyMenuOpen) return@LaunchedEffect
        if (anyMenuUiOpen) {
            if (!neverSuspend) {
                // pause JS-side audio BEFORE webView.onPause -- once JS execution is suspended,
                // evaluateJavascript won't run. Android's WebView.onPause() stops timers/compositing
                // but does NOT pause <audio>/<video> or suspend Web Audio; Tyrano/RMMV BGM via
                // Howler.ctx keeps playing through QuickMenu without this.
                webView.evaluateJavascript(PAUSE_MEDIA_JS, null)
                // manual mode only: arm the focus-hold gate so QuickMenu-close's window 'focus'
                // is swallowed and focus-driven engines stay muted until the user taps resume.
                // immediate/never modes resume on close as normal, so they must NOT arm it.
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
            // resume JS-side audio AFTER webView.onResume -- symmetric with pause ordering.
            webView.evaluateJavascript(RESUME_MEDIA_JS, null)
            webView.post { webView.requestFocusFromTouch() }
        }
        // manual mode + menu closes: isOverlayPaused stays true; widget renders below; user taps.
        prevAnyMenuOpen = anyMenuUiOpen
    }

    return Html5SuspendController(manualResumeMode, resumeFromManual)
}
