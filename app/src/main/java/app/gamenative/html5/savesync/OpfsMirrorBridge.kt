package app.gamenative.html5.savesync

import android.util.Base64
import app.gamenative.html5.isGnTempName
import app.gamenative.html5.writeBytesAtomic
import android.webkit.JavascriptInterface
import org.json.JSONArray
import timber.log.Timber
import java.io.File

// moves save bytes between disk and OPFS for pack:c3 worker-shim containers; one per WebView,
// like Html5FsBridge. every path is sandboxed under the current root.
//
// rootResolver is consulted on EVERY call: the root starts as the installPath fallback and switches
// to the Wine save dir once pullInstallToOpfs resolves it. a fixed root would flush saves into the
// install dir, where cloud upload never looks.
class OpfsMirrorBridge(
    private val containerId: String,
    private val rootResolver: () -> File,
    // true once rootResolver returns the wine save dir. hydrating before that would enumerate
    // game install files instead of saves.
    private val isInboundReadyResolver: () -> Boolean = { true },
    // true when inbound sync just brought newer Wine bytes: hydration then OVERWRITES OPFS. otherwise
    // it skips existing files so unflushed OPFS saves survive a crash-mid-flush relaunch.
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

    // JSON array of root-relative paths under <relSubdir>.
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
                root.walkTopDown().filter { it.isFile && !isGnTempName(it.name) }.forEach { f ->
                    out.put(f.canonicalPath.substring(baseLen).replace('\\', '/'))
                }
            }
            out.toString()
        }.onFailure {
            Timber.tag(TAG).w(it, "listInstallFiles failed containerId=%s relSubdir=%s", containerId, relSubdir)
        }.getOrDefault("[]")
    }

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

    @JavascriptInterface
    fun writeInstallFile(relPath: String, base64: String): Boolean {
        val f = withinSandbox(relPath) ?: return false
        return runCatching {
            f.parentFile?.mkdirs()
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            f.writeBytesAtomic(bytes)
            true
        }.onFailure {
            Timber.tag(TAG).w(it, "writeInstallFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun isInboundReady(): Boolean = isInboundReadyResolver()

    @JavascriptInterface
    fun shouldOverwriteOnHydrate(): Boolean = shouldOverwriteOnHydrateResolver()

    // WebViewScreen waits on this before destroy.
    @JavascriptInterface
    fun markFlushDone() {
        Timber.tag(TAG).i("FLUSH done containerId=%s", containerId)
        onFlushDone()
    }

    @JavascriptInterface
    fun reportProbe(payload: String) {
        Timber.tag(TAG).i("PROBE OK containerId=%s payload=%s", containerId, payload)
    }

    // fires repeatedly during gameplay; keep at debug.
    @JavascriptInterface
    fun logFlush(n: Int, bytes: Long) {
        Timber.tag(TAG).d("FLUSH n=%d bytes=%d containerId=%s", n, bytes, containerId)
    }

    // empty relPath is legal: it means the root itself (listInstallFiles("") walks the whole tree).
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
