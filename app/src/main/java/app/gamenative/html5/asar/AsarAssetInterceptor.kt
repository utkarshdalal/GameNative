package app.gamenative.html5.asar

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.gamenative.html5.host.AssetInterceptor
import app.gamenative.html5.host.ElectronAsarSetup
import app.gamenative.html5.host.Html5DiskPath
import app.gamenative.html5.host.Html5InterceptorBase
import app.gamenative.html5.host.IndexHtmlRewriter
import app.gamenative.html5.host.IndexInjectionConfig
import app.gamenative.html5.host.mimeFor
import app.gamenative.html5.host.withContentLength
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber

// electron analog of ZipAssetInterceptor. caller owns the archive lifetime.
// no `patches` support: no electron title needs decrypt/remap/body-replace yet.
class AsarAssetInterceptor(
    context: Context,
    private val archive: ElectronArchive,
    private val shimUrls: List<String>,
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
    // set when main serves the page itself on a real https origin (see ProtocolServedPage); root is the dir under
    // resources it serves from.
    private val protocolPage: ProtocolServedPage? = null,
    private val protocolRoot: File? = null,
    // the wrapped main script (ElectronAsarSetup.mainProcessScript), served at MAIN_PROCESS_SCRIPT_URL.
    private val mainProcessScript: String? = null,
) : Html5InterceptorBase(context) {

    override val logTag = "AsarAssetInterceptor"

    private val protocolRootCanonical: String? by lazy { protocolRoot?.canonicalPath }

    public override fun isGameDocument(url: Uri): Boolean =
        super.isGameDocument(url) || protocolPage?.isPageDocument(url.scheme, url.host, url.path) == true

    // 'self' would be the whole live site; frames and form posts stay inside the locally served prefix.
    public override val documentPolicy: String
        get() = protocolPage?.let { p ->
            val local = "${p.origin}${p.prefix}"
            "frame-src $local data: blob:; object-src $local data: blob:; form-action $local"
        } ?: super.documentPolicy

    // the real entry is named in main.js (loadFile(...)), which we never run. WebViewScreen always
    // asks for /index.html; we serve this entry instead.
    private val resolvedEntry: String = resolveEntry(archive).also {
        Timber.tag("AsarAssetInterceptor").i("resolved asar entry: $it")
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (protocolPage != null) return withDocumentPolicy(serveProtocolPage(request.url, request.isForMainFrame))
        // media falls through to the loopback HTTP server -- see ZipAssetInterceptor for why.
        if (app.gamenative.html5.host.isMediaUrl(request.url)) return null
        return withDocumentPolicy(serve(request.url))
    }

    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        if (path == "/" || path.endsWith("/index.html") || path == "/$resolvedEntry") {
            return runCatching {
                val bytes = readIndexAndInjectFromAsar(archive, resolvedEntry, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("AsarAssetInterceptor").e(it, "entry '$resolvedEntry' rewrite failed for $path")
            }.getOrNull()
        }

        if (path.startsWith("/_shims/")) {
            return openShimAsset(path.removePrefix("/_shims/"))
        }

        if (path == ElectronAsarSetup.MAIN_PROCESS_SCRIPT_URL) {
            val js = mainProcessScript ?: return null
            return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(js.toByteArray(Charsets.UTF_8)))
        }

        // mirrors AssetInterceptor's branch. orig MUST be same-origin.
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

        if (path.startsWith("/_opfs_ready_marker")) {
            return opfsReadyMarkerResponse()
        }

        // fs.readdirSync on absolute paths (some electron titles scan '/conf' for mod configs);
        // Html5FsBridge is save-sandbox only and rejects absolutes.
        if (path.startsWith("/_asar_listdir/") || path == "/_asar_listdir") {
            return openAsarListing(path.removePrefix("/_asar_listdir").removePrefix("/"))
        }

        return openAsarEntry(path.removePrefix("/"))
    }

    // electron's handler: the prefix is local (404 when missing, never the network), our runtime paths and the
    // preload are served on the page's host too since the page asks for them root-relative, and everything else
    // goes to the network like main's net.fetch passthrough. media is answered here as well: a null would send it
    // to the live site, not to the loopback server.
    internal fun serveProtocolPage(uri: Uri, mainFrame: Boolean = false): WebResourceResponse? {
        val page = protocolPage ?: return null
        if (mainFrame && !page.isPageDocument(uri.scheme, uri.host, uri.path)) return refuseOutsideDocument(page, uri)
        if (!"https".equals(uri.scheme, ignoreCase = true) || !page.servesHost(uri.host)) return null
        val path = uri.path ?: return null
        page.localPath(uri.scheme, uri.host, path)?.let { rel -> return serveProtocolLocal(page, rel) }
        val runtimePath = path.startsWith("/_shims/") || path.startsWith("/_worker_stub") ||
            path.startsWith("/_opfs_ready_marker") || path == injection.electronPreloadUrl ||
            path == ElectronAsarSetup.MAIN_PROCESS_SCRIPT_URL
        return if (runtimePath) serve(uri) else null
    }

    // a top-level document outside the prefix would be the live site running with the page's bridges. WebView
    // doesn't ask shouldOverrideUrlLoading for a reload or a POST, so it is answered here, with a way back to the
    // entry page instead of a dead end.
    private fun refuseOutsideDocument(page: ProtocolServedPage, uri: Uri): WebResourceResponse {
        Timber.tag(logTag).w("protocol page: main-frame document outside %s%s refused: %s", page.origin, page.prefix, uri)
        val entry = page.entryUrl.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        val html = """<!DOCTYPE html><meta http-equiv="refresh" content="0;url=$entry">"""
        return WebResourceResponse(
            "text/html", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun serveProtocolLocal(page: ProtocolServedPage, rel: String): WebResourceResponse {
        if (rel == page.entryName) {
            val html = page.entryHtml?.toByteArray(Charsets.UTF_8) ?: protocolFile(rel)?.readBytes()
            if (html != null) {
                runCatching {
                    // first: no service worker may register at the real origin before the app's code runs.
                    val body = IndexHtmlRewriter.inject(ByteArrayInputStream(html), listOf(NO_SERVICE_WORKER_SHIM_URL) + shimUrls, injection)
                    return WebResourceResponse("text/html", "utf-8", body)
                }.onFailure { Timber.tag(logTag).e(it, "protocol page entry rewrite failed") }
            }
        }
        val file = protocolFile(rel) ?: run {
            Timber.tag(logTag).w("protocol page: not found %s", rel)
            return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse(mimeFor(file.name), "utf-8", FileInputStream(file)).withContentLength(file.length())
    }

    private fun protocolFile(rel: String): File? {
        val root = protocolRoot ?: return null
        val rootCanonical = protocolRootCanonical ?: return null
        val canon = Html5DiskPath.resolveCaseInsensitive(root, rel)?.canonicalFile ?: return null
        if (!canon.path.startsWith(rootCanonical + File.separator)) return null
        return canon.takeIf { it.isFile }
    }

    internal fun openAsarListing(relPath: String): WebResourceResponse? {
        if (relPath.contains("..")) return null
        val names = archive.listFiles(relPath)
        val body = buildString {
            append('[')
            names.forEachIndexed { i, n ->
                if (i > 0) append(',')
                append('"')
                // minimal escape: entry names don't carry control chars in practice.
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

    internal fun openAsarEntry(relPath: String): WebResourceResponse? {
        if (relPath.contains("..") || relPath.startsWith("/")) {
            Timber.tag("AsarAssetInterceptor").w("rejecting suspicious asar entry: $relPath")
            return null
        }
        archive.read(relPath)?.let {
            return WebResourceResponse(mimeFor(relPath), null, ByteArrayInputStream(it))
                .withContentLength(it.size.toLong())
        }
        // ABSOLUTE paths in a subdir entry's HTML (`<script src="/assets/x.js">`) resolve to the URL
        // root, but the file lives next to the entry.
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
        const val NO_SERVICE_WORKER_SHIM_URL = "/_shims/no-service-worker.js"

        private val PREFERRED_NAMES = listOf("index.html", "main.html", "app.html", "start.html")

        // some titles boot splash.html -> src/index.html via a script we never run. ordered by prevalence.
        private val COMMON_SUBDIRS = listOf("src", "app", "dist", "build", "public", "www")

        // first .html literal in loadFile(...)/loadURL(...), including path.join(__dirname, ...) forms.
        private val DIRNAME_BASE = Regex("""__dirname|import\.meta\.dirname""")

        private val LOADFILE_REGEX = Regex(
            """(?:loadFile|loadURL)\s*\(\s*[^)]*?['"]([^'"]+\.html?)['"]""",
        )

        // explicit entries: every existing loadFile/loadURL target in the app's own scripts (main first, then the
        // rest of the tree minus node_modules). main often isn't the file that opens the game window -- Bitburner's
        // main only opens exit.html and export.html; gameWindow.js loads index.html -- so the whole app is
        // scanned. targets resolve archive-relative first, then relative to the script's dir.
        private val APP_SCRIPT_EXT = Regex("""\.(c|m)?js$""", RegexOption.IGNORE_CASE)
        private const val APP_SCAN_MAX_DEPTH = 4
        private const val APP_SCAN_MAX_FILES = 200
        private const val APP_SCAN_MAX_BYTES = 8 * 1024 * 1024

        private fun mainScriptName(archive: ElectronArchive): String? =
            (archive.packageJson()?.get("main") as? JsonPrimitive)?.contentOrNull
                ?.trim()?.removePrefix("./")?.trimStart('/')
                ?.takeIf { it.isNotBlank() }

        private fun appScripts(archive: ElectronArchive): List<String> {
            val mainName = mainScriptName(archive)
            val out = mutableListOf<String>()
            mainName?.let { out += it }
            fun walk(dir: String, depth: Int) {
                if (depth > APP_SCAN_MAX_DEPTH || out.size >= APP_SCAN_MAX_FILES) return
                for (name in archive.listFiles(dir).sorted()) {
                    if (name == "node_modules" || name.startsWith(".")) continue
                    val rel = if (dir.isEmpty()) name else "$dir/$name"
                    if (APP_SCRIPT_EXT.containsMatchIn(name) && archive.exists(rel)) {
                        if (rel != mainName) out += rel
                    } else if (archive.listFiles(rel).isNotEmpty()) {
                        walk(rel, depth + 1)
                    }
                    if (out.size >= APP_SCAN_MAX_FILES) return
                }
            }
            walk("", 0)
            return out
        }

        private fun explicitEntries(archive: ElectronArchive): List<String> {
            val out = LinkedHashSet<String>()
            val mainName = mainScriptName(archive)
            for (script in appScripts(archive)) {
                // the cap only bounds the extra-script scan; main is always read, as it was before the scan.
                if (script != mainName && (archive.size(script) ?: continue) > APP_SCAN_MAX_BYTES) continue
                val bytes = archive.read(script) ?: continue
                val scriptDir = script.substringBeforeLast('/', "")
                val text = bytes.toString(Charsets.UTF_8)
                // a commented-out or quoted loadFile(...) is not a page the app opens. if masking leaves main with
                // no target at all, the mask misread something; fall back to the unmasked scan main always had.
                val code = jsCodeMask(text)
                val masked = LOADFILE_REGEX.findAll(text).filter { code[it.range.first] }.toList()
                val matches = if (masked.isEmpty() && script == mainName) LOADFILE_REGEX.findAll(text).toList() else masked
                for (m in matches) {
                    val target = m.groupValues[1].trim()
                    // join(__dirname, "../build/index.html") is relative to the declaring script; a bare target is
                    // relative to the app root (electron resolves loadFile against the app path).
                    val dirnameBased = DIRNAME_BASE.containsMatchIn(m.value)
                    val candidates = if (dirnameBased) {
                        listOf("$scriptDir/$target")
                    } else {
                        listOfNotNull(target, if (scriptDir.isNotEmpty()) "$scriptDir/$target" else null)
                    }
                    candidates.mapNotNull { normalizeArchivePath(it.trimStart('/'))?.takeIf { p -> p.isNotEmpty() } }
                        .firstOrNull { archive.exists(it) }
                        ?.let { out += it }
                }
            }
            return out.toList()
        }

        // shim injection anchors on a <script> tag, so a script-less page (a static quit/notice page) can never
        // run as the entry. among the rest, the game page is the one that pulls in the most script: Bitburner's
        // main opens exit.html (no script) and export.html (small inline module), while its index.html loads
        // the multi-MB bundles.
        private val SCRIPT_ELEMENT = Regex(
            """<script\b([^>]*)>([\s\S]*?)</script\s*>""",
            RegexOption.IGNORE_CASE,
        )

        // double-, single- or unquoted (`src=game.js` is valid HTML). `(?<!\S)` so data-src / data-type don't match.
        private val SRC_ATTR = Regex(
            """(?<!\S)src\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
            RegexOption.IGNORE_CASE,
        )

        private val HTML_COMMENT = Regex("""<!--[\s\S]*?-->""")
        private val BASE_HREF = Regex(
            """<base\b[^>]*?(?<!\S)href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
            RegexOption.IGNORE_CASE,
        )

        // archive dir relative srcs resolve against: the page's dir, or its <base href> when local.
        private fun baseHrefDir(html: String, pageDir: String): String {
            val href = BASE_HREF.find(html)?.let { it.groups[1] ?: it.groups[2] ?: it.groups[3] }?.value?.trim()
                ?: return pageDir
            if (href.isEmpty() || href.contains("://") || href.startsWith("//")) return pageDir
            // a base names a document; its dir is everything up to the last slash.
            val dir = href.substringBefore('?').substringBefore('#').substringBeforeLast('/', "")
            val joined = when {
                href.startsWith("/") || pageDir.isEmpty() -> dir
                else -> "$pageDir/$dir"
            }
            return normalizeArchivePath(joined) ?: pageDir
        }

        private val TYPE_ATTR = Regex(
            """(?<!\S)type\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))""",
            RegexOption.IGNORE_CASE,
        )

        // classic and module scripts only; `application/json`, templates etc. are data blocks, not code.
        private fun isExecutableScript(attrs: String): Boolean {
            val type = TYPE_ATTR.find(attrs)?.let { it.groups[1] ?: it.groups[2] ?: it.groups[3] }?.value
                ?.trim()?.lowercase() ?: return true
            return type.isEmpty() || type == "module" || type.contains("javascript") || type.contains("ecmascript")
        }

        // null = no executable <script> at all. otherwise bytes of local script files referenced + inline script text.
        private fun scriptWeight(archive: ElectronArchive, path: String): Long? {
            // commented-out markup never runs.
            val html = archive.read(path)?.toString(Charsets.UTF_8)?.replace(HTML_COMMENT, "") ?: return null
            val scripts = SCRIPT_ELEMENT.findAll(html).filter { isExecutableScript(it.groupValues[1]) }.toList()
            if (scripts.isEmpty()) return null
            val pageDir = path.substringBeforeLast('/', "")
            // <base href="assets/"> moves where relative srcs resolve.
            val baseDir = baseHrefDir(html, pageDir)
            var weight = 0L
            for (m in scripts) {
                val src = SRC_ATTR.find(m.groupValues[1])?.let { it.groups[1] ?: it.groups[2] ?: it.groups[3] }?.value
                    ?.takeIf { it.isNotBlank() }
                if (src == null) {
                    weight += m.groupValues[2].length
                    continue
                }
                if (src.contains("://") || src.startsWith("//")) continue
                val clean = src.substringBefore('?').substringBefore('#')
                // root-relative srcs miss at the archive root when the file sits next to the entry; the
                // interceptor serves those via the entry-dir fallback, so weigh them the same way.
                val candidates = when {
                    clean.startsWith("/") -> listOfNotNull(
                        clean.trimStart('/'),
                        if (pageDir.isNotEmpty()) "$pageDir/${clean.trimStart('/')}" else null,
                    )
                    baseDir.isEmpty() -> listOf(clean.removePrefix("./"))
                    else -> listOf("$baseDir/${clean.removePrefix("./")}")
                }
                weight += candidates.mapNotNull { normalizeArchivePath(it) }.firstNotNullOfOrNull { archive.size(it) } ?: 0L
            }
            return weight
        }

        // some apps accidentally ship Vite dev HTML (`src="/src/main.ts"`) at the root next to the
        // real build (e.g. Desktop Heroes: build/index.html). skip it so the production entry wins.
        private val VITE_DEV_MARKER = Regex("""<script[^>]+src=["']/?src/[^"']*\.tsx?["']""")
        private fun isViteDevHtml(archive: ElectronArchive, path: String): Boolean {
            val raw = archive.read(path) ?: return false
            val text = String(raw, 0, minOf(raw.size, 65_536), Charsets.UTF_8)
            return VITE_DEV_MARKER.containsMatchIn(text)
        }

        // explicit entries (pages the app's scripts load) win over guessed names: the scripted explicit page with the
        // most script is the entry. only when none is scripted: PREFERRED_NAMES at root > in COMMON_SUBDIRS > ranked
        // root *.html minus debug/aux variants, same weighing. candidate order breaks ties. Vite dev HTML and
        // script-less pages are skipped.
        internal fun resolveEntry(archive: ElectronArchive): String {
            val explicit = explicitEntries(archive)
            heaviestScripted(archive, explicit)?.let { return it }
            val candidates = explicit.toMutableList()
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

            val seen = HashSet<String>()
            val unique = candidates.filter { seen.add(it) }
            heaviestScripted(archive, unique)?.let { return it }
            // nothing scripted: keep the old pick rather than guess.
            for (cand in unique) {
                if (!isViteDevHtml(archive, cand)) return cand
            }
            return unique.firstOrNull() ?: "index.html"
        }

        // resolves `.` / `..` like a URL would; null when the path climbs above the archive root.
        private fun normalizeArchivePath(path: String): String? {
            val out = ArrayDeque<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> out.removeLastOrNull() ?: return null
                    else -> out.addLast(seg)
                }
            }
            return out.joinToString("/")
        }

        // maxByOrNull keeps the first of equal weights, so candidate order is the tie-break.
        private fun heaviestScripted(archive: ElectronArchive, candidates: List<String>): String? =
            candidates.filter { !isViteDevHtml(archive, it) }
                .mapNotNull { cand -> scriptWeight(archive, cand)?.let { cand to it } }
                .maxByOrNull { it.second }
                ?.first

        // test convenience overload; production callers pass IndexInjectionConfig.
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
            return ByteArrayInputStream(bytes).use { input ->
                IndexHtmlRewriter.inject(input, shimUrls, injection).readBytes()
            }
        }
    }
}
