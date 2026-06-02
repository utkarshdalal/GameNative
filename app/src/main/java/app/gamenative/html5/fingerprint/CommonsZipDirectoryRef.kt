package app.gamenative.html5.fingerprint

import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

// adapter over an org.apache.commons.compress ZipFile -- sibling to ZipDirectoryRef
// (java.util.zip). used by the NW.js single-exe probe, where commons-compress is required
// because it honors LFH offsets relative to the zip portion (java.util.zip lands on the MZ
// header at byte 0 for prefix-data zips). entry names are forward-slash; trailing slash
// indicates directory (zip convention). caller owns ZipFile lifetime -- close when done.
// SECURITY: entry names are attacker-controllable (user-sideloaded .exe). this adapter returns
// raw names; callers that serve contents MUST reject '..' before passing paths in. the prefix
// guard mirrors ZipDirectoryRef exactly so the traversal logic lives in one shape per backend.
class CommonsZipDirectoryRef(private val zip: CommonsZipFile) : DirectoryRef {
    // commons-compress doesn't expose an O(1) entry lookup that distinguishes dir-prefixes, so we
    // snapshot the name set once and reuse it across exists/listFiles.
    private val entryNames: Set<String> = zip.entries.asSequence().map { it.name }.toSet()

    override fun exists(relPath: String): Boolean {
        val norm = relPath.trimEnd('/')
        if (entryNames.contains(norm)) return true
        if (entryNames.contains("$norm/")) return true
        val prefix = "$norm/"
        return entryNames.any { it.startsWith(prefix) }
    }

    override fun listFiles(relPath: String): List<String> {
        val prefix = if (relPath.isEmpty() || relPath == ".") "" else "${relPath.trimEnd('/')}/"
        return entryNames
            .filter { it.startsWith(prefix) && it != prefix }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    override fun readText(relPath: String): String? {
        val entry = zip.getEntry(relPath.trimEnd('/')) ?: return null
        if (entry.isDirectory) return null
        return runCatching {
            zip.getInputStream(entry).use { it.reader(Charsets.UTF_8).readText() }
        }.getOrNull()
    }
}
