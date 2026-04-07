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
 * Resolved segments are cached for the lifetime of this instance (one download
 * session) so repeated lookups for the same parent+segment are O(1).
 */
class CaseInsensitiveFileSystem(
    delegate: FileSystem = SYSTEM,
) : ForwardingFileSystem(delegate) {

    // (parent, lowercased segment) → resolved child path.
    // keyed by lowercase so all casing variants ("Saves", "saves", "SAVES") hit
    // the same entry. computeIfAbsent is atomic on ConcurrentHashMap, so
    // concurrent threads won't race to create duplicate directories.
    private val cache = ConcurrentHashMap<Pair<Path, String>, Path>()

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
            val key = resolved to segment.lowercase()
            resolved = cache.computeIfAbsent(key) {
                val exact = resolved / segment
                if (delegate.metadataOrNull(exact) != null) {
                    exact
                } else {
                    delegate.listOrNull(resolved)
                        ?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                        ?: exact
                }
            }
        }

        if (!resolveAll) {
            resolved = resolved / segments.last()
        }

        return resolved
    }
}
