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

// serves from a caller-owned, read-only ZipFile. commons-compress, NOT java.util.zip: old NW.js single-exe
// builds prepend nw.exe to the zip, and java.util.zip treats entry offsets as absolute and lands in the exe.
class ZipAssetInterceptor(
    context: Context,
    private val zipFile: ZipFile,
    private val shimUrls: List<String>,
    private val patches: List<Patch> = emptyList(),
    private val decryptContext: Html5DecryptContext? = null,
    private val injection: IndexInjectionConfig = IndexInjectionConfig(),
    // TyranoScript .tpatch zips, checked BEFORE the main zip; the last one wins. caller owns them.
    private val overlayZips: List<ZipFile> = emptyList(),
    // like NW.js, fall back to loose files next to the zip (many titles ship a loose data/ folder).
    private val installDir: File? = null,
    // see AssetInterceptor.
    private val shouldWaitForMainHydrationProvider: () -> Boolean = { false },
    private val winSaveRootProvider: () -> String? = { null },
) : Html5InterceptorBase(context) {

    override val logTag = "ZipAssetInterceptor"

    override fun onShimServed(shimName: String) {
        Timber.tag("ZipAssetInterceptor").d("shim served: %s", shimName)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        // media MUST fall through to the loopback server -- see isMediaUrl.
        if (isMediaUrl(request.url)) return null
        return serve(request.url)
    }

    // also called recursively by the worker-stub path to inline `orig`.
    fun serve(uri: android.net.Uri): WebResourceResponse? {
        val path = uri.path ?: return null

        val rewrittenPath = PatchApplication.applyUrlRedirects(path, patches) ?: path

        if (rewrittenPath == "/" || rewrittenPath.endsWith("/index.html")) {
            return runCatching {
                val bytes = readIndexAndInjectFromZip(zipFile, shimUrls, injection)
                WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(bytes))
            }.onFailure {
                Timber.tag("ZipAssetInterceptor").e(it, "index.html rewrite failed for $rewrittenPath")
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

        // fs.readdirSync backend: some c2 titles enumerate asset folders to preload music.
        if (rewrittenPath.startsWith("/_asar_listdir/") || rewrittenPath == "/_asar_listdir") {
            return openListing(rewrittenPath.removePrefix("/_asar_listdir").removePrefix("/"))
        }

        // explicit 404 rather than null: null falls through to a redundant loopback fetch, which is noisy
        // for titles probing many optional files. assetExistsSync reads 404 as false.
        val base = openZipEntry(rewrittenPath.removePrefix("/"))
            ?: openDiskFile(rewrittenPath)
            ?: return notFoundResponse()

        return PatchApplication.applyServeTime(base, rewrittenPath, patches, decryptContext)
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    // directories return an empty 200 so existsSync succeeds. canonical path must stay under installDir.
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
                // text/plain, not text/html, so XHR doesn't try to parse it.
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

    private fun resolveDiskCaseInsensitive(root: File, rel: String): File? =
        Html5DiskPath.resolveCaseInsensitive(root, rel)

    // merges loose disk children with zip entries; disk wins on collision, as in NW.js.
    private fun openListing(relPath: String): WebResourceResponse {
        if (relPath.contains("..")) {
            return WebResourceResponse(
                "application/json", "utf-8", ByteArrayInputStream("[]".toByteArray(Charsets.UTF_8)),
            )
        }
        val seen = LinkedHashSet<String>()

        installDir?.canonicalFile?.let { root ->
            val dir = if (relPath.isEmpty()) root else resolveDiskCaseInsensitive(root, relPath)
            if (dir != null && dir.canonicalFile.path.startsWith(root.path) &&
                dir.exists() && dir.isDirectory
            ) {
                dir.list()?.forEach { seen.add(it) }
            }
        }

        // direct children only.
        val prefix = if (relPath.isEmpty()) "" else "$relPath/"
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


    internal fun openZipEntry(relPath: String): WebResourceResponse? {
        if (relPath.contains("..") || relPath.startsWith("/")) {
            Timber.tag("ZipAssetInterceptor").w("rejecting suspicious zip entry name: $relPath")
            return null
        }
        // overlays shadow the main zip; later overlays win.
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
        // test convenience overload; production builds an IndexInjectionConfig.
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
