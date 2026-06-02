package app.gamenative.html5.fingerprint

import java.io.File

class JavaFileDirectoryRef(private val root: File) : DirectoryRef {
    override fun exists(relPath: String): Boolean =
        File(root, relPath.replace('/', File.separatorChar)).exists()

    override fun listFiles(relPath: String): List<String> =
        File(root, relPath.replace('/', File.separatorChar))
            .listFiles()
            ?.map { it.name }
            ?: emptyList()

    // meant for small metadata files (package.json), not engine bundles.
    override fun readText(relPath: String): String? {
        val f = File(root, relPath.replace('/', File.separatorChar))
        if (!f.isFile) return null
        return runCatching { f.readText(Charsets.UTF_8) }.getOrNull()
    }
}
