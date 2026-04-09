package app.gamenative.utils

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Okio [FileSystem] wrapper that resolves each path component against on-disk
 * casing before delegating to [FileSystem.SYSTEM]. Prevents duplicate directories
 * when Steam depot manifests use different casing than what's already installed
 * (e.g. DLC referencing `_Work` when the base game created `_work`).
 *
 * Two-level cache: full-path results are cached so repeat operations on the same
 * path (common during chunk writes) skip per-segment resolution entirely.
 * Per-segment results are cached in a nested map so different paths sharing a
 * common prefix reuse earlier resolution work without allocating keys.
 */
class CaseInsensitiveFileSystem(
    delegate: FileSystem = SYSTEM,
) : ForwardingFileSystem(delegate) {

    // full path → resolved path. most calls hit this and return immediately.
    private val pathCache = ConcurrentHashMap<Path, Path>()

    // parent → (lowercase segment → resolved child path).
    // nested map avoids key concatenation/Pair allocation on every lookup.
    private val segmentCache = ConcurrentHashMap<Path, ConcurrentHashMap<String, Path>>()

    // segment string → its lowercase form. game paths reuse a small vocabulary
    // of directory names, so this prevents repeated lowercase() allocation.
    private val lowercasePool = ConcurrentHashMap<String, String>()

    override fun onPathParameter(path: Path, functionName: String, parameterName: String): Path {
        pathCache[path]?.let { return it }

        val root = path.root ?: return path
        var resolved = root
        for (segment in path.segments) {
            val lower = lowercasePool.computeIfAbsent(segment) { it.lowercase() }
            val parent = resolved
            val children = segmentCache.computeIfAbsent(parent) { ConcurrentHashMap() }
            resolved = children.computeIfAbsent(lower) {
                val exact = parent / segment
                if (delegate.metadataOrNull(exact) != null) {
                    exact
                } else {
                    delegate.listOrNull(parent)
                        ?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                        ?: exact
                }
            }
        }
        pathCache[path] = resolved
        return resolved
    }
}
