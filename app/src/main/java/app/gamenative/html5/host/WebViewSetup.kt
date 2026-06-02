package app.gamenative.html5.host

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.util.Base64
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import app.gamenative.R
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber

// physical mouse: WebView swallows ACTION_HOVER_MOVE without a pressed button
// (no DOM pointermove/mousemove until you click+drag), which leaves engines that
// track cursor via pointermove unable to update position. forward each hover into
// physical-mouse.js, which routes per-pack (POJO inject for c3, dispatchEvent for
// others). press / release / button-drag are translated correctly natively, so the
// generic-motion path is left to WebView's default handling.
internal fun installPhysicalMouseHoverForwarding(webView: WebView) {
    webView.setOnHoverListener { _, e ->
        if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE &&
            e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
        ) {
            val js = "window.__gnPhysicalMouseHover && window.__gnPhysicalMouseHover(${e.x.toInt()}, ${e.y.toInt()})"
            webView.evaluateJavascript(js, null)
        }
        false
    }
}

// games trigger save export via `<a href="data:..." download="X.txt">`+click;
// WebView swallows that without an explicit DownloadListener. decode the data:
// URL ourselves and write to public Downloads so the title's "Export Save File"
// actually produces a file on disk. registry: TitleQuirks.ANTIMATTER_DIMENSIONS_SAVE_EXPORT.
internal fun installDataUrlDownloadListener(webView: WebView) {
    webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
        runCatching {
            Timber.tag("WebViewScreen").d(
                "download: scheme=%s mime=%s disp=%s",
                url.substringBefore(':'),
                mimeType,
                contentDisposition,
            )
            val filename = deriveDownloadFilename(url, contentDisposition, mimeType)
            if (url.startsWith("data:")) {
                val bytes = decodeDataUrl(url)
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                dir.mkdirs()
                val out = File(dir, filename)
                out.writeBytes(bytes)
                SnackbarManager.show(webView.context.getString(R.string.webview_download_saved, filename))
                Timber.tag("WebViewScreen").i("download saved: ${out.absolutePath} (${bytes.size} bytes)")
            } else {
                // blob: / http: -- AD doesn't use them; deferred to when a title needs it.
                SnackbarManager.show(webView.context.getString(R.string.webview_download_unsupported_scheme))
                Timber.tag("WebViewScreen").w("unsupported download scheme: ${url.take(64)}")
            }
        }.onFailure {
            SnackbarManager.show(
                webView.context.getString(R.string.webview_download_failed, it.message ?: it.javaClass.simpleName),
            )
            Timber.tag("WebViewScreen").e(it, "download failed")
        }
    }
}

