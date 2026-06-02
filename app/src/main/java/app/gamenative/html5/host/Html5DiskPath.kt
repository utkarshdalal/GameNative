package app.gamenative.html5.host

import java.io.File

// shared case-insensitive segment-by-segment disk resolver. NW.js, Electron and Chromium
// (running in WebView) all serve disk-backed assets case-insensitively because the games were
// authored against Windows where every fs lookup folds case. on Android (case-sensitive ext4 /
// f2fs), a verbatim `File(root, requested)` lookup misses any path whose case drifted between
// authoring and the title's runtime requests (e.g. `Ayami_Intro.webm` requested for
// `ayami_intro.webm` on disk). every disk surface in the html5 runtime MUST go through this
// util so future packs don't each have to re-discover the same fix.
//
// path-traversal: ".." segments are always rejected as a defense-in-depth check. callers should
// ALSO do canonical-root containment AFTER the walk (symlinks can escape any pre-walk check).
// caller must NOT pass an absolute path -- `relPath` is relative to `root`.
internal object Html5DiskPath {

    /**
     * Resolves [relPath] under [root], folding case per segment.
     *
     * READ mode (default, [writeSemantics] = false): returns null on any segment that has no
     * case-insensitive sibling match. Use for asset lookups where "not found" is meaningful.
     *
     * WRITE mode ([writeSemantics] = true): on segment miss, appends the literal segment and
     * keeps walking. Use for fs writes (mkdir / atomic-rename / open-for-create) where the leaf
     * -- or some intermediate dir -- may be a NEW path under a case-folded parent. Impact-engine
     * NW.js titles compose `C:\Users\xuser\...` from `nw.App.dataPath`; their first save creates
     * a new file inside case-different parent dirs.
     */
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
