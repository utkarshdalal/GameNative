package app.gamenative.html5.shim

import android.util.Base64
import android.webkit.JavascriptInterface
import app.gamenative.html5.host.Html5DiskPath
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// per-container host side of the node-compat fs shim. JS shim in
// assets/html5/shims/fs.js routes require('fs').writeFileSync / readFileSync / etc. to the
// methods below via window.__gnFsBridge. sandboxed under the per-container install dir ;
// the sandbox root is resolved by SaveDirectoryResolver.resolveSandboxRoot and passed at
// construction time -- no path resolution happens here.

// traversal defense is layered: (1) reject any ".." segment pre-IO; (2) canonical-path must
// start with sandboxRoot.canonicalPath after File construction; (3) absolute paths rejected
// with logging. EVERY reject routes through withinSandbox() so logs share a shape for
// grep-friendly diagnosis.

// NOT Hilt-injected. Constructed per-WebView in WebViewScreen alongside SteamworksJsBridge.
// dies with the WebView -- no onDispose hook needed; File handles release at use {} block end.
class Html5FsBridge(
    private val containerId: String,
    private val sandboxRoot: File,
    // called from every mutating op -- signals that this title uses fs for canonical saves so
    // SaveSyncService's leveldb-rewrite pass should skip (titles that call fs don't want
    // chromium LS/IDB scratch sync'd to cloud). kept as a lambda so the bridge stays pure-JVM
    // testable (no Android Context dependency). WebViewScreen wires to Html5FsAuthoritative.
    // default no-op keeps test constructors happy.
    private val onFsUsage: () -> Unit = {},
    // wine drive_c root for Windows-absolute path translation. when provided, paths matching
    // `[A-Z]:[/\\]...` strip the drive letter and resolve under this root instead of
    // sandboxRoot. matches the project posture of HTML5 games running as the WINDOWS NW.js
    // distribution: process.env.APPDATA = "C:/users/xuser/AppData/Roaming" → game composes
    // "C:/users/xuser/AppData/Roaming/<vendor>/<game>/save.dat" → bridge maps to
    // <wine>/drive_c/users/xuser/AppData/Roaming/<vendor>/<game>/save.dat where Steam UFS /
    // GOG cloud sync read it. only C: accepted (other drive letters rejected). null disables
    // (test fixtures, sideloaded containers without a wine prefix).
    private val wineDriveC: File? = null,
) {
    // cached canonical form so withinSandbox() doesn't canonicalize on every call. the root
    // doesn't change across a WebView session; if it did we'd rebuild the bridge.
    private val sandboxCanonical: String by lazy { sandboxRoot.canonicalPath }
    private val wineDriveCCanonical: String? by lazy { wineDriveC?.canonicalPath }

    // ---------------- v1 sync methods -- 9 exports ----------------

    @JavascriptInterface
    fun writeFile(relPath: String, content: String, encoding: String): Boolean {
        onFsUsage()
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            f.parentFile?.mkdirs()
            val bytes = decode(content, encoding, relPath) ?: return@runCatching false
            f.writeBytes(bytes)
            true
        }.onFailure {
            Timber.tag(TAG).w(it, "writeFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun readFile(relPath: String, encoding: String): String? {
        return runCatching {
            val f = withinSandbox(relPath) ?: run {
                Timber.tag(TAG).d("readFile UNRESOLVED relPath=%s", relPath)
                return@runCatching null
            }
            val exists = f.exists() && f.isFile
            Timber.tag(TAG).d(
                "readFile resolved relPath=%s -> abs=%s exists=%s size=%d mtime=%d",
                relPath, f.absolutePath, exists, if (exists) f.length() else -1L, if (exists) f.lastModified() else 0L,
            )
            if (!exists) return@runCatching null
            encode(f.readBytes(), encoding, relPath)
        }.onFailure {
            Timber.tag(TAG).w(it, "readFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrNull()
    }

    @JavascriptInterface
    fun exists(relPath: String): Boolean {
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            val ok = f.exists()
            // observability for fsBridgeOnly mode: misses that would have fallen through to
            // the asset XHR (and surfaced as a chromium-console 404) now short-circuit
            // silently from the JS shim. logging here gives equivalent coverage server-side.
            // grep `Html5FsBridge.*exists=false` to spot regressions; expected for empty
            // save slots (file%d.rpgsave), suspicious for asset-shaped paths.
            if (!ok) Timber.tag(TAG).d("exists=false relPath=%s -> abs=%s", relPath, f.absolutePath)
            ok
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun unlink(relPath: String): Boolean {
        onFsUsage()
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            f.exists() && f.delete()
        }.onFailure {
            Timber.tag(TAG).w(it, "unlink failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    // returns JSON shape {size, mtimeMs, isFile, isDirectory} -- the minimal set RMMV/RMMZ
    // touch. NO absolute path / sandboxRoot leak threat.
    @JavascriptInterface
    fun stat(relPath: String): String {
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching jsonErr("path rejected")
            if (!f.exists()) return@runCatching jsonErr("ENOENT")
            JSONObject().apply {
                put("size", f.length())
                put("mtimeMs", f.lastModified())
                put("isFile", f.isFile)
                put("isDirectory", f.isDirectory)
            }.toString()
        }.onFailure {
            Timber.tag(TAG).w(it, "stat failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrElse { jsonErr("EIO") }
    }

    @JavascriptInterface
    fun mkdir(relPath: String, recursive: Boolean): Boolean {
        onFsUsage()
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            if (f.exists()) return@runCatching f.isDirectory
            // always create parents, even when the caller didn't pass {recursive:true}. the
            // sandboxed windows tree is SPARSE -- real windows always has C:\Users\x\AppData\Local,
            // ours doesn't -- so a non-recursive mkdir of a deep app path (nw.js Storage.preparePaths
            // mkdirSync'ing C:\Users\xuser\AppData\Local\<game>\Saves) fails on the missing parent.
            // emulating the populated windows tree is the right behavior; `recursive` is now advisory.
            f.mkdirs() || f.isDirectory
        }.onFailure {
            Timber.tag(TAG).w(it, "mkdir failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    // JSON array of entry NAMES only (not paths). empty array for non-existent or non-dir.
    @JavascriptInterface
    fun readdir(relPath: String): String {
        return runCatching {
            val f = withinSandbox(relPath) ?: run {
                Timber.tag(TAG).d("readdir UNRESOLVED relPath=%s", relPath)
                return@runCatching "[]"
            }
            val exists = f.exists()
            val isDir = exists && f.isDirectory
            if (!isDir) {
                Timber.tag(TAG).d(
                    "readdir relPath=%s -> abs=%s exists=%s isDir=%s",
                    relPath, f.absolutePath, exists, isDir,
                )
                return@runCatching "[]"
            }
            val names = f.list()?.sorted().orEmpty()
            Timber.tag(TAG).d(
                "readdir relPath=%s -> abs=%s entries=%d names=%s",
                relPath, f.absolutePath, names.size, names.joinToString(",", limit = 12),
            )
            val arr = JSONArray()
            names.forEach { arr.put(it) }
            arr.toString()
        }.onFailure {
            Timber.tag(TAG).w(it, "readdir failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault("[]")
    }

    @JavascriptInterface
    fun rename(oldRel: String, newRel: String): Boolean {
        onFsUsage()
        return runCatching {
            val src = withinSandbox(oldRel) ?: return@runCatching false
            val dst = withinSandbox(newRel) ?: return@runCatching false
            if (!src.exists()) return@runCatching false
            dst.parentFile?.mkdirs()
            src.renameTo(dst)
        }.onFailure {
            Timber.tag(TAG).w(it, "rename failed containerId=%s oldRel=%s newRel=%s", containerId, oldRel, newRel)
        }.getOrDefault(false)
    }

    @JavascriptInterface
    fun appendFile(relPath: String, content: String, encoding: String): Boolean {
        onFsUsage()
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            f.parentFile?.mkdirs()
            val bytes = decode(content, encoding, relPath) ?: return@runCatching false
            // byte-append -- open in append mode + write decoded bytes. File.appendBytes
            // doesn't exist for java.io.File in kotlin stdlib.
            FileOutputStream(f, true).use { it.write(bytes) }
            true
        }.onFailure {
            Timber.tag(TAG).w(it, "appendFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    // ---------------- encoding ----------------

    // decode JS string content per node-style encoding for write/append. utf8 maps to the raw
    // UTF-8 bytes (matches File.writeText/appendText default charset); base64 decodes. unknown
    // encoding logs + returns null so callers short-circuit to false (the prior `else` branch).
    private fun decode(content: String, encoding: String, relPath: String): ByteArray? = when (encoding) {
        "utf8" -> content.toByteArray(Charsets.UTF_8)
        "base64" -> Base64.decode(content, Base64.DEFAULT)
        else -> {
            Timber.tag(TAG).w("unknown encoding=%s containerId=%s relPath=%s", encoding, containerId, relPath)
            null
        }
    }

    // encode file bytes to a JS string per node-style encoding for read. utf8 maps to a UTF-8
    // string (matches File.readText default charset); base64 encodes NO_WRAP. unknown encoding
    // logs + returns null (the prior `else` branch).
    private fun encode(bytes: ByteArray, encoding: String, relPath: String): String? = when (encoding) {
        "utf8" -> String(bytes, Charsets.UTF_8)
        "base64" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
        else -> {
            Timber.tag(TAG).w("unknown encoding=%s containerId=%s relPath=%s", encoding, containerId, relPath)
            null
        }
    }

    // ---------------- sandbox enforcement ----------------

    // layered traversal defense. returns null on reject -- every JS-exposed method wraps the
    // returned File?. null result + Timber.w log pattern matches AssetInterceptor.openShimAsset.
    
    // internal visibility so tests can call directly without reflection; @VisibleForTesting
    // semantics are enforced by convention -- prod code goes through the public bridge methods
    // only.
    internal fun withinSandbox(relPath: String): File? {
        if (relPath.isBlank()) {
            Timber.tag(TAG).w("empty path rejected containerId=%s", containerId)
            return null
        }
        // Windows-absolute path translation. when wineDriveC is configured (every HTML5
        // container that has a wine prefix), paths like "C:/users/xuser/AppData/..." are
        // mapped to <wine>/drive_c/<rest> instead of being rejected. lets games composing
        // Windows-style paths (via spoofed APPDATA env, or NW.js Windows-build code paths)
        // round-trip through cloud sync without per-title remap.
        val normalized = relPath.replace('\\', '/')
        val winMatch = WINDOWS_ABSOLUTE.matchEntire(normalized)
        if (winMatch != null) {
            val drive = winMatch.groupValues[1].lowercase()
            val tail = winMatch.groupValues[2]
            val driveC = wineDriveC
            val driveCCanonical = wineDriveCCanonical
            if (drive != "c" || driveC == null || driveCCanonical == null) {
                Timber.tag(TAG).w(
                    "windows-absolute path rejected (drive=%s mapped=%s) containerId=%s path=%s",
                    drive, driveC != null, containerId, relPath,
                )
                return null
            }
            return resolveUnderRoot(driveC, driveCCanonical, tail, relPath)
        }
        // unix-style absolutes still rejected -- would land outside sandbox unpredictably.
        if (relPath.startsWith("/") || relPath.startsWith("\\")) {
            Timber.tag(TAG).w("absolute path rejected containerId=%s path=%s", containerId, relPath)
            return null
        }
        return resolveUnderRoot(sandboxRoot, sandboxCanonical, relPath, relPath)
    }

    // shared resolver: layer-1 ".." segment reject + layer-2 canonical-prefix check + case-
    // insensitive fallback. used for both sandbox-rooted and wine-drive_c-rooted resolution.
    // `displayPath` is the original caller-supplied path (for log readability when relPath
    // already had its drive letter stripped).
    private fun resolveUnderRoot(root: File, rootCanonical: String, relPath: String, displayPath: String): File? {
        val normalized = relPath.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) {
            Timber.tag(TAG).w("path escape rejected (layer 1) containerId=%s path=%s", containerId, displayPath)
            return null
        }
        return runCatching {
            val f = File(root, relPath).canonicalFile
            val sep = File.separator
            if (!(f.canonicalPath == rootCanonical || f.canonicalPath.startsWith(rootCanonical + sep))) {
                Timber.tag(TAG).w(
                    "path escape rejected (layer 2) containerId=%s relPath=%s canonical=%s root=%s",
                    containerId,
                    displayPath,
                    f.canonicalPath,
                    rootCanonical,
                )
                null
            } else if (f.exists()) {
                f
            } else {
                // case-insensitive fallback (NW.js + Windows are case-insensitive). returns
                // null on miss → caller falls back to `f` so write/mkdir on a NEW path lands
                // at the original construction (preserves RMMZ atomic-save rename pattern).
                resolveCaseInsensitive(root, rootCanonical, relPath) ?: f
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "path canonicalization failed containerId=%s relPath=%s", containerId, displayPath)
            null
        }
    }

    // delegates to shared Html5DiskPath with writeSemantics=true: on segment miss, append the
    // literal segment so writes/creates to new paths land beneath any case-folded parent.
    // without this, a missing leaf or new intermediate would null out the whole walk and the
    // caller would fall back to the case-unfolded construction -- for wine prefixes this means
    // writes land at drive_c/Users/... when the canonical wine layout is drive_c/users/...
    // CrossCode-class titles compose `C:\Users\xuser\...` from nw.App.dataPath; their first
    // save creates new files inside case-different parent dirs. post-walk re-confinement to
    // the caller's root is kept here because the bridge has multiple roots (sandbox vs
    // drive_c) and the shared util is root-agnostic.
    private fun resolveCaseInsensitive(root: File, rootCanonical: String, relPath: String): File? {
        val current = Html5DiskPath.resolveCaseInsensitive(root, relPath, writeSemantics = true)
            ?: return null
        val canonical = current.canonicalPath
        val sep = File.separator
        if (canonical == rootCanonical || canonical.startsWith(rootCanonical + sep)) {
            return current
        }
        return null
    }

    // node-style error JSON for stat -- shim maps this to a throwing Error on JS side.
    private fun jsonErr(code: String): String =
        JSONObject().apply { put("error", code) }.toString()

    companion object {
        private const val TAG = "Html5FsBridge"
        // recognizes "C:/...", "C:\\...", "c:/..." etc. captures the drive letter + tail.
        private val WINDOWS_ABSOLUTE = Regex("^([A-Za-z]):[/\\\\]+(.*)\$")
    }
}
