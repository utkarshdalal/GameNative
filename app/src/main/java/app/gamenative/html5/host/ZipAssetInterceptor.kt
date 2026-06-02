package app.gamenative.html5.host

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import app.gamenative.html5.profile.Patch
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import org.apache.commons.compress.archivers.zip.ZipFile

// sibling of AssetInterceptor. reads all resources from an open ZipFile provided by
// the caller (WebViewScreen owns its lifetime via remember/onDispose). intercept-don't-mutate
// preserved: ZipFile opened read-only; responses synthesized or passthrough getInputStream.
// commons-compress ZipFile (not java.util.zip) so NW.js single-exe bundles work -- old NW.js
// builds prepend the nw.exe wrapper to the package.nw zip; java.util.zip reads LFH offsets
// as absolute file offsets and lands on the MZ header. commons-compress handles prefix data.
class ZipAssetInterceptor(
    context: Context,
    private val zipFile: ZipFile,
    private val shimUrls: List<String>,
    private val patches: List<Patch> = emptyList(),
    private val decryptContext: Html5DecryptContext? = null,
    // parse-time HTML injection knobs -- see IndexInjectionConfig. defaults to no-op config.
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    // pack-level overlay zips (TyranoScript .tpatch files: scenario/asset overrides shipped
    // alongside the main install). checked BEFORE the main zip -- last-overlay wins (caller
    // orders the list). caller owns lifetime; we close-by-reference. empty = no overlay.
    private val overlayZips: List<ZipFile> = emptyList(),
    // NW.js parity: many titles ship a `data/` folder LOOSE alongside package.nw (e.g.
    // `data/misc/*` companion files, mod folders, etc.). NW.js's runtime reads both -- zip
    // + sibling disk files. when set, we mirror that by falling back to disk under installDir
    // on zip miss. null preserves test/back-compat (3-arg ctor).
    private val installDir: File? = null,
    // see AssetInterceptor.shouldWaitForMainHydrationProvider -- same role here.
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
    // see AssetInterceptor.winSaveRootProvider -- injected as self.__gnWinSaveRoot into worker
    // stubs so worker-fs.js maps the win32 game's absolute C:/ save paths onto OPFS.
    private val winSaveRootProvider: () -> String? = { null },
) : Html5InterceptorBase(context) {

    override val logTag = "ZipAssetInterceptor"

    // ZipAssetInterceptor alone emits a "shim served" diagnostic on success.
    override fun onShimServed(shimName: String) {
        Timber.tag("ZipAssetInterceptor").d("shim served: %s", shimName)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // media URLs MUST fall through to the loopback HTTP server: chromium's media pipeline
        // calls seek() on the source to read Cues / index data, and a WebResourceResponse-
        // wrapped InputStream isn't seekable. the HTTP path advertises Accept-Ranges +
        // Content-Length so the FFmpeg demuxer can seek properly via Range requests.
        // return null here → WebView falls back to a real HTTP fetch.
        if (isMediaUrl(request.url)) return null
        return serve(request.url)
    }

    // see AssetInterceptor.serve for the why; same recursive inline-orig consumer here.
    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        // rewrite URL path before asset lookup.
        val rewrittenPath = PatchApplication.applyUrlRedirects(path, patches) ?: path

        // top-level HTML: rewrite with shim injection from zip's root index.html.
        if (rewrittenPath == "/" || rewrittenPath.endsWith("/index.html")) {
            return runCatching {
                val bytes = readIndexAndInjectFromZip(zipFile, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("ZipAssetInterceptor").e(it, "index.html rewrite failed for $rewrittenPath")
            }.getOrNull()
        }

        // synthesized shim path -- never exists in zip.
        if (rewrittenPath.startsWith("/_shims/")) {
            return openShimAsset(rewrittenPath.removePrefix("/_shims/"))
        }

        // worker entry synthesis. mirrors AssetInterceptor branch so zip-hosted pack:c3 hits
        // it too. PICK = classic-worker+sync-XHR. orig MUST be same-origin.
        if (rewrittenPath.startsWith("/_worker_stub")) {
            val shouldWait = runCatching { shouldWaitForMainHydrationProvider() }.getOrDefault(false)
            val winSaveRoot = runCatching { winSaveRootProvider() }.getOrNull()
            return serveWorkerStub(uri, shouldWait, ::serve, winSaveRoot)
        }

        // classic-worker variant: 1-byte 200 response. see AssetInterceptor for rationale.
        if (rewrittenPath.startsWith("/_opfs_ready_marker")) {
            return opfsReadyMarkerResponse()
        }

        // synthetic directory-listing endpoint for fs.readdirSync. c2 titles enumerate sibling
        // asset folders (e.g. `data/audio/soundscapes/<name>/`); without this, music never
        // preloads. JSON body is the merged set of zip entries + loose disk children under the
        // requested relative path, deduped (disk wins for same-name).
        if (rewrittenPath.startsWith("/_asar_listdir/") || rewrittenPath == "/_asar_listdir") {
            return openListing(rewrittenPath.removePrefix("/_asar_listdir").removePrefix("/"))
        }

        // all other paths resolve against zip entries first, then fall back to loose disk
        // files under installDir (NW.js parity -- see ctor doc). origin binding preserved --
        // WebViewAssetLoader is NOT consulted for zip containers.
        // miss-path: synthesize a 404 instead of returning null. null lets WebView fall
        // through to a real network load against `gamenative` (a fake host) which DNS-fails
        // with ERR_NAME_NOT_RESOLVED -- extremely noisy for c2 titles that probe many optional
        // files (screensavers, theme overrides). 404 is the right shape for "asset doesn't
        // exist" and assetExistsSync interprets it correctly as false.
        val base = openZipEntry(rewrittenPath.removePrefix("/"))
            ?: openDiskFile(rewrittenPath)
            ?: return notFoundResponse()

        // serve-time transforms (audio-ext-remap, asset-decrypt, body-replace).
        return PatchApplication.applyServeTime(base, rewrittenPath, patches, decryptContext)
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    // disk fallback for NW.js external files. traversal-guarded: canonical path must stay
    // under installDir.canonicalPath. %xx-decoded
    // because installDir is on disk and not URI-aware. case-insensitive segment fallback
    // matches Html5FsBridge.resolveCaseInsensitive -- c2 titles ship settings like
    // `Theme|Default` but disk has lowercase `default/` folders. directories return
    // a 200 empty body so HEAD/GET-based existsSync checks succeed; readFileSync on a dir
    // gets an empty string but that's a game bug to surface, not silently mask.
    private fun openDiskFile(path: String): WebResourceResponse? {
        val root = installDir?.canonicalFile ?: return null
        return runCatching {
            val rel = path.removePrefix("/")
            if (rel.isEmpty()) return@runCatching null
            val f = resolveDiskCaseInsensitive(root, rel) ?: return@runCatching null
            val canon = f.canonicalFile
            if (!canon.path.startsWith(root.path)) return@runCatching null
            if (!canon.exists()) return@runCatching null
            if (canon.isDirectory) {
                // 200 empty so existsSync of a directory returns true. mime intentionally
                // text/plain not text/html so XHR can read body without HTML parsing.
                return@runCatching WebResourceResponse(
                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                )
            }
            if (!canon.isFile) return@runCatching null
            WebResourceResponse(mimeFor(canon.name), null, FileInputStream(canon))
                .withContentLength(canon.length())
        }.onFailure {
            Timber.tag("ZipAssetInterceptor").w(it, "disk fallback open failed for %s", path)
        }.getOrNull()
    }

    // case-insensitive segment walk via Html5DiskPath -- shared with WebViewScreen path handler
    // and UnpackedElectronArchive so every disk asset surface case-folds the same way.
    private fun resolveDiskCaseInsensitive(root: File, rel: String): File? =
        Html5DiskPath.resolveCaseInsensitive(root, rel)

    // /_asar_listdir handler -- merges zip entries under prefix with loose disk children.
    // disk takes precedence for collisions (NW.js runtime semantics: disk overrides archive).
    // case-insensitive disk lookup for the directory path itself, then literal child names.
    private fun openListing(relPath: String): WebResourceResponse {
        if (relPath.contains("..")) {
            return WebResourceResponse(
                "application/json", "utf-8", ByteArrayInputStream("[]".toByteArray(Charsets.UTF_8)),
            )
        }
        val seen = LinkedHashSet<String>()

        // disk side first so disk wins on name collision.
        installDir?.canonicalFile?.let { root ->
            val dir = if (relPath.isEmpty()) root else resolveDiskCaseInsensitive(root, relPath)
            if (dir != null && dir.canonicalFile.path.startsWith(root.path) &&
                dir.exists() && dir.isDirectory
            ) {
                dir.list()?.forEach { seen.add(it) }
            }
        }

        // zip entries with the prefix. emit only the FIRST path segment after the prefix
        // (children of the requested dir, not deeper descendants).
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
        // commons-compress: entries is a property (Kotlin syntax via getEntries()), Enumeration<ZipArchiveEntry>.
        val entries = zipFile.entries
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name
            if (prefix.isNotEmpty() && !name.startsWith(prefix)) continue
            val tail = name.substring(prefix.length).trimEnd('/')
            if (tail.isEmpty()) continue
            val firstSeg = tail.substringBefore('/')
            if (firstSeg.isNotEmpty()) seen.add(firstSeg)
        }

        val body = buildString {
            append('[')
            seen.forEachIndexed { i, n ->
                if (i > 0) append(',')
                append('"')
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
        return WebResourceResponse(
            "application/json", "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    }


    // internal so unit tests can exercise traversal guards directly without WebResourceRequest mocks.
    internal fun openZipEntry(relPath: String): WebResourceResponse? {
        if (relPath.contains("..") || relPath.startsWith("/")) {
            // zip-slip defense -- attacker-controllable entry names rejected.
            Timber.tag("ZipAssetInterceptor").w("rejecting suspicious zip entry name: $relPath")
            return null
        }
        // overlay zips win over the main install (TyranoScript .tpatch convention: zip whose
        // contents shadow files at the same relative path under the install). later entries
        // in overlayZips override earlier ones -- caller orders by precedence (newest mtime
        // last). main zip only consulted when no overlay has the entry.
        for (i in overlayZips.indices.reversed()) {
            val overlay = overlayZips[i]
            val overlayEntry = overlay.getEntry(relPath)
            if (overlayEntry != null && !overlayEntry.isDirectory) {
                return runCatching {
                    WebResourceResponse(mimeFor(overlayEntry.name), null, overlay.getInputStream(overlayEntry))
                        .withContentLength(overlayEntry.size)
                }.onFailure {
                    Timber.tag("ZipAssetInterceptor").w(it, "overlay getInputStream failed for $relPath")
                }.getOrNull()
            }
        }
        val entry = zipFile.getEntry(relPath) ?: return null
        if (entry.isDirectory) return null
        return runCatching {
            WebResourceResponse(mimeFor(entry.name), null, zipFile.getInputStream(entry))
                .withContentLength(entry.size)
        }.onFailure {
            Timber.tag("ZipAssetInterceptor").w(it, "zip getInputStream failed for $relPath")
        }.getOrNull()
    }

    companion object {
        // pure-jvm helper -- unit-testable without WebResourceRequest mocks.
        // reads "index.html" from zip root + runs shim injection via IndexHtmlRewriter.
        // path-traversal defense belt-and-suspenders: entry name must have no ".." or leading "/".
        //
        // legacy overload -- tests pass individual params. new callers build IndexInjectionConfig.
        fun readIndexAndInjectFromZip(
            zip: ZipFile,
            shimUrls: List<String>,
            locale: String? = null,
            gestureConfigJson: String? = null,
            renderScaleOverride: Float? = null,
            fsBridgeOnly: Boolean = false,
            touchscreenMode: Boolean = true,
        ): ByteArray = readIndexAndInjectFromZip(
            zip = zip,
            shimUrls = shimUrls,
            injection = IndexInjectionConfig(
                locale = locale,
                gestureConfigJson = gestureConfigJson,
                renderScaleOverride = renderScaleOverride,
                fsBridgeOnly = fsBridgeOnly,
                touchscreenMode = touchscreenMode,
            ),
        )

        fun readIndexAndInjectFromZip(
            zip: ZipFile,
            shimUrls: List<String>,
            injection: IndexInjectionConfig,
        ): ByteArray {
            val entry = zip.getEntry("index.html")
                ?: error("index.html missing from zip root")
            require(!entry.name.contains("..") && !entry.name.startsWith("/")) {
                "bad entry name: ${entry.name}"
            }
            return zip.getInputStream(entry).use { input ->
                IndexHtmlRewriter.inject(input, shimUrls, injection).readBytes()
            }
        }
    }
}
