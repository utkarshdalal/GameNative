package app.gamenative.html5.host

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import timber.log.Timber

// waits for inbound save sync so localStorage is populated before any game JS runs.
// the webView.post defer is load-bearing: RMMV/NW.js read width/height on first frame.
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
        webView.post {
            webView.requestFocusFromTouch()
            webView.loadUrl(entryUrl)
        }
    }
}
