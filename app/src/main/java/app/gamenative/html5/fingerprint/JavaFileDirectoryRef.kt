package app.gamenative.html5.fingerprint

import java.io.File

// production adapter. used by custom-game sideload and depot installs.
// File.separatorChar replacement matches CustomGameScanner idiom (utils/CustomGameScanner.kt).
class JavaFileDirectoryRef(private val root: File) : DirectoryRef {
    override fun exists(relPath: String): Boolean =
        File(root, relPath.replace('/', File.separatorChar)).exists()

    override fun listFiles(relPath: String): List<String> =
        File(root, relPath.replace('/', File.separatorChar))
            .listFiles()
            ?.map { it.name }
            ?: emptyList()

    // small text files only (package.json). large files would be wasteful here -- signatures
    // are expected to peek at metadata, not load engine bundles. swallow read errors → null.
    override fun readText(relPath: String): String? {
        val f = File(root, relPath.replace('/', File.separatorChar))
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }
}
