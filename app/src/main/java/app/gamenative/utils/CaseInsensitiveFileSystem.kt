package app.gamenative.utils

import `in`.dragonbra.javasteam.depotdownloader.BaseCaseInsensitiveFileSystem
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import java.io.File
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
) : BaseCaseInsensitiveFileSystem(delegate) {

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

    /**
     * Resolve [path] to an on-disk [File] through the filesystem.
     * Handles case-insensitive file resolution and caching.
     */
    override fun toResolvedFile(path: Path): File {
        val resolvedPath = onPathParameter(path, "toResolvedFile", "path")
        return resolvedPath.toFile()
    }

    /**
     * Remove cache entries for a completed file path.
     * This cleans up both the full path cache and segment cache entries
     * for the file's parent directory to prevent memory accumulation.
     */
    override fun removeFileCache(path: Path) {
        val segments = path.segments
        if (segments.isEmpty()) return

        val root = path.root ?: return

        // Remove the cache entry for the file itself
        val parentPath = path.parent ?: root
        val fileName = segments.last()
        val fileKey = parentPath to fileName.lowercase()
        cache.remove(fileKey)
    }

    /**
     * Clear all caches. Useful for cleanup when downloads are complete.
     */
    override fun clearAllCaches() {
        cache.clear()
    }
}
