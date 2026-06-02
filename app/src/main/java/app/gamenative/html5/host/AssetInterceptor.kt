package app.gamenative.html5.host

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import app.gamenative.html5.profile.Patch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

// disk-backed interceptor. rewrites are synthesized at serve time -- the install folder stays read-only.
// POSTs can't be intercepted; no known title needs them.
class AssetInterceptor(
    context: Context,
    private val assetLoader: WebViewAssetLoader,
    private val installDirectory: File,
    private val shimUrls: List<String>,
    private val patches: List<Patch> = emptyList(),
    private val decryptContext: Html5DecryptContext? = null,
    private val omoriContext: OmoriDecryptContext? = null,
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    // true when wine has fresh cloud bytes: workers must wait for the main-thread OPFS hydrate before
    // opening SAHs. a provider because inbound sync runs concurrently with WebView attach.
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
    // Windows form of the wine save dir, so worker-fs.js can map the game's absolute C:/ save paths onto
    // OPFS. a provider because it resolves after construction.
    private val winSaveRootProvider: () -> String? = { null },
    // see EffekseerWasmGate.
    private val effekseerWasmStub: Boolean = false,
    // emscripten .br/.gz assets need a real Content-Encoding header; see shouldInterceptRequest.
    private val contentEncodedCompression: Boolean = false,
) : Html5InterceptorBase(context) {

    override val logTag = "AssetInterceptor"

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // media falls through to the loopback server -- see isMediaUrl.
        if (isMediaUrl(request.url)) return null
        // chromium only runs its Content-Encoding decoder on network responses, not intercepted ones, so
        // these go via the loopback server. gated so other packs keep raw .gz semantics.
        if (contentEncodedCompression && contentEncodingFor(request.url.path) != null) return null
        return serve(request.url)
    }

    // also called recursively by the worker-stub path to inline `orig`.
    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        val explicitRedirect = PatchApplication.applyUrlRedirects(path, patches) ?: path
        // RMMV titles often request plain `.png`/`.ogg`/`.m4a` when only the encrypted sibling exists
        // (e.g. plugins bypassing the engine's decrypt flag); serve the sibling through the decrypt patch.
        val rewrittenPath = maybeRewriteToEncryptedVariant(explicitRedirect)

        if (rewrittenPath == "/" || rewrittenPath.endsWith("/index.html")) {
            return runCatching {
                val bytes = readIndexAndInject(installDirectory, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("AssetInterceptor").e(it, "index.html rewrite failed for $rewrittenPath")
            }.getOrNull()
        }

        if (rewrittenPath.startsWith("/_shims/")) {
            return openShimAsset(rewrittenPath.removePrefix("/_shims/"))
        }

        if (rewrittenPath.startsWith("/_worker_stub")) {
            val shouldWait = runCatching { shouldWaitForMainHydrationProvider() }.getOrDefault(false)
            val winSaveRoot = runCatching { winSaveRootProvider() }.getOrNull()
            return serveWorkerStub(uri, shouldWait, ::serve, winSaveRoot)
        }

        if (rewrittenPath.startsWith("/_opfs_ready_marker")) {
            return opfsReadyMarkerResponse()
        }

        if (effekseerWasmStub && rewrittenPath.endsWith("/js/libs/effekseer.min.js")) {
            return WebResourceResponse(
                "application/javascript", "utf-8",
                ByteArrayInputStream(EffekseerWasmGate.stubScript.toByteArray(Charsets.UTF_8)),
            )
        }

        // loopback-only. compressed bytes + the UNDERLYING mime + Content-Encoding, so chromium decompresses
        // transparently; Unity's loader hard-errors without the header.
        if (contentEncodedCompression) {
            val encoding = contentEncodingFor(rewrittenPath)
            if (encoding != null) {
                val ceUri = uri.buildUpon().authority(uri.host).path(rewrittenPath).build()
                val base = assetLoader.shouldInterceptRequest(ceUri) ?: return null
                // .br/.gz are both 3 chars; x.wasm.br -> application/wasm.
                val underlyingMime = mimeFor(rewrittenPath.dropLast(3))
                return WebResourceResponse(underlyingMime, null, base.data).also { resp ->
                    resp.responseHeaders = (base.responseHeaders?.toMutableMap() ?: LinkedHashMap()).apply {
                        // the length hint would describe the encoded body.
                        remove(HEADER_CONTENT_LENGTH)
                        put("Content-Encoding", encoding)
                    }
                }
            }
        }

        // strip the port: WebViewAssetLoader compares authority strictly against a port-less domain, so
        // every asset would 404.
        val delegateUri = uri.buildUpon().authority(uri.host).path(rewrittenPath).build()
        val base = assetLoader.shouldInterceptRequest(delegateUri) ?: return null

        return PatchApplication.applyServeTime(base, rewrittenPath, patches, decryptContext, omoriContext)
    }

    private val rmmvExtMap = mapOf(".png" to ".rpgmvp", ".ogg" to ".rpgmvo", ".m4a" to ".rpgmvm")

    // only with a key: otherwise the encrypted bytes would be served raw.
    private fun maybeRewriteToEncryptedVariant(path: String): String {
        if (decryptContext?.hasKey != true) return path
        for ((plain, encrypted) in rmmvExtMap) {
            if (!path.endsWith(plain, ignoreCase = true)) continue
            val encryptedPath = path.dropLast(plain.length) + encrypted
            val onDisk = File(installDirectory, encryptedPath.trimStart('/'))
            if (onDisk.isFile) return encryptedPath
        }
        return path
    }

    companion object {
        internal fun contentEncodingFor(path: String?): String? = when {
            path == null -> null
            path.endsWith(".br", ignoreCase = true) -> "br"
            path.endsWith(".gz", ignoreCase = true) -> "gzip"
            else -> null
        }

        // with readShim, the bundle's shims are INLINED rather than fetched: on chromium 113+
        // (PlzDedicatedWorker) worker subresource requests bypass shouldInterceptRequest. the
        // importScripts fallback still works on older WebViews.
        internal fun synthesizeWorkerStubBody(
            orig: String,
            bundleUrl: String,
            mode: String,
            readShim: ((String) -> String?)? = null,
            shouldWaitForMainHydration: Boolean = false,
            // orig's body, so the worker makes ZERO network fetches once spawned (same chromium 113+ reason).
            inlineOrigContent: String? = null,
            // e.g. "C:/users/xuser/Saved Games/<game>"; null = no path translation.
            winSaveRoot: String? = null,
        ): String {
            // BEFORE the bundle so worker-fs.js's IIFE sees these at parse time.
            val waitFlagInjection = "self.__gnShouldWaitForMainHydration = $shouldWaitForMainHydration;\n" +
                (winSaveRoot?.let { "self.__gnWinSaveRoot = ${org.json.JSONObject.quote(it)};\n" } ?: "")
            return when (mode) {
                "module" -> {
                    // c3 posts its init message right after constructing the Worker, but top-level await
                    // delays workermain.js's listener registration, so the message would be dropped.
                    // buffer early messages and replay them once orig has loaded.
                    val inlineBundle = readShim?.let { reader -> buildModuleBundleInline(reader) }
                    val bundleSection = if (inlineBundle != null) {
                        inlineBundle
                    } else {
                        "await import(${org.json.JSONObject.quote(bundleUrl)});\n"
                    }
                    // __gnPrimaryWorker: only the module worker (c3's workermain) may open the exclusive save
                    // SAHs. otherwise classic workers race it for them, and when one wins, c3's existsSync in
                    // workermain sees no saves. set before the bundle so worker-fs.js sees it at parse time.
                    val origSection = if (inlineOrigContent != null) {
                        "// gn-inline-orig: $orig\n" + inlineOrigContent + "\n"
                    } else {
                        "await import(${org.json.JSONObject.quote(orig)});\n"
                    }
                    "self.__gnPrimaryWorker = true;\n" +
                        waitFlagInjection +
                        "const __gnPending = [];\n" +
                        "const __gnEarly = (e) => __gnPending.push({ data: e.data, ports: e.ports });\n" +
                        "self.addEventListener('message', __gnEarly);\n" +
                        bundleSection +
                        origSection +
                        "self.removeEventListener('message', __gnEarly);\n" +
                        "for (const m of __gnPending) {\n" +
                        "    self.dispatchEvent(new MessageEvent('message', { data: m.data, ports: m.ports || [] }));\n" +
                        "}\n" +
                        "if (self.__gnShimVerbose) try { console.log('Html5WorkerShim: replayed ' + __gnPending.length + ' buffered worker messages'); } catch (_e) {}\n"
                }
                else -> {
                    val inlineBundle = readShim?.let { reader -> buildClassicBundleInline(reader) }
                    val origLine = if (inlineOrigContent != null) {
                        "\n// gn-inline-orig: $orig\n" + inlineOrigContent + "\n"
                    } else {
                        "\nimportScripts(${org.json.JSONObject.quote(orig)});\n"
                    }
                    if (inlineBundle != null) {
                        waitFlagInjection + inlineBundle + origLine
                    } else {
                        waitFlagInjection +
                            "importScripts(${org.json.JSONObject.quote(bundleUrl)});" +
                            origLine
                    }
                }
            }
        }

        // order MUST mirror worker-bundle.js. null (any read failed) -> caller falls back to importScripts.
        private fun buildClassicBundleInline(readShim: (String) -> String?): String? {
            val parts = listOf(
                "worker-bootstrap.js",
                "worker-fs.js",
                "path.js",
                "os.js",
                "nw.js",
            )
            val contents = parts.map { name -> readShim(name) ?: return null }
            return buildString {
                append("'use strict';\n")
                // path/os/nw shims register against window.require.
                append("if (typeof self.window === 'undefined') { self.window = self; }\n")
                contents.forEachIndexed { i, content ->
                    append("\n// gn-inline-bundle: ").append(parts[i]).append('\n')
                    append(content)
                    append('\n')
                }
            }
        }

        // worker-bundle.mjs has its own bootstrap followed by dynamic imports of the shims; those imports
        // would miss interception too, so they're replaced with inline content.
        private fun buildModuleBundleInline(readShim: (String) -> String?): String? {
            val mjsBundle = readShim("worker-bundle.mjs") ?: return null
            // each import is on its own line as `await import('/_shims/...');`.
            val withoutImports = mjsBundle.lines().filter { line ->
                val t = line.trim()
                !(t.startsWith("await import('/_shims/") && t.endsWith("');"))
            }.joinToString("\n")
            val parts = listOf("worker-fs.js", "path.js", "os.js", "nw.js")
            val contents = parts.map { name -> readShim(name) ?: return null }
            return buildString {
                append(withoutImports)
                append('\n')
                contents.forEachIndexed { i, content ->
                    append("\n// gn-inline-bundle: ").append(parts[i]).append('\n')
                    append(content)
                    append('\n')
                }
            }
        }

        // classic workers can only importScripts; module workers can only `await import`.
        internal fun workerBundleUrlFor(mode: String): String = when (mode) {
            "module" -> "/_shims/worker-bundle.mjs"
            else -> "/_shims/worker-bundle.js"
        }

        // test convenience overload; production builds an IndexInjectionConfig.
        fun readIndexAndInject(
            installDirectory: File,
            shimUrls: List<String>,
            locale: String? = null,
            gestureConfigJson: String? = null,
            nwArgvJson: String? = null,
            nwAppDataPath: String? = null,
            mainModuleFilename: String = "",
            renderScaleOverride: Float? = null,
            fsBridgeOnly: Boolean = false,
            touchscreenMode: Boolean = true,
        ): ByteArray = readIndexAndInject(
            installDirectory = installDirectory,
            shimUrls = shimUrls,
            injection = IndexInjectionConfig(
                locale = locale,
                gestureConfigJson = gestureConfigJson,
                nwArgvJson = nwArgvJson,
                nwAppDataPath = nwAppDataPath,
                mainModuleFilename = mainModuleFilename,
                renderScaleOverride = renderScaleOverride,
                fsBridgeOnly = fsBridgeOnly,
                touchscreenMode = touchscreenMode,
            ),
        )

        fun readIndexAndInject(
            installDirectory: File,
            shimUrls: List<String>,
            injection: IndexInjectionConfig,
        ): ByteArray {
            // node-webkit.html: Impact-engine NW.js entry. canonical paths reject traversal outside the install.
            val installRoot = installDirectory.canonicalFile
            val candidates = listOf("index.html", "node-webkit.html")
            val indexFile = candidates
                .map { File(installRoot, it).canonicalFile }
                .firstOrNull { f ->
                    f.path.startsWith(installRoot.path) && f.exists()
                }
            require(indexFile != null) {
                "no index file found at $installRoot (tried ${candidates.joinToString()})"
            }
            val rewritten: InputStream = FileInputStream(indexFile).use { fis ->
                IndexHtmlRewriter.inject(fis, shimUrls, injection)
            }
            return rewritten.readBytes()
        }
    }
}
