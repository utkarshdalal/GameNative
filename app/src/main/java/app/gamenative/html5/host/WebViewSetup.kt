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

// WebView drops button-less hover moves, so cursor-tracking engines never see pointermove. only hover is
// forwarded; presses and drags already arrive natively.
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

// WebView silently drops `<a href="data:..." download>` save exports without a DownloadListener; write them
// to public Downloads. registry: TitleQuirks.ANTIMATTER_DIMENSIONS_SAVE_EXPORT.
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
                // blob: / http: not needed by any known title yet.
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

// console -> logcat, so a renderer crash has some JS-side signal. also bails early when heavy WebGL2 shaders
// exceed the driver's uniform/varying caps, so the user can switch to Wine. the file chooser lives here too
// because a WebView has only one WebChromeClient.
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
                // match the GLSL compiler's own errors, NOT engines' generic "error compiling the shader"
                // wrappers, which also fire for transient compiles the engine recovers from.
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
            // never leave a prior callback dangling.
            pendingFileChooserCallback.getAndSet(callback)?.onReceiveValue(null)
            val mimeType = params.acceptTypes
                ?.firstOrNull { it.isNotBlank() && !it.startsWith(".") }
                ?: "*/*"
            return runCatching {
                pickContentLauncher.launch(mimeType)
                true
            }.onFailure {
                // no activity handles the intent; the null reply re-enables the input.
                pendingFileChooserCallback.set(null)
                callback.onReceiveValue(null)
                Timber.tag("WebViewScreen").e(it, "file chooser launch failed")
            }.getOrDefault(false)
        }
    }
}

// URLUtil.guessFileName turns a data: URL's whole payload into the filename, which fails at write time.
private fun deriveDownloadFilename(
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): String {
    if (!contentDisposition.isNullOrBlank()) {
        // RFC 6266: `filename="X"` or `filename*=UTF-8''X`.
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

// data:[<mediatype>][;base64],<payload>; non-base64 payloads are URL-encoded text.
private fun decodeDataUrl(url: String): ByteArray {
    require(url.startsWith("data:")) { "not a data: URL" }
    val comma = url.indexOf(',')
    require(comma > 0) { "malformed data: URL (no comma)" }
    val meta = url.substring(5, comma)
    val payload = url.substring(comma + 1)
    return if (meta.endsWith(";base64", ignoreCase = true)) {
        Base64.decode(payload, Base64.DEFAULT)
    } else {
        // NOT URLDecoder: it form-decodes `+` as space.
        Uri.decode(payload).toByteArray(Charsets.UTF_8)
    }
}
