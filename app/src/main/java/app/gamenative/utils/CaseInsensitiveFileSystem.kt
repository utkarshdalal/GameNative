package app.gamenative.utils

import `in`.dragonbra.javasteam.depotdownloader.BaseCaseInsensitiveFileSystem
import okio.FileSystem
import okio.Path
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Okio [FileSystem] wrapper that resolves each path component against on-disk
 * casing before delegating to [FileSystem.SYSTEM]. Prevents duplicate directories
 * when Steam depot manifests use different casing than what's already installed
 * (e.g. DLC referencing `_Work` when the base game created `_work`).
 *
 * Only directory segments are resolved case-insensitively; file segments are
 * appended as-is (no caching needed, avoids unbounded growth).
 *
 * Three-level cache, all bounded by directory count:
 * 1. Nested segment cache — parent → (lowercase segment → child). On case
 *    mismatch, pre-populates all siblings so each directory is listed at most once.
 * 2. Lowercase pool — avoids repeated lowercase() allocation for the small
 *    vocabulary of directory names that games reuse.
 * 3. DIRECTORY_OPS set — distinguishes dir ops (resolve all segments) from file
 *    ops (skip last segment).
 */
class CaseInsensitiveFileSystem(
    delegate: FileSystem = SYSTEM,
) : BaseCaseInsensitiveFileSystem(delegate) {

    // parent → (lowercase segment → resolved child). bounded by directory count.
    private val segmentCache = ConcurrentHashMap<Path, ConcurrentHashMap<String, Path>>()

    // segment string → lowercased form. game paths reuse a small set of names.
    private val lowercasePool = ConcurrentHashMap<String, String>()

    private companion object {
        val DIRECTORY_OPS = setOf("createDirectory", "createDirectories")
    }

    override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
        val root = path.root ?: return path
        val segments = path.segments
        if (segments.isEmpty()) return path

        val resolveAll = functionName in DIRECTORY_OPS
        val lastDirIndex = if (resolveAll) segments.lastIndex else segments.lastIndex - 1

        var resolved = root
        for (i in 0..lastDirIndex) {
            val segment = segments[i]
            val lower = lowercasePool.computeIfAbsent(segment) { it.lowercase() }
            val parent = resolved
            val children = segmentCache.computeIfAbsent(parent) { ConcurrentHashMap() }

            val cached = children[lower]
            if (cached != null) {
                resolved = cached
            } else {
                resolved = resolveAndCache(parent, segment, lower, children)
            }
        }

        if (!resolveAll) {
            resolved = resolved / segments.last()
        }

        return resolved
    }

    private fun resolveAndCache(
        parent: Path,
        segment: String,
        lower: String,
        children: ConcurrentHashMap<String, Path>,
    ): Path {
        val exact = parent / segment
        if (delegate.metadataOrNull(exact) != null) {
            children[lower] = exact
            return exact
        }
        // case mismatch — list directory once, pre-populate all siblings
        val listing = delegate.listOrNull(parent)
        if (listing != null) {
            for (entry in listing) {
                val entryLower = lowercasePool.computeIfAbsent(entry.name) { it.lowercase() }
                children.putIfAbsent(entryLower, entry)
            }
        }
        return children[lower] ?: exact.also { children[lower] = it }
    }

    override fun toResolvedFile(path: Path): File {
        val resolvedPath = onPathParameter(path, "toResolvedFile", "path")
        return resolvedPath.toFile()
    }

    override fun removeFileCache(path: Path) {
        // dir-only cache: nothing to remove for individual files
    }

    override fun clearAllCaches() {
        segmentCache.clear()
        lowercasePool.clear()
    }
}
