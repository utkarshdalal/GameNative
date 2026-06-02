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

// seam. intercepts /index.html + /_shims/* and delegates everything else to
// WebViewAssetLoader. note: POSTs aren't interceptable -- none of the titles in scope POST,
// so this limit is acceptable for v1.

// all rewrites are synthesized -- source install folder stays read-only.
class AssetInterceptor(
    context: Context,
    private val assetLoader: WebViewAssetLoader,
    private val installDirectory: File,
    private val shimUrls: List<String>,
    private val patches: List<Patch> = emptyList(),
    private val decryptContext: Html5DecryptContext? = null,
    // OMORI AES-256-CTR decrypt context. only active when an asset-decrypt patch with
    // kind="omori-aes-ctr" is present; built Kotlin-side from Steam launch args.
    private val omoriContext: OmoriDecryptContext? = null,
    // parse-time HTML injection knobs -- see IndexInjectionConfig for field-by-field docs.
    // defaults to a no-op config so existing tests stay byte-identical.
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    // resolver for "wine has fresh cloud bytes" → injected as self.__gnShouldWaitForMainHydration
    // into every worker stub. when true, worker eagerHydrateOpfs awaits the BroadcastChannel
    // 'done' signal from opfs-hydrate-inbound before grabbing SAHs (avoids the OVERWRITE-mode
    // race). when false, worker walks OPFS immediately (the common no-cloud-change relaunch
    // path; opfs-hydrate-inbound is in SKIP-IF-EXISTS mode and not writing). resolver-based
    // because syncInbound runs concurrent with WebView attach -- flag may flip between
    // AssetInterceptor construction and the first worker stub request.
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
    // Windows form of the OPFS-mirrored wine save dir, injected into worker stubs as
    // self.__gnWinSaveRoot so worker-fs.js maps the win32 game's absolute C:/ save paths onto
    // OPFS. resolver-based: the save dir resolves async (pullInstallToOpfs) after construction.
    private val winSaveRootProvider: () -> String? = { null },
    // when true, intercept `js/libs/effekseer.min.js` and serve a stub that bypasses
    // Effekseer's WASM init. used to dodge a chromium-109 audio CHECK bug -- see
    // EffekseerWasmGate for the full diagnosis. resolved at construction time by
    // EffekseerWasmGate.shouldStubWasm(context). default false preserves Effekseer for
    // tests + newer WebView builds.
    private val effekseerWasmStub: Boolean = false,
    // pack:unity emscripten builds ship .br/.gz assets the loader fetches directly, requiring a
    // real Content-Encoding response header. true → .br/.gz route to the loopback server (see
    // shouldInterceptRequest + serve). resolved from EngineProfile.contentEncodedCompression.
    private val contentEncodedCompression: Boolean = false,
) : Html5InterceptorBase(context) {

    override val logTag = "AssetInterceptor"

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // media falls through to the loopback HTTP server -- see ZipAssetInterceptor for why.
        if (isMediaUrl(request.url)) return null
        // pre-compressed emscripten assets must traverse the network stack so chromium runs the
        // Content-Encoding decoder -- shouldInterceptRequest responses are served verbatim (no
        // decode). returning null falls through to the loopback server, where serve() attaches
        // the encoding. gated on the pack flag so non-emscripten packs keep raw .gz semantics.
        if (contentEncodedCompression && contentEncodingFor(request.url.path) != null) return null
        return serve(request.url)
    }

    // internal uri-only entry point. used by shouldInterceptRequest AND by the worker_stub
    // branch below to recursively fetch `orig` so its body can be inlined into the synthesized
    // worker stub (Chromium ≥ 113 PlzDedicatedWorker no longer routes worker subresources
    // through shouldInterceptRequest).
    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        // rewrite URL path before asset lookup.
        val explicitRedirect = PatchApplication.applyUrlRedirects(path, patches) ?: path
        // RMMV image/audio fallback: many titles request `.png`/`.ogg`/`.m4a` directly even
        // when only the encrypted `.rpgmvp`/`.rpgmvo`/`.rpgmvm` variant exists on disk
        // (engine flag isn't set yet, atlas plugins request raw, etc.). when a decrypt context
        // has a key AND the encrypted sibling exists, swap the path so the existing rpgmv-xor
        // patch (matched by .rpgmv* extension) fires on the served bytes.
        val rewrittenPath = maybeRewriteToEncryptedVariant(explicitRedirect)

        // top-level HTML: rewrite with shim injection.
        if (rewrittenPath == "/" || rewrittenPath.endsWith("/index.html")) {
            return runCatching {
                val bytes = readIndexAndInject(installDirectory, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("AssetInterceptor").e(it, "index.html rewrite failed for $rewrittenPath")
            }.getOrNull()
        }

        // synthesized shim path -- never exists on disk.
        if (rewrittenPath.startsWith("/_shims/")) {
            return openShimAsset(rewrittenPath.removePrefix("/_shims/"))
        }

        // synthesizes worker entry for the stub URL. PICK = classic-worker+sync-XHR
        // is the production path; module branch retained for forward-compat
        // future titles that ship module workers natively. orig MUST be same-origin.
        if (rewrittenPath.startsWith("/_worker_stub")) {
            val shouldWait = runCatching { shouldWaitForMainHydrationProvider() }.getOrDefault(false)
            val winSaveRoot = runCatching { winSaveRootProvider() }.getOrNull()
            return serveWorkerStub(uri, shouldWait, ::serve, winSaveRoot)
        }

        // classic-worker variant: 1-byte 200 response that flushes chromium's storage
        // subsystem from a worker sync-XHR (see Html5InterceptorBase.opfsReadyMarkerResponse).
        if (rewrittenPath.startsWith("/_opfs_ready_marker")) {
            return opfsReadyMarkerResponse()
        }

        // Effekseer WASM stub (chromium-109 audio CHECK workaround -- see EffekseerWasmGate).
        // when active, replaces the bundled effekseer.min.js with a tiny stub that defines
        // window.effekseer.initRuntime as a no-op (fires onLoad immediately, never fetches
        // the .wasm). RMMZ's Graphics._createEffekseerContext sees createContext()→null and
        // skips renderer init. particle effects silent + invisible; everything else works.
        if (effekseerWasmStub && rewrittenPath.endsWith("/js/libs/effekseer.min.js")) {
            return WebResourceResponse(
                "application/javascript", "utf-8",
                ByteArrayInputStream(EffekseerWasmGate.stubScript.toByteArray(Charsets.UTF_8)),
            )
        }

        // emscripten pre-compressed asset (pack:unity .br/.gz). serve the raw compressed bytes
        // with the UNDERLYING mime + Content-Encoding so the loopback server's network response
        // triggers chromium's transparent decompression (Unity's loader fetches the .br URL
        // directly and hard-errors when the header is absent). only reached via the loopback path
        // -- shouldInterceptRequest returns null for these. reuses the assetLoader for the
        // case-insensitive disk walk + traversal guard; we only override mime + headers.
        if (contentEncodedCompression) {
            val encoding = contentEncodingFor(rewrittenPath)
            if (encoding != null) {
                val ceUri = uri.buildUpon().authority(uri.host).path(rewrittenPath).build()
                val base = assetLoader.shouldInterceptRequest(ceUri) ?: return null
                // strip the .br/.gz suffix (both 3 chars) to derive the decoded content type --
                // e.g. v0.6.wasm.br → application/wasm; script/wasm exec keys off the decoded mime.
                val underlyingMime = mimeFor(rewrittenPath.dropLast(3))
                return WebResourceResponse(underlyingMime, null, base.data).also { resp ->
                    resp.responseHeaders = (base.responseHeaders?.toMutableMap() ?: LinkedHashMap()).apply {
                        // drop the compressed-size Content-Length hint -- it describes the encoded
                        // body; the loopback STREAM_PLAIN path frames via Connection: close.
                        remove(HEADER_CONTENT_LENGTH)
                        put("Content-Encoding", encoding)
                    }
                }
            }
        }

        // delegate remaining paths to WebViewAssetLoader. strip the port from the URI
        // before delegating: WebViewAssetLoader does a strict `uri.getAuthority() ==
        // mAuthority` check, and setDomain stores host alone (no port), so a URL like
        // http://<safeId>.localhost:59099/... has authority "<safeId>.localhost:59099"
        // but mAuthority is just "<safeId>.localhost" -- mismatch returns null → 404 for
        // every disk-backed asset. the loopback origin always carries a port.
        val delegateUri = uri.buildUpon().authority(uri.host).path(rewrittenPath).build()
        val base = assetLoader.shouldInterceptRequest(delegateUri) ?: return null

        // serve-time transforms (audio-ext-remap, asset-decrypt, body-replace).
        return PatchApplication.applyServeTime(base, rewrittenPath, patches, decryptContext, omoriContext)
    }

    private val rmmvExtMap = mapOf(".png" to ".rpgmvp", ".ogg" to ".rpgmvo", ".m4a" to ".rpgmvm")

    // see callsite -- only swaps when decrypt context has a key (otherwise the encrypted file
    // would be served as-is and the engine would render garbage). returns the original path
    // unchanged when no remap applies.
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
        // maps an emscripten pre-compressed asset path to its HTTP Content-Encoding token, or
        // null if the path isn't .br/.gz. pure + internal so the gating decision is unit-testable
        // without binding a socket or mocking WebResourceRequest.
        internal fun contentEncodingFor(path: String?): String? = when {
            path == null -> null
            path.endsWith(".br", ignoreCase = true) -> "br"
            path.endsWith(".gz", ignoreCase = true) -> "gzip"
            else -> null
        }

        // pure-jvm worker entry body synthesizer. extracted from
        // shouldInterceptRequest so unit tests can verify both variants without
        // WebResourceRequest mocks. JSONObject.quote escapes embedded ".
        //
        // readShim: optional asset reader (Context.assets-backed in production, fixture-backed
        // in tests). When provided, the bundle's component shim contents are INLINED into the
        // stub body instead of being loaded via importScripts/await import. Required on
        // Chromium ≥ ~113 (verified on WebView 124): dedicated-worker subresource requests no
        // longer route through WebViewClient.shouldInterceptRequest, so the bundle URL fetches
        // a real network request against the synthetic `gamenative` host and DNS-fails.
        // Inlining sidesteps the missing intercept entirely. WebView 109 still routes them --
        // we keep the importScripts fallback as the legacy path for that chromium era + for
        // tests that don't supply a reader.
        internal fun synthesizeWorkerStubBody(
            orig: String,
            bundleUrl: String,
            mode: String,
            readShim: ((String) -> String?)? = null,
            shouldWaitForMainHydration: Boolean = false,
            // when non-null, embed orig's body in place of the await import / importScripts(orig)
            // line so the worker runs with ZERO network fetches once spawned. required on
            // Chromium ≥ 113 -- PlzDedicatedWorker bypasses shouldInterceptRequest for worker
            // subresources, so any external fetch from inside a worker hits the network stack.
            inlineOrigContent: String? = null,
            // Windows form of the OPFS-mirrored wine save dir (e.g.
            // "C:/users/xuser/Saved Games/<game>"). injected so worker-fs.js can map the win32
            // game's absolute save paths onto OPFS -- mirror of Html5FsBridge.wineDriveC on the
            // main thread. null for non-cloud / sideloaded containers (no translation).
            winSaveRoot: String? = null,
        ): String {
            // worker-global injection prefix. set BEFORE the bundle so worker-fs.js's IIFE sees
            // these at parse time.
            val waitFlagInjection = "self.__gnShouldWaitForMainHydration = $shouldWaitForMainHydration;\n" +
                (winSaveRoot?.let { "self.__gnWinSaveRoot = ${org.json.JSONObject.quote(it)};\n" } ?: "")
            // bundleUrl is selected per mode -- classic .js (importScripts) vs module .mjs
            // (await import). Caller now passes the mode-appropriate URL via workerBundleUrlFor.
            return when (mode) {
                "module" -> {
                    // c3 main thread posts the init-runtime message immediately after Worker
                    // construction. Top-level await on bundle import defers workermain.js's
                    // self.addEventListener('message', ...) registration -- without buffering,
                    // the init message arrives before any listener exists and is dropped.
                    // Buffer in an early listener, replay after orig has finished loading.
                    val inlineBundle = readShim?.let { reader -> buildModuleBundleInline(reader) }
                    val bundleSection = if (inlineBundle != null) {
                        // inline the .mjs bundle body verbatim. it already uses top-level await
                        // for path/os/nw imports -- those imports ALSO miss interception on
                        // WebView 124, so we replace them with inline contents inside the
                        // builder. await on inline body is a no-op (synchronous code).
                        inlineBundle
                    } else {
                        "await import(${org.json.JSONObject.quote(bundleUrl)});\n"
                    }
                    // primary-worker flag -- read by worker-fs.js eagerHydrateOpfs to gate
                    // exclusive-SAH opening to ONLY the module worker (c3's workermain). without
                    // this, classic workers (dispatchworker/jobworker) raced workermain for save-
                    // file SAHs and won non-deterministically: launch 1 workermain held the saves,
                    // launch 2 dispatchworker did → c3's existsSync (running in workermain) saw
                    // an empty sahCache → "no saves" UI on subsequent launches. set BEFORE the
                    // bundle inline so the IIFE inside worker-fs.js sees the flag at parse time.
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
                        // worker-bundle.js's job is to set self.window=self and importScripts
                        // worker-bootstrap/worker-fs/path/os/nw. concatenated IIFE bodies here
                        // produce the same end-state without any network fetch. orig (typically
                        // a blob: URL from c3) still goes via importScripts -- blob URLs read
                        // from chromium's in-memory blob registry, no interception needed.
                        // wait-flag injected BEFORE the bundle so worker-fs.js IIFE sees it.
                        waitFlagInjection + inlineBundle + origLine
                    } else {
                        waitFlagInjection +
                            "importScripts(${org.json.JSONObject.quote(bundleUrl)});" +
                            origLine
                    }
                }
            }
        }

        // classic-worker bundle inline. order mirrors worker-bundle.js exactly:
        // bootstrap → fs → path → os → nw. each shim is a self-contained IIFE so concatenation
        // preserves their isolation. fail-soft: if any read returns null, return null and the
        // caller falls back to the legacy importScripts path.
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
                // self.window=self alias -- path/os/nw shims register against window.require.
                append("if (typeof self.window === 'undefined') { self.window = self; }\n")
                contents.forEachIndexed { i, content ->
                    append("\n// gn-inline-bundle: ").append(parts[i]).append('\n')
                    append(content)
                    append('\n')
                }
            }
        }

        // module-worker bundle inline. worker-bundle.mjs has a custom bootstrap (different from
        // worker-bootstrap.js -- uses ES const/let, top-level await for OPFS) followed by
        // dynamic imports of worker-fs/path/os/nw. those dynamic imports also miss interception
        // on WV124, so we replace them with inline content too. classic IIFE shims work fine
        // when concatenated into a module body -- they don't use ES module syntax themselves.
        private fun buildModuleBundleInline(readShim: (String) -> String?): String? {
            val mjsBundle = readShim("worker-bundle.mjs") ?: return null
            // strip the dynamic imports from the .mjs (they'd hit the same interception miss).
            // each is on its own line as `await import('/_shims/...')` -- match exactly.
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

        // pick the right bundle URL for the worker mode. classic workers can only importScripts;
        // module workers can only `await import`. matched assets ship as worker-bundle.js (classic)
        // and worker-bundle.mjs (module).
        internal fun workerBundleUrlFor(mode: String): String = when (mode) {
            "module" -> "/_shims/worker-bundle.mjs"
            else -> "/_shims/worker-bundle.js"
        }

        // pure-jvm helper -- unit-testable without WebResourceRequest mocks.
        // reads <installDir>/<entry> (first match) and injects the provided shim URLs.
        // path traversal safeguard: rejects if canonical resolution escapes installDir.
        // candidate list lets pack:nwjs Impact-engine titles (assets/node-webkit.html) work
        // alongside the standard index.html convention; titles using yet another entry filename
        // can extend the list. first existing file wins.
        //
        // legacy overload -- tests pass individual params. new callers should build an
        // IndexInjectionConfig and call the primary overload below.
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