// capture JS console errors to logcat so the next renderer-gone has at least one
// JS-side signal before the Chromium crash. also runs prophylactic shader-compile
// failure detection -- heavy WebGL2 titles trip ANGLE-on-GL's driver-reported
// MAX_VERTEX_UNIFORM_VECTORS cap; without ANGLE-Vulkan override their shaders won't
// compile on Adreno. detect 3+ "too many uniforms"/"too many varyings" errors within
// 15s of WebView attach, snackbar + bail (via onCriticalShaderFailure) so the user
// can flip the runtime to Wine. onShowFileChooser is colocated because Chromium
// exposes one WebChromeClient slot per WebView; splitting these into two clients
// isn't possible.
internal fun buildShaderAwareChromeClient(
    context: Context,
    pendingFileChooserCallback: AtomicReference<ValueCallback<Array<Uri>>?>,
    pickContentLauncher: ActivityResultLauncher<String>,
    onCriticalShaderFailure: () -> Unit,
): WebChromeClient {
    val shaderFailureWindowStart = SystemClock.elapsedRealtime()
    val shaderFailureCount = AtomicInteger(0)
    val shaderFailureFired = AtomicBoolean(false)
    return object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            val tag = "WebViewConsole"
            val line = "${msg.sourceId()}:${msg.lineNumber()} ${msg.message()}"
            when (msg.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Timber.tag(tag).e(line)
                ConsoleMessage.MessageLevel.WARNING -> Timber.tag(tag).w(line)
                else -> Timber.tag(tag).d(line)
            }
            if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR &&
                !shaderFailureFired.get()
            ) {
                val text = msg.message()
                // proximate-cause GLSL compiler errors. matched substring is precise enough
                // that false positives from game-side log lines are unlikely (engines don't
                // log "too many uniforms" as informational text). don't match the higher-
                // level "An error occurred compiling the shader" wrap -- that's engine-
                // specific phrasing and broader matches risk catching transient compiles
                // that engines recover from.
                val isShaderFail = text.contains("too many uniforms") ||
                    text.contains("too many varyings")
                if (isShaderFail) {
                    val elapsed = SystemClock.elapsedRealtime() - shaderFailureWindowStart
                    if (elapsed < 15_000L) {
                        val n = shaderFailureCount.incrementAndGet()
                        if (n >= 3 && shaderFailureFired.compareAndSet(false, true)) {
                            Timber.tag("Html5RuntimeFailure").w(
                                "GLSL uniform/varying overflow (n=%d elapsed=%dms) — bailing to library",
                                n, elapsed,
                            )
                            SnackbarManager.show(
                                context.getString(R.string.html5_runtime_graphics_alloc_failure),
                            )
                            onCriticalShaderFailure()
                        }
                    }
                }
            }
            return true
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            // cancel any prior in-flight chooser so we don't leave a dangling callback
            // (shouldn't happen -- Chromium serializes -- but keeps invariants tight).
            pendingFileChooserCallback.getAndSet(callback)?.onReceiveValue(null)
            val mimeType = params.acceptTypes
                ?.firstOrNull { it.isNotBlank() && !it.startsWith(".") }
                ?: "*/*"
            return runCatching {
                pickContentLauncher.launch(mimeType)
                true
            }.onFailure {
                // launcher can throw if no activity handles the intent -- clear state
                // and signal the chooser failed so WebView re-enables the input.
                pendingFileChooserCallback.set(null)
                callback.onReceiveValue(null)
                Timber.tag("WebViewScreen").e(it, "file chooser launch failed")
            }.getOrDefault(false)
        }
    }
}

// android URLUtil.guessFileName falls back to URL-path parsing for data: URLs, which
// produces the entire encoded payload as the filename -- explodes via FileNotFoundException
// at write time. parse Content-Disposition's filename= ourselves, then fall back to a
// timestamped name keyed off the mediatype. for non-data URLs we trust URLUtil.
private fun deriveDownloadFilename(
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): String {
    if (!contentDisposition.isNullOrBlank()) {
        // RFC 6266: `attachment; filename="X"` or `filename*=UTF-8''X`. tolerant regex.
        val m = Regex(
            """filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?""",
            RegexOption.IGNORE_CASE,
        ).find(contentDisposition)
        m?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return sanitizeFilename(it) }
    }
    if (url.startsWith("data:")) {
        val ext = when {
            mimeType?.startsWith("text/plain", true) == true -> "txt"
            mimeType?.startsWith("application/json", true) == true -> "json"
            mimeType?.startsWith("application/octet-stream", true) == true -> "bin"
            mimeType?.startsWith("text/", true) == true -> "txt"
            else -> "dat"
        }
        return "save-${System.currentTimeMillis()}.$ext"
    }
    return URLUtil.guessFileName(url, contentDisposition, mimeType)
}

private fun sanitizeFilename(name: String): String =
    name.replace(Regex("""[/\\:*?"<>|]"""), "_").take(120)

// data:[<mediatype>][;base64],<payload>. AD's export emits text/plain;charset=utf-8 with
// URL-encoded payload; some games use ;base64 for binary saves. anything else (charset
// quirks, encoded mediatype params) gets best-effort decode and surfaces via writeBytes.
private fun decodeDataUrl(url: String): ByteArray {
    require(url.startsWith("data:")) { "not a data: URL" }
    val comma = url.indexOf(',')
    require(comma > 0) { "malformed data: URL (no comma)" }
    val meta = url.substring(5, comma)
    val payload = url.substring(comma + 1)
    return if (meta.endsWith(";base64", ignoreCase = true)) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        // Uri.decode is %xx-only; java.net.URLDecoder is form-decode (treats `+` as space) and
        // would mangle any literal `+` in a non-base64 data: URL.
        Uri.decode(payload).toByteArray(Charsets.UTF_8)
    }
}
