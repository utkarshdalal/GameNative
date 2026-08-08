package app.gamenative.html5.asar

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.gamenative.html5.host.AssetInterceptor
import app.gamenative.html5.host.Html5InterceptorBase
import app.gamenative.html5.host.IndexHtmlRewriter
import app.gamenative.html5.host.IndexInjectionConfig
import app.gamenative.html5.host.mimeFor
import app.gamenative.html5.host.withContentLength
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

// asset-serving analog of ZipAssetInterceptor for electron asar-rooted
// containers. reads all resources from an open AsarArchive provided by the caller
// (WebViewScreen -- owns lifetime via remember/onDispose). intercept-don't-mutate
// preserved: asar opened read-only; responses synthesized or passthrough ByteArrayInputStream.

// no electron title today uses rpgmv-xor decrypt or audio-ext-remap, so patches is omitted vs the
// zip analog. add it back when an electron title needs response-body-replace at depot time.
class AsarAssetInterceptor(
    context: Context,
    private val archive: ElectronArchive,
    private val shimUrls: List<String>,
    // parse-time HTML injection knobs -- see IndexInjectionConfig. defaults to no-op config.
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    // see AssetInterceptor.shouldWaitForMainHydrationProvider -- same role here.
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
) : Html5InterceptorBase(context) {

    override val logTag = "AsarAssetInterceptor"

    // electron apps don't all ship index.html at the asar root -- the real
    // entry filename lives in main.js (BrowserWindow.loadFile(...)), which we can't execute.
    // resolve once at interceptor construction and use that name for both /index.html and /
    // requests. keeps WebViewScreen's URL construction unchanged (it still asks for /index.html).
    private val resolvedEntry: String = resolveEntry(archive).also {
        Timber.tag("AsarAssetInterceptor").i("resolved asar entry: $it")
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // media falls through to the loopback HTTP server -- see ZipAssetInterceptor for why.
        if (app.gamenative.html5.host.isMediaUrl(request.url)) return null
        return serve(request.url)
    }

    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        // top-level HTML: shim-inject from asar's resolved entry. WebViewScreen always asks
        // for /index.html; we serve the resolved entry content in response.
        if (path == "/" || path.endsWith("/index.html") || path == "/$resolvedEntry") {
            return runCatching {
                val bytes = readIndexAndInjectFromAsar(archive, resolvedEntry, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("AsarAssetInterceptor").e(it, "entry '$resolvedEntry' rewrite failed for $path")
            }.getOrNull()
        }

        // synthesized shim path -- never exists in asar.
        if (path.startsWith("/_shims/")) {
            return openShimAsset(path.removePrefix("/_shims/"))
        }

        // production worker entry synthesis. mirrors AssetInterceptor branch
        // for asar-hosted electron pack:c3 future scope. PICK = classic-worker+sync-XHR
        // orig MUST be same-origin.
        if (path.startsWith("/_worker_stub")) {
            val orig = uri.getQueryParameter("orig")
            val mode = uri.getQueryParameter("mode") ?: "classic"
            if (orig.isNullOrBlank()) {
                Timber.tag("Html5WorkerShim").w("AsarAssetInterceptor: worker_stub missing orig param")
                return null
            }
            val originBase = "${uri.scheme}://${uri.authority}"
            // accept blob:<sameOrigin>/<uuid> too -- c3 / NW.js spawn workers from blob URLs.
            if (!orig.startsWith(originBase) && !orig.startsWith("/") && !orig.startsWith("blob:$originBase/")) {
                Timber.tag("Html5WorkerShim").w("AsarAssetInterceptor: rejecting cross-origin orig=%s", orig)
                return null
            }
            val bundleUrl = AssetInterceptor.workerBundleUrlFor(mode)
            val shouldWait = runCatching { shouldWaitForMainHydrationProvider() }.getOrDefault(false)
            val js = AssetInterceptor.synthesizeWorkerStubBody(
                orig, bundleUrl, mode,
                readShim = ::readShimAsset,
                shouldWaitForMainHydration = shouldWait,
            )
            Timber.tag("Html5WorkerShim").d(
                "AsarAssetInterceptor: served worker stub mode=%s orig=%s shouldWaitForMainHydration=%s",
                mode, orig, shouldWait,
            )
            return WebResourceResponse(
                "application/javascript", "utf-8",
                ByteArrayInputStream(js.toByteArray(Charsets.UTF_8)),
            )
        }

        // classic-worker variant: 1-byte 200 response. see AssetInterceptor for rationale.
        if (path.startsWith("/_opfs_ready_marker")) {
            return opfsReadyMarkerResponse()
        }

        // synthetic directory-listing endpoint for fs.readdirSync over absolute paths. some
        // older Electron titles do `fs.readdirSync('/conf')` to discover mod configs;
        // Html5FsBridge is save-sandbox only and rejects absolutes. JSON body is
        // `archive.listFiles(relPath)` -- empty array for missing / file / outside-asar.
        if (path.startsWith("/_asar_listdir/") || path == "/_asar_listdir") {
            return openAsarListing(path.removePrefix("/_asar_listdir").removePrefix("/"))
        }

        // all other paths resolve against asar entries.
        return openAsarEntry(path.removePrefix("/"))
    }

    internal fun openAsarListing(relPath: String): WebResourceResponse? {
        if (relPath.contains("..")) return null
        val names = archive.listFiles(relPath)
        val body = buildString {
            append('[')
            names.forEachIndexed { i, n ->
                if (i > 0) append(',')
                append('"')
                // minimal JSON string escape -- asar entry names don't contain control chars or
                // backslashes in practice, but escape the three that break parse.
                n.forEach { c ->
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        else -> append(c)
                    }
                }
                append('"')
            }
            append(']')
        }
        return WebResourceResponse("application/json", "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
    }

    // internal so unit tests exercise traversal guards directly.
    internal fun openAsarEntry(relPath: String): WebResourceResponse? {
        if (relPath.contains("..") || relPath.startsWith("/")) {
            Timber.tag("AsarAssetInterceptor").w("rejecting suspicious asar entry: $relPath")
            return null
        }
        // direct lookup. covers paths relative to the entry's URL (which resolve through
        // the page's URL base -- e.g. entry served at /src/index.html makes `./foo` → /src/foo).
        archive.read(relPath)?.let {
            return WebResourceResponse(mimeFor(relPath), null, ByteArrayInputStream(it))
                .withContentLength(it.size.toLong())
        }
        // entry-dir fallback. ABSOLUTE paths in the HTML (e.g. Microlandia's
        // `<script src="/assets/index-DfwayJvg.js">`) resolve to the URL root, but the
        // actual file lives in the entry's directory. when entry is in a subdir, try
        // <entryDir>/<relPath> as a fallback. harmless when entry is at root (entryDir = "").
        val entryDir = resolvedEntry.substringBeforeLast('/', missingDelimiterValue = "")
        if (entryDir.isNotEmpty()) {
            val fallback = "$entryDir/$relPath"
            archive.read(fallback)?.let {
                return WebResourceResponse(mimeFor(relPath), null, ByteArrayInputStream(it))
                    .withContentLength(it.size.toLong())
            }
        }
        return null
    }

    companion object {
        // explicit preference order. most Electron apps ship index.html; some hand-picked
        // ones use varied entry names (e.g. play-electron.html).
        private val PREFERRED_NAMES = listOf("index.html", "main.html", "app.html", "start.html")

        // unpacked-electron subdir scan. some titles boot splash.html → src/index.html via
        // start.js, which we never run; so heuristic must reach into src/ to find the real
        // entry. order = developer convention prevalence (src/ most common, then app/, then
        // build outputs). asar games rarely have these (asar root IS the app dir), but
        // including them is harmless -- root checks all run first.
        private val COMMON_SUBDIRS = listOf("src", "app", "dist", "build", "public", "www")

        // matches the .html(?) string literal inside loadFile(...) or loadURL(...) calls.
        // permissive -- handles `loadFile('foo.html')`, `loadFile(path.join(__dirname,'foo.html'))`,
        // and `loadURL('file://.../foo.html')`. captures the LAST .html literal in the call.
        private val LOADFILE_REGEX = Regex(
            """(?:loadFile|loadURL)\s*\(\s*[^)]*?['"]([^'"]+\.html?)['"]""",
        )

        // read package.json.main, then grep that script for loadFile/loadURL HTML target.
        // returns null on any failure (missing main, unreadable, no match, target absent in
        // archive) -- caller falls through to PREFERRED_NAMES heuristics. Cookie Clicker's
        // start.js: loadFile(path.join(__dirname,'/src/index.html')) → captures '/src/index.html'.
        // AD's main.js: loadFile('AppFiles/index.html') → captures 'AppFiles/index.html'.
        private fun entryFromMainScript(archive: ElectronArchive): String? {
            val pkg = archive.packageJson() ?: return null
            val mainName = (pkg["main"] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val mainBytes = archive.read(mainName) ?: return null
            val src = mainBytes.toString(Charsets.UTF_8)
            val match = LOADFILE_REGEX.find(src) ?: return null
            // strip leading "./" and "/" so the path is asar-relative.
            val raw = match.groupValues[1]
            val normalized = raw.trim().removePrefix("./").trimStart('/')
            return if (archive.exists(normalized)) normalized else null
        }

        // Vite dev-mode HTML references unprocessed TS/TSX entry points (`<script type="module"
        // src="/src/main.ts">`). some Electron apps accidentally ship the dev HTML at asar root
        // alongside the production build (Desktop Heroes 3734200 ships both `index.html` (dev)
        // and `build/index.html` (production)). reject candidates that look like dev HTML so the
        // production candidate downstream wins.
        private val VITE_DEV_MARKER = Regex("""<script[^>]+src=["']/?src/[^"']*\.tsx?["']""")
        private fun isViteDevHtml(archive: ElectronArchive, path: String): Boolean {
            val raw = archive.read(path) ?: return false
            // bounded read -- index.html is small in practice; cap to 64KB defensively.
            val text = String(raw, 0, minOf(raw.size, 65_536), Charsets.UTF_8)
            return VITE_DEV_MARKER.containsMatchIn(text)
        }

        // pack:electron apps don't all put index.html at the asar root.
        // heuristic walks candidates in priority order, skips Vite dev-mode HTMLs, picks first.
        // 1) parse package.json.main → read that script → grep loadFile/loadURL literal
        //    (most authoritative when present; Antimatter Dimensions ships AppFiles/index.html,
        //    declared by main.js's mainWindow.loadFile('AppFiles/index.html'))
        // 2) PREFERRED_NAMES at root
        // 3) PREFERRED_NAMES inside COMMON_SUBDIRS (Cookie Clicker → src/index.html, Desktop
        //    Heroes → build/index.html)
        // 4) else list root *.html, drop debug/log/aux variants, rank, return top
        // 5) fallback "index.html" -- caller will 404 with the same message as before the fix
        internal fun resolveEntry(archive: ElectronArchive): String {
            val candidates = mutableListOf<String>()
            entryFromMainScript(archive)?.let { candidates += it }
            for (name in PREFERRED_NAMES) {
                if (archive.exists(name)) candidates += name
            }
            for (subdir in COMMON_SUBDIRS) {
                for (name in PREFERRED_NAMES) {
                    val cand = "$subdir/$name"
                    if (archive.exists(cand)) candidates += cand
                }
            }
            val htmlAtRoot = archive.listFiles("")
                .filter { it.endsWith(".html", ignoreCase = true) }
            val filtered = htmlAtRoot.filter { name ->
                val n = name.lowercase()
                !n.contains("debug") &&
                    !n.contains("-dev") &&
                    !n.startsWith("log") && !n.contains("-log") &&
                    !n.startsWith("io_") && !n.contains("-io") &&
                    !n.startsWith("splash")
            }
            filtered.sortedBy { name ->
                val n = name.lowercase()
                when {
                    n.startsWith("index") -> 0
                    n.contains("play") -> 1
                    n.startsWith("main") -> 2
                    n.startsWith("start") -> 3
                    n.startsWith("app") -> 4
                    else -> 5
                }
            }.forEach { candidates += it }

            // dedup while preserving order, then skip Vite dev HTML.
            val seen = HashSet<String>()
            val unique = candidates.filter { seen.add(it) }
            for (cand in unique) {
                if (!isViteDevHtml(archive, cand)) return cand
            }
            // every candidate was Vite dev (rare) -- fall back to first to preserve old behavior.
            return unique.firstOrNull() ?: "index.html"
        }

        // pure-JVM helper -- no Android deps, unit-testable without WebResourceRequest mocks.
        // reads the resolved entry from the asar + runs shim injection via IndexHtmlRewriter.
        //
        // legacy overload -- tests pass individual params. new callers build IndexInjectionConfig.
        fun readIndexAndInjectFromAsar(
            archive: ElectronArchive,
            entryName: String,
            shimUrls: List<String>,
            locale: String? = null,
            electronCtx: Map<String, String>? = null,
            gestureConfigJson: String? = null,
            electronPreloadUrl: String? = null,
            renderScaleOverride: Float? = null,
            fsBridgeOnly: Boolean = false,
            touchscreenMode: Boolean = true,
        ): ByteArray = readIndexAndInjectFromAsar(
            archive = archive,
            entryName = entryName,
            shimUrls = shimUrls,
            injection = IndexInjectionConfig(
                locale = locale,
                electronCtx = electronCtx,
                gestureConfigJson = gestureConfigJson,
                electronPreloadUrl = electronPreloadUrl,
                renderScaleOverride = renderScaleOverride,
                fsBridgeOnly = fsBridgeOnly,
                touchscreenMode = touchscreenMode,
            ),
        )

        fun readIndexAndInjectFromAsar(
            archive: ElectronArchive,
            entryName: String,
            shimUrls: List<String>,
            injection: IndexInjectionConfig,
        ): ByteArray {
            val bytes = archive.read(entryName)
                ?: error(
                    "asar entry '$entryName' missing — pack:electron fingerprint-time existence check passed but runtime read failed",
                )
            // IndexHtmlRewriter consumes InputStream + returns InputStream -- wrap bytes + drain.
            return ByteArrayInputStream(bytes).use { input ->
                IndexHtmlRewriter.inject(input, shimUrls, injection).readBytes()
            }
        }
    }
}
