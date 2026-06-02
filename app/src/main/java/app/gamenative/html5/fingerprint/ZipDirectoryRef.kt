package app.gamenative.html5.fingerprint

import java.util.zip.ZipFile

// caller owns ZipFile lifetime.
// SECURITY: entry names are attacker-controllable (user-sideloaded package.nw). this adapter
// returns raw names; callers that serve contents MUST reject '..' before passing paths in.
class ZipDirectoryRef(private val zip: ZipFile) : DirectoryRef {
    override fun exists(relPath: String): Boolean {
        val norm = relPath.trimEnd('/')
        if (zip.getEntry(norm) != null) return true
        // zips may omit directory entries, so fall back to a prefix scan.
        if (zip.getEntry("$norm/") != null) return true
        val prefix = "$norm/"
        return zip.entries().asSequence().any { it.name.startsWith(prefix) }
    }

    override fun listFiles(relPath: String): List<String> {
        val prefix = if (relPath.isEmpty() || relPath == ".") "" else "${relPath.trimEnd('/')}/"
        return zip.entries().asSequence()
            .filter { it.name.startsWith(prefix) && it.name != prefix }
            .map { it.name.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }

    override fun readText(relPath: String): String? {
        val entry = zip.getEntry(relPath.trimEnd('/')) ?: return null
        if (entry.isDirectory) return null
        return runCatching {
            zip.getInputStream(entry).use { it.reader(Charsets.UTF_8).readText() }
        }.getOrNull()
    }
}
