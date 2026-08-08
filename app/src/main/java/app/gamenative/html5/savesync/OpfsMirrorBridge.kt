package app.gamenative.html5.savesync

import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import timber.log.Timber
import java.io.File

// per-WebView bridge that marshals install-dir bytes
// to/from OPFS via the worker. registered as `__gnOpfsMirrorBridge` in WebViewScreen for
// pack:c3+workerShim containers only. dies with the WebView (no Hilt -- per-launch lifecycle
// mirrors Html5FsBridge). all operations canonical-path-rooted under installRoot;
// path traversal rejected with logcat warn.

// rootResolver is consulted on EVERY call so the destination can swap from the initial
// fallback (container.installPath) to the cloud-sync target (wine prefix Saved Games dir)
// once Html5SaveSyncService.pullInstallToOpfs has run resolveSetup. without the dynamic
// resolver, OPFS bytes flushed to the install dir on exit, but GOG / Steam cloud upload
// scans the wine prefix path -- uploads found nothing.

// why not Hilt? per-WebView lifecycle. installRoot varies per container, and the bridge
// dies with webView.destroy(). Html5FsBridge follows the same pattern .
class OpfsMirrorBridge(
    private val containerId: String,
    private val rootResolver: () -> File,
    // true once rootResolver returns the wine save dir (not the installPath fallback).
    // hydration shim polls this before consuming listInstallFiles -- calling it on the
    // installPath fallback would enumerate game install files instead of saves.
    private val isInboundReadyResolver: () -> Boolean = { true },
    // true when wine just received fresh bytes from cloud (Html5SaveSyncService.syncInbound
    // ran because wine > lastApplied). opfs-hydrate-inbound switches to OVERWRITE semantics
    // when true; otherwise keeps SKIP-IF-EXISTS so unflushed in-game OPFS saves survive a
    // crash-mid-flush relaunch. defaults to false ⇒ backwards-compatible SKIP-IF-EXISTS.
    private val shouldOverwriteOnHydrateResolver: () -> Boolean = { false },
    private val onFlushDone: () -> Unit = {},
) {
    private val installRoot: File get() = rootResolver()
    private val installCanonical: String get() = installRoot.canonicalPath

    init {
        Timber.tag(TAG).d(
            "constructed containerId=%s initialRoot=%s",
            containerId,
            installRoot.absolutePath,
        )
    }

    // returns JSON array of relative paths (from installRoot) for files under <relSubdir>.
    // recursive walk; symlinks not followed. js calls this at worker bootstrap (launch
    // pull) to know what to hydrate into OPFS.
    @JavascriptInterface
    fun listInstallFiles(relSubdir: String): String {
        val root = withinSandbox(relSubdir) ?: return "[]"
        if (!root.exists()) return "[]"
        val baseLen = installCanonical.length + 1
        val out = JSONArray()
        return runCatching {
            if (root.isFile) {
                out.put(root.canonicalPath.substring(baseLen).replace('\\', '/'))
            } else {
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    out.put(f.canonicalPath.substring(baseLen).replace('\\', '/'))
                }
            }
            out.toString()
        }.onFailure {
            Timber.tag(TAG).w(it, "listInstallFiles failed containerId=%s relSubdir=%s", containerId, relSubdir)
        }.getOrDefault("[]")
    }

    // returns base64 of file bytes; null if missing or rejected.
    @JavascriptInterface
    fun readInstallFile(relPath: String): String? {
        val f = withinSandbox(relPath) ?: return null
        if (!f.exists() || !f.isFile) return null
        return runCatching {
            Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }.onFailure {
            Timber.tag(TAG).w(it, "readInstallFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrNull()
    }

    // writes base64-decoded bytes to <installRoot>/<relPath>. creates parent dirs. returns
    // true on success; false on traversal reject or io error.
    @JavascriptInterface
    fun writeInstallFile(relPath: String, base64: String): Boolean {
        val f = withinSandbox(relPath) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            f.writeBytes(bytes)
            true
        }.onFailure {
            Timber.tag(TAG).w(it, "writeInstallFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    // returns true once Html5SaveSyncService.pullInstallToOpfs has resolved the wine save
    // dir for this container -- i.e. listInstallFiles/readInstallFile point at saves, not
    // the installPath fallback. opfs-hydrate-inbound.js polls this before pumping bytes
    // into OPFS.
    @JavascriptInterface
    fun isInboundReady(): Boolean = isInboundReadyResolver()

    // see ctor doc.
    @JavascriptInterface
    fun shouldOverwriteOnHydrate(): Boolean = shouldOverwriteOnHydrateResolver()

    // signals exit-boundary flush complete. WebViewScreen waits on this before destroy.
    @JavascriptInterface
    fun markFlushDone() {
        Timber.tag(TAG).i("FLUSH done containerId=%s", containerId)
        onFlushDone()
    }

    // probe readback. js posts the probe bytes (hex-encoded) once OPFS init succeeds.
    @JavascriptInterface
    fun reportProbe(payload: String) {
        Timber.tag(TAG).i("PROBE OK containerId=%s payload=%s", containerId, payload)
    }

    // flush summary. js posts after each flush wave -> recurring during gameplay, keep at debug.
    @JavascriptInterface
    fun logFlush(n: Int, bytes: Long) {
        Timber.tag(TAG).d("FLUSH n=%d bytes=%d containerId=%s", n, bytes, containerId)
    }

    // canonical-path defense -- reject any path that escapes installRoot. mirror of
    // Html5FsBridge.withinSandbox. empty relPath is legal here: it represents installRoot itself (used by
    // listInstallFiles("") for full-tree walk). other ops never call with "".
    private fun withinSandbox(relPath: String): File? {
        if (relPath.indexOf('\u0000') >= 0) {
            Timber.tag(TAG).w("withinSandbox reject: null byte containerId=%s", containerId)
            return null
        }
        return runCatching {
            val target = File(installRoot, relPath).canonicalFile
            val sep = File.separator
            if (!(target.canonicalPath == installCanonical || target.canonicalPath.startsWith(installCanonical + sep))) {
                Timber.tag(TAG).w("withinSandbox reject: escape containerId=%s relPath=%s", containerId, relPath)
                return@runCatching null
            }
            target
        }.onFailure {
            Timber.tag(TAG).w(it, "withinSandbox failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Html5WorkerShim"
    }
}
