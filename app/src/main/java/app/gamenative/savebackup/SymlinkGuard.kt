package app.gamenative.savebackup

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Shared symbolic-link containment guard for the import codecs ([ArchiveCodec], [RawTreeCodec]).
 *
 * ## Why a leaf-only symlink check is not enough
 *
 * The codecs previously rejected only the *final* component of a destination being a symlink
 * (`Files.isSymbolicLink(destination)`) and relied on a lexical `startsWith(normalizedRoot)` check
 * for containment. Both are insufficient: if any **ancestor** directory between the save root and
 * the destination is a symbolic link, `Files.move` follows it and writes the imported bytes
 * **outside** the intended save root — a path-traversal via symlinked directory (CWE-59). The
 * lexical prefix check does not catch this because the path string still looks contained.
 *
 * [hasSymlinkAncestor] closes that hole by walking every path component from just under [root] down
 * to [destination] and rejecting if any of them is a symbolic link (checked with
 * [LinkOption.NOFOLLOW_LINKS] so the link itself is detected rather than followed). Both codecs call
 * it before staging any bytes, so a symlinked ancestor is rejected atomically with no partial
 * writes, and the rollback-safe commit never moves a file through a symlinked directory.
 */
internal object SymlinkGuard {

    /**
     * True if any path component strictly between [root] and [destination] (inclusive of
     * intermediate directories, exclusive of [root] itself) is a symbolic link. [root] and
     * [destination] should already be normalized and [destination] should be under [root].
     *
     * Only components that currently exist are inspected — a not-yet-created directory cannot be a
     * symlink. The [destination] leaf itself is included so a symlinked target file is also caught.
     */
    fun hasSymlinkAncestor(root: Path, destination: Path): Boolean {
        val normalizedRoot = root.normalize()
        val normalizedDest = destination.normalize()
        if (!normalizedDest.startsWith(normalizedRoot)) {
            // Not contained at all — treat as unsafe.
            return true
        }

        val relative = normalizedRoot.relativize(normalizedDest)
        var current = normalizedRoot
        for (segment in relative) {
            current = current.resolve(segment)
            // Only existing components can be links; a component we will create fresh cannot be.
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                return true
            }
        }
        return false
    }
}
