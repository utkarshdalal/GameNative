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

// shared base for the disk / zip / asar asset interceptors: renderer-crash handling, shim serving,
// path-traversal guards, and worker-stub synthesis. subclasses own asset resolution.
abstract class Html5InterceptorBase(
    protected val context: Context,
) : WebViewClient() {

    // also the "<Class>:" prefix on Html5WorkerShim messages.
    protected abstract val logTag: String

    // returning false escalates a renderer crash into a full app crash. return true and go back to the library.
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Timber.tag(logTag).e(
            "WebView render process died (didCrash=${detail.didCrash()} priorityLevel=${detail.rendererPriorityAtExit()}) — exiting to library",
        )
        PluviaApp.events.emit(AndroidEvent.BackPressed)
        return true
    }

    internal fun openShimAsset(shimName: String): WebResourceResponse? {
        if (shimName.contains("..") || shimName.startsWith("/")) {
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

    protected open fun onShimServed(shimName: String) {}

    // same traversal guard as openShimAsset: both resolve page-influenced names.
    internal fun readShimAsset(shimName: String): String? {
        if (shimName.contains("..") || shimName.startsWith("/")) return null
        return runCatching {
            context.assets.open("html5/shims/$shimName").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
    }

    // a worker sync-XHR to this marker yields long enough for chromium to settle navigator.storage.
    protected fun opfsReadyMarkerResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain", "utf-8",
            ByteArrayInputStream("1".toByteArray(Charsets.UTF_8)),
        )

    // disk + zip interceptors only; asar keeps its own variant. orig MUST be same-origin.
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
        // chromium 113+ (PlzDedicatedWorker) sends worker subresource fetches to the network, NOT
        // shouldInterceptRequest, so http(s) origs are inlined. blob: origs never hit the network.
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
