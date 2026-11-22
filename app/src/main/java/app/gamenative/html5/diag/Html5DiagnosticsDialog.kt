package app.gamenative.html5.diag

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.RingIconButton
import app.gamenative.ui.component.dialog.dialogScrollKeys
import app.gamenative.ui.component.focusOnOpen
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.PaddingUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

// the probe reports well under 10s on real devices (rAF 1.5s + cpu bench); past this, something is wedged.
private const val PROBE_TIMEOUT_MS = 20_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Html5DiagnosticsDialog(onDismissRequest: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    val host = remember { Html5Diagnostics.hostInfo(context) }
    val lastGame by produceState<JSONObject?>(null) {
        value = withContext(Dispatchers.IO) { Html5Diagnostics.readLastGame(context) }
    }
    var probe by remember { mutableStateOf<JSONObject?>(null) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val probing = probe == null && probeError == null

    LaunchedEffect(Unit) {
        delay(PROBE_TIMEOUT_MS)
        if (probe == null && probeError == null) probeError = "probe timed out"
    }

    val report = Html5Diagnostics.formatReport(host, probe, probeError ?: if (probing) "running..." else null, lastGame)

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().dialogScrollKeys(scrollState),
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.html5_diag_title)) },
                    navigationIcon = {
                        RingIconButton(
                            onClick = onDismissRequest,
                            modifier = Modifier.focusOnOpen(),
                            content = { Icon(Icons.Default.Close, stringResource(R.string.close)) },
                        )
                    },
                    actions = {
                        RingIconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(report))
                                SnackbarManager.show(context.getString(R.string.html5_diag_copied))
                            },
                            content = { Icon(Icons.Default.ContentCopy, stringResource(R.string.html5_diag_copy)) },
                        )
                        RingIconButton(
                            onClick = { shareReport(context, report) },
                            content = { Icon(Icons.Default.Share, stringResource(R.string.html5_diag_share)) },
                        )
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = PaddingUtils.statusBarAwarePadding().calculateTopPadding() +
                            paddingValues.calculateTopPadding(),
                        bottom = 24.dp + paddingValues.calculateBottomPadding(),
                        start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                        end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                    ),
            ) {
                if (probing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    ProbeWebView(
                        onReport = { json ->
                            runCatching { probe = JSONObject(json) }
                                .onFailure {
                                    Timber.tag("Html5Diagnostics").e(it, "bad probe payload")
                                    probeError = "bad probe payload: ${it.message}"
                                }
                        },
                    )
                }
                SelectionContainer {
                    Text(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontSize = 12.sp,
                        text = report,
                    )
                }
            }
        }
    }
}

private fun shareReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GameNative HTML5 diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Timber.tag("Html5Diagnostics").e(it, "share failed") }
}

private class ProbeBridge(private val view: WebView, private val onReport: (String) -> Unit) {
    @JavascriptInterface
    fun report(json: String) {
        view.post { onReport(json) }
    }
}

// must be visible and attached: rAF doesn't tick for a detached WebView, and the refresh-rate probe needs it.
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ProbeWebView(onReport: (String) -> Unit) {
    val latestOnReport by rememberUpdatedState(onReport)
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(24.dp),
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
