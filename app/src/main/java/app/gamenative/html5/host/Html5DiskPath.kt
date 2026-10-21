package app.gamenative.html5.host

import java.io.File

// games were authored against case-folding Windows filesystems, so requested paths often differ in
// case from what's on (case-sensitive) android storage. every html5 disk lookup MUST go through here.
//
// ".." segments are always rejected; callers must ALSO check canonical-root containment after the
// walk (symlinks can escape). relPath must be relative to root.
internal object Html5DiskPath {

    // read mode: null on any segment with no case-insensitive match.
    // write mode: a missed segment is appended literally and the walk continues, so fs writes can create
    // NEW paths under case-folded parents.
    fun resolveCaseInsensitive(
        root: File,
        relPath: String,
        writeSemantics: Boolean = false,
    ): File? {
        val normalized = relPath.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return root
        val segments = normalized.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        var current = root
        for (seg in segments) {
            val direct = File(current, seg)
            if (direct.exists()) {
                current = direct
                continue
            }
            val siblings = current.list() ?: if (writeSemantics) emptyArray() else return null
            val match = siblings.firstOrNull { it.equals(seg, ignoreCase = true) }
            current = when {
                match != null -> File(current, match)
                writeSemantics -> File(current, seg)
                else -> return null
            }
        }
        return current
    }
}
