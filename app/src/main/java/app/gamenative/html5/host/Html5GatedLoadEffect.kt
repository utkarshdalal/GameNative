package app.gamenative.html5.host

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import timber.log.Timber

// gated webView.loadUrl -- waits for Html5SaveSyncService.syncInbound to flip
// saveSyncInboundComplete before navigating, so localStorage[gn:gw:*] is fully populated before
// any game JS runs (closes the INBOUND-vs-loadUrl race). defer-by-frame via webView.post is
// load-bearing (RMMV/NW.js read width/height on first frame). same key (saveSyncInboundComplete,
// webView) + same call position as the inline effect it replaced.
@Composable
internal fun Html5GatedLoadEffect(
    saveSyncInboundComplete: Boolean,
    webView: WebView,
    containerId: String,
    entryPath: String,
    inputModeLabel: String,
) {
    LaunchedEffect(saveSyncInboundComplete, webView) {
        if (!saveSyncInboundComplete) return@LaunchedEffect
        val entryUrl = "${WebViewOrigin.originUrl(containerId)}/${entryPath.removePrefix("/")}"
        Timber.tag("WebViewScreen").i("loading $entryUrl (inputMode=$inputModeLabel)")
        // canvas-positioning CSS is injected at HTML parse time via IndexHtmlRewriter
        // (more reliable than a post-load JS inject -- applies before any game JS runs).
        webView.post {
            webView.requestFocusFromTouch()
            webView.loadUrl(entryUrl)
            // install the gn-open-quickmenu window-event listener AFTER loadUrl is queued.
            // evaluateJavascript runs after the next page commit; the listener hops to
            // __gnInputBridge.openQuickMenu (registered as @JavascriptInterface). silent try/catch
            // so a missing bridge (e.g. early dispose race) doesn't break the install.
            webView.evaluateJavascript(
                """
                (function(){
                  window.addEventListener('gn-open-quickmenu', function(){
                    try { window.__gnInputBridge && window.__gnInputBridge.openQuickMenu && window.__gnInputBridge.openQuickMenu(); } catch (e) {}
                  });
                })();
                """.trimIndent(),
                null,
            )
        }
    }
}
