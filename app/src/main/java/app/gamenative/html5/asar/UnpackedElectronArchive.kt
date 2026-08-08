package app.gamenative.html5.asar

import app.gamenative.html5.host.Html5DiskPath
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// filesystem-backed sibling of AsarArchive for electron apps shipped UNPACKED -- i.e.
// `resources/app/` is a real directory with package.json + main.js, no asar file.
// Cookie Clicker (1454400) is the live reference; many smaller indie electron titles
// also skip the asar pack step. close() is a no-op (no FDs held); kept for ElectronArchive
// parity so WebViewScreen's onDispose path stays uniform.
class UnpackedElectronArchive(private val root: File) : ElectronArchive {

    init {
        require(root.isDirectory) { "unpacked electron root not a directory: ${root.absolutePath}" }
    }

    override fun read(relPath: String): ByteArray? {
        val f = safeFile(relPath) ?: return null
        if (!f.isFile) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    override fun exists(relPath: String): Boolean {
        if (relPath.isEmpty() || relPath == "/" || relPath == ".") return true
        val f = safeFile(relPath) ?: return false
        return f.exists()
    }

    override fun listFiles(relPath: String): List<String> {
        val f = safeFile(relPath) ?: return emptyList()
        if (!f.isDirectory) return emptyList()
        return f.listFiles()?.map { it.name } ?: emptyList()
    }

    override fun packageJson(): JsonObject? {
        val bytes = read("package.json") ?: return null
        return runCatching {
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    override fun close() { /* no FDs held */ }

    // path-safety parity with AsarArchive.isSafePath PLUS canonical-path containment so a
    // symlink inside resources/app/ can't escape the unpacked root. case-insensitive walk via
    // Html5DiskPath -- Windows-authored electron titles assume CI fs (same fundamental as the
    // WebView asset loader path).
    private fun safeFile(relPath: String): File? {
        val cur = Html5DiskPath.resolveCaseInsensitive(root, relPath) ?: return null
        val rootCanon = runCatching { root.canonicalPath }.getOrNull() ?: return null
        val curCanon = runCatching { cur.canonicalPath }.getOrNull() ?: return null
        if (curCanon != rootCanon && !curCanon.startsWith(rootCanon + File.separator)) return null
        return cur
    }
}
