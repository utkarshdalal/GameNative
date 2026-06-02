package app.gamenative.html5.fingerprint

import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile

// for the NW.js single-exe probe: commons-compress honors LFH offsets relative to the zip
// portion, while java.util.zip lands on the MZ header for prefix-data zips. caller owns lifetime.
// SECURITY: entry names are attacker-controllable (user-sideloaded .exe). this adapter returns
// raw names; callers that serve contents MUST reject '..' before passing paths in.
class CommonsZipDirectoryRef(private val zip: CommonsZipFile) : DirectoryRef {
    // commons-compress has no cheap dir-prefix lookup, so snapshot names once.
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
