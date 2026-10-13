package app.gamenative.html5.shim

import android.util.Base64
import android.webkit.JavascriptInterface
import app.gamenative.html5.host.Html5DiskPath
import app.gamenative.html5.isGnTempName
import app.gamenative.html5.writeBytesAtomic
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

// host side of the node-compat fs shim (fs.js). SECURITY: every path is untrusted game input;
// traversal defense is layered: reject ".." segments pre-IO, then require the canonical path to
// stay under its root. EVERY path MUST go through withinSandbox().
// one instance per WebView; holds no open handles, so no dispose hook.
class Html5FsBridge(
    private val containerId: String,
    private val sandboxRoot: File,
    // called from every mutating op: a title that saves via fs must not have its chromium
    // LS/IDB scratch synced to cloud.
    private val onFsUsage: () -> Unit = {},
    // games run as the WINDOWS NW.js build and compose paths like "C:/users/xuser/AppData/...";
    // those map under drive_c, where Steam UFS / GOG cloud sync read them. only C: is accepted.
    // null (no wine prefix) rejects all Windows-absolute paths.
    private val wineDriveC: File? = null,
) {
    private val sandboxCanonical: String by lazy { sandboxRoot.canonicalPath }
    private val wineDriveCCanonical: String? by lazy { wineDriveC?.canonicalPath }

    @JavascriptInterface
    fun writeFile(relPath: String, content: String, encoding: String): Boolean {
        onFsUsage()
        return runCatching {
            val f = withinSandbox(relPath) ?: return@runCatching false
            f.parentFile?.mkdirs()
            val bytes = decode(content, encoding, relPath) ?: return@runCatching false
            f.writeBytesAtomic(bytes)
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
            // in fsBridgeOnly mode misses no longer show as console 404s, so log them here.
            // expected for empty save slots, suspicious for asset-shaped paths.
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

    // deliberately minimal -- never leak the absolute path.
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
            // always create parents: our windows tree is SPARSE (real windows always has
            // AppData\Local), so a non-recursive mkdir of a deep app path would fail.
            f.mkdirs() || f.isDirectory
        }.onFailure {
            Timber.tag(TAG).w(it, "mkdir failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

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
            val names = f.list()?.filterNot(::isGnTempName)?.sorted().orEmpty()
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
            FileOutputStream(f, true).use { it.write(bytes) }
            true
        }.onFailure {
            Timber.tag(TAG).w(it, "appendFile failed containerId=%s relPath=%s", containerId, relPath)
        }.getOrDefault(false)
    }

    // called by fs.js when it switches rmmv saves to fs, before any write: a session that exits
    // before its first save would otherwise sync chromium scratch.
    @JavascriptInterface
    fun markFsSaveMode() {
        onFsUsage()
    }

    private fun decode(content: String, encoding: String, relPath: String): ByteArray? = when (encoding) {
        "utf8" -> content.toByteArray(Charsets.UTF_8)
        "base64" -> Base64.decode(content, Base64.DEFAULT)
        else -> {
            Timber.tag(TAG).w("unknown encoding=%s containerId=%s relPath=%s", encoding, containerId, relPath)
            null
        }
    }

    private fun encode(bytes: ByteArray, encoding: String, relPath: String): String? = when (encoding) {
        "utf8" -> String(bytes, Charsets.UTF_8)
        "base64" -> Base64.encodeToString(bytes, Base64.NO_WRAP)
        else -> {
            Timber.tag(TAG).w("unknown encoding=%s containerId=%s relPath=%s", encoding, containerId, relPath)
            null
        }
    }

    // null = rejected.
    internal fun withinSandbox(relPath: String): File? {
        if (relPath.isBlank()) {
            Timber.tag(TAG).w("empty path rejected containerId=%s", containerId)
            return null
        }
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
        if (relPath.startsWith("/") || relPath.startsWith("\\")) {
            Timber.tag(TAG).w("absolute path rejected containerId=%s path=%s", containerId, relPath)
            return null
        }
        return resolveUnderRoot(sandboxRoot, sandboxCanonical, relPath, relPath)
    }

    // displayPath is the caller's original path, for logs (relPath may have lost its drive letter).
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
                // games assume Windows' case-insensitive fs. a miss keeps `f` so creating a NEW
                // path lands where the game asked (RMMZ's write-then-rename save relies on this).
                resolveCaseInsensitive(root, rootCanonical, relPath) ?: f
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "path canonicalization failed containerId=%s relPath=%s", containerId, displayPath)
            null
        }
    }

    // writeSemantics: a missing segment is appended literally, so a new file under a case-different
    // existing parent (game asks for C:\Users\..., wine has drive_c/users/...) lands in that parent
    // instead of creating a sibling tree. re-confined here because Html5DiskPath is root-agnostic.
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

    private fun jsonErr(code: String): String =
        JSONObject().apply { put("error", code) }.toString()

    companion object {
        private const val TAG = "Html5FsBridge"
        private val WINDOWS_ABSOLUTE = Regex("^([A-Za-z]):[/\\\\]+(.*)\$")
    }
}
