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

// electron analog of ZipAssetInterceptor. caller owns the archive lifetime.
// no `patches` support: no electron title needs decrypt/remap/body-replace yet.
class AsarAssetInterceptor(
    context: Context,
    private val archive: ElectronArchive,
    private val shimUrls: List<String>,
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
) : Html5InterceptorBase(context) {

    override val logTag = "AsarAssetInterceptor"

    // the real entry is named in main.js (loadFile(...)), which we never run. WebViewScreen always
    // asks for /index.html; we serve this entry instead.
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
        private val PREFERRED_NAMES = listOf("index.html", "main.html", "app.html", "start.html")

        // some titles boot splash.html -> src/index.html via a script we never run. ordered by prevalence.
        private val COMMON_SUBDIRS = listOf("src", "app", "dist", "build", "public", "www")

        // first .html literal in loadFile(...)/loadURL(...), including path.join(__dirname, ...) forms.
        private val LOADFILE_REGEX = Regex(
            """(?:loadFile|loadURL)\s*\(\s*[^)]*?['"]([^'"]+\.html?)['"]""",
        )

        private fun entryFromMainScript(archive: ElectronArchive): String? {
            val pkg = archive.packageJson() ?: return null
            val mainName = (pkg["main"] as? JsonPrimitive)?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val mainBytes = archive.read(mainName) ?: return null
            val src = mainBytes.toString(Charsets.UTF_8)
            val match = LOADFILE_REGEX.find(src) ?: return null
            val raw = match.groupValues[1]
            val normalized = raw.trim().removePrefix("./").trimStart('/')
            return if (archive.exists(normalized)) normalized else null
        }

        // some apps accidentally ship Vite dev HTML (`src="/src/main.ts"`) at the root next to the
        // real build (e.g. Desktop Heroes: build/index.html). skip it so the production entry wins.
        private val VITE_DEV_MARKER = Regex("""<script[^>]+src=["']/?src/[^"']*\.tsx?["']""")
        private fun isViteDevHtml(archive: ElectronArchive, path: String): Boolean {
            val raw = archive.read(path) ?: return false
            val text = String(raw, 0, minOf(raw.size, 65_536), Charsets.UTF_8)
            return VITE_DEV_MARKER.containsMatchIn(text)
        }

        // priority: main script's loadFile target > PREFERRED_NAMES at root > in COMMON_SUBDIRS >
        // ranked root *.html minus debug/aux variants. Vite dev HTML is skipped.
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

            val seen = HashSet<String>()
            val unique = candidates.filter { seen.add(it) }
            for (cand in unique) {
                if (!isViteDevHtml(archive, cand)) return cand
            }
            return unique.firstOrNull() ?: "index.html"
        }

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
