package app.gamenative.html5.host

import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.gamenative.PluviaApp
import app.gamenative.events.AndroidEvent
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.InputStream

// shared base for the three html5 asset interceptors (AssetInterceptor / ZipAssetInterceptor /
// AsarAssetInterceptor). holds the logic that was byte-identical-modulo-log-tag across all
// three: renderer-crash handling, /_shims/ serving + path-traversal guards, /_opfs_ready_marker,
// and (for the disk + zip interceptors) the /_worker_stub synthesis. each subclass keeps its
// own asset-resolution tail (assetLoader vs zip-entry vs asar) and its log tag.
abstract class Html5InterceptorBase(
    protected val context: Context,
) : WebViewClient() {

    // class-name log tag -- also used as the "<Class>:" prefix on Html5WorkerShim messages.
    protected abstract val logTag: String

    // Chromium's renderer lives in a sandboxed child process. when it crashes (OOM, GPU driver
    // hiccup, content-specific bug), Android delivers onRenderProcessGone. if we don't handle it
    // by returning true, the crash escalates to a full app crash (see crashpad_client_linux.cc
    // "crash wasn't handled by all associated webviews"). return true to keep the app alive; fire
    // BackPressed so the user returns to the library and can retry.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Timber.tag(logTag).e(
            "WebView render process died (didCrash=${detail.didCrash()} priorityLevel=${detail.rendererPriorityAtExit()}) — exiting to library",
        )
        PluviaApp.events.emit(AndroidEvent.BackPressed)
        return true
    }

    // synthesized shim asset. internal so unit tests can exercise the traversal guard directly
    // without WebResourceRequest mocks.
    internal fun openShimAsset(shimName: String): WebResourceResponse? {
        if (shimName.contains("..") || shimName.startsWith("/")) {
            // path traversal safeguard -- shim names must be flat.
            Timber.tag(logTag).w("rejecting suspicious shim name: $shimName")
            return null
        }
        return runCatching {
            val stream: InputStream = context.assets.open("html5/shims/$shimName")
            val mime = when {
                shimName.endsWith(".js") || shimName.endsWith(".mjs") -> "application/javascript"
                shimName.endsWith(".css") -> "text/css"
                else -> "text/plain"
            }
            onShimServed(shimName)
            WebResourceResponse(mime, "utf-8", stream)
        }.onFailure {
            Timber.tag(logTag).d(it, "shim not found: $shimName")
        }.getOrNull()
    }

    // hook fired after a shim is found, before the response is built. default no-op; ZipAsset-
    // Interceptor overrides to emit its "shim served" diagnostic. keeps the existing per-class
    // logging output unchanged.
    protected open fun onShimServed(shimName: String) {}

    // raw text reader for the worker-bundle inliner. shares the path-traversal guard with
    // openShimAsset since both resolve user-influenced names against `html5/shims/`.
    internal fun readShimAsset(shimName: String): String? {
        if (shimName.contains("..") || shimName.startsWith("/")) return null
        return runCatching {
            context.assets.open("html5/shims/$shimName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    // 1-byte 200 marker. flushes chromium's storage subsystem from a worker sync-XHR -- the act
    // of serving any response from the interceptor thread yields long enough for chromium to
    // settle navigator.storage. shared verbatim by all three interceptors.
    protected fun opfsReadyMarkerResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8",
            ByteArrayInputStream("1".toByteArray(Charsets.UTF_8)),
        )

    // /_worker_stub synthesis for the disk + zip interceptors (identical modulo log tag +
    // shim reader). asar's worker path diverges (no orig inlining, different success log) and
    // keeps its own copy. orig MUST be same-origin. `serveForInline` recursively fetches orig
    // so its body can be embedded (Chromium ≥ 113 PlzDedicatedWorker bypasses
    // shouldInterceptRequest for worker subresources).
    protected fun serveWorkerStub(
        uri: Uri,
        shouldWaitForMainHydration: Boolean,
        serveForInline: (Uri) -> WebResourceResponse?,
        winSaveRoot: String? = null,
    ): WebResourceResponse? {
        val prefix = "$logTag:"
        val orig = uri.getQueryParameter("orig")
        val mode = uri.getQueryParameter("mode") ?: "classic"
        if (orig.isNullOrBlank()) {
            Timber.tag("Html5WorkerShim").w("$prefix worker_stub missing orig param")
            return null
        }
        val originBase = "${uri.scheme}://${uri.authority}"
        // accept blob:<sameOrigin>/<uuid> too -- c3 / NW.js spawn workers from blob URLs; a blob
        // URL can only be created by code running at its embedded origin, so it's same-origin.
        if (!orig.startsWith(originBase) && !orig.startsWith("/") && !orig.startsWith("blob:$originBase/")) {
            Timber.tag("Html5WorkerShim").w("$prefix rejecting cross-origin orig=%s", orig)
            return null
        }
        val bundleUrl = AssetInterceptor.workerBundleUrlFor(mode)
        // inline orig body when http(s)-served. PlzDedicatedWorker routes worker subresource
        // fetches through the network stack, NOT shouldInterceptRequest -- so the worker stub
        // must run with ZERO network fetches once spawned. blob: origs read from chromium's
        // in-memory blob registry (no network), so they don't need inlining.
        val inlineOrig = if (orig.startsWith("http://") || orig.startsWith("https://")) {
            runCatching {
                val origPath = orig.removePrefix(originBase)
                val origUri = uri.buildUpon().encodedPath(origPath).clearQuery().fragment(null).build()
                val resp = serveForInline(origUri)
                val bytes = resp?.data?.use { stream -> stream.readBytes() }
                bytes?.toString(Charsets.UTF_8)
            }.onFailure {
                Timber.tag("Html5WorkerShim").w(it, "$prefix inline-orig fetch failed orig=%s", orig)
            }.getOrNull()
        } else {
            null
        }
        val js = AssetInterceptor.synthesizeWorkerStubBody(
            orig, bundleUrl, mode, ::readShimAsset, shouldWaitForMainHydration, inlineOrig, winSaveRoot,
        )
        Timber.tag("Html5WorkerShim").d(
            "$prefix served worker stub mode=%s orig=%s shouldWaitForMainHydration=%s inlinedOrig=%s",
            mode, orig, shouldWaitForMainHydration, inlineOrig != null,
        )
        return WebResourceResponse(
            "application/javascript", "utf-8",
            ByteArrayInputStream(js.toByteArray(Charsets.UTF_8)),
        )
    }
}
