package app.gamenative.html5.diag

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private class ProbeBridge(private val view: WebView, private val onReport: (String) -> Unit) {
    @JavascriptInterface
    fun report(json: String) {
        view.post { onReport(json) }
    }
}

// runs html5/diag/probe.js once in its own page, so the game's page keeps its contexts and globals to itself.
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun Html5ProbeWebView(onReport: (String) -> Unit) {
    val latestOnReport by rememberUpdatedState(onReport)
    AndroidView(
        modifier = Modifier.size(1.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(Color.BLACK)
                addJavascriptInterface(ProbeBridge(this) { latestOnReport(it) }, "__gnDiagBridge")
                val js = ctx.assets.open("html5/diag/probe.js").bufferedReader().use { it.readText() }
                // *.localhost is a secure context, like the game origins, so OPFS/WebGPU detection matches.
                loadDataWithBaseURL(
                    "http://gn-diag.localhost/",
                    "<!doctype html><html><body style=\"margin:0;background:#000\"><script>$js</script></body></html>",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
        onRelease = { it.destroy() },
    )
}
