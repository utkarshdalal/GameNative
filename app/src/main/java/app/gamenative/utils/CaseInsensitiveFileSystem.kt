package app.gamenative.utils

import `in`.dragonbra.javasteam.depotdownloader.BaseCaseInsensitiveFileSystem
import `in`.dragonbra.javasteam.depotdownloader.DepotDownloader
import okio.FileMetadata
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Source
import timber.log.Timber
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
    val showDebugLog: Boolean = false,
    private val chunkStagingRedirect: Path? = null,
) : BaseCaseInsensitiveFileSystem(delegate) {

    // parent → (lowercase segment → resolved child). bounded by directory count.
    private val segmentCache = ConcurrentHashMap<Path, ConcurrentHashMap<String, Path>>()

    // segment string → lowercased form. game paths reuse a small set of names.
    private val lowercasePool = ConcurrentHashMap<String, String>()

    private fun log(message: String) {
        if (showDebugLog) {
            Timber.tag("CaseInsensitiveFileSystem").d(message)
        }
    }

    private companion object {
        val DIRECTORY_OPS = setOf("createDirectory", "createDirectories", "deleteRecursively")
        val CHUNK_DIR_SEGMENTS = listOf(DepotDownloader.CONFIG_DIR, "staging", "chunks")
        const val REDIRECT_MIN_FREE_BYTES = 1L shl 30
    }

    private fun chunkRedirectTarget(path: Path): Path? {
        val redirectRoot = chunkStagingRedirect ?: return null
        val segments = path.segments
        val start = (0..segments.size - CHUNK_DIR_SEGMENTS.size).firstOrNull { i ->
            CHUNK_DIR_SEGMENTS.indices.all { j -> segments[i + j] == CHUNK_DIR_SEGMENTS[j] }
        } ?: return null
        return segments.drop(start + CHUNK_DIR_SEGMENTS.size).fold(redirectRoot) { acc, segment -> acc / segment }
    }

    private fun chunkRedirectForWrite(path: Path): Path? {
        val target = chunkRedirectTarget(path) ?: return null
        return target.takeIf { chunkStagingRedirect!!.toFile().usableSpace > REDIRECT_MIN_FREE_BYTES }
    }

    override fun sink(file: Path, mustCreate: Boolean): Sink {
        if (chunkRedirectTarget(file) != null) {
            val target = chunkRedirectForWrite(file) ?: file
            target.parent?.let { delegate.createDirectories(it) }
            return delegate.sink(target, mustCreate)
        }
        return super.sink(file, mustCreate)
    }

    override fun source(file: Path): Source {
        chunkRedirectTarget(file)?.let { target ->
            if (delegate.metadataOrNull(target) != null) return delegate.source(target)
        }
        return super.source(file)
    }

    override fun metadataOrNull(path: Path): FileMetadata? {
        chunkRedirectTarget(path)?.let { target ->
            delegate.metadataOrNull(target)?.let { return it }
        }
        return super.metadataOrNull(path)
    }

    override fun delete(path: Path, mustExist: Boolean) {
        chunkRedirectTarget(path)?.let { target ->
            if (delegate.metadataOrNull(target) != null) {
                delegate.delete(target, mustExist)
                return
            }
        }
        super.delete(path, mustExist)
    }

    override fun createDirectory(dir: Path, mustCreate: Boolean) {
        if (chunkRedirectTarget(dir) != null) {
            val target = chunkRedirectForWrite(dir) ?: dir
            delegate.createDirectories(target)
            return
        }
        super.createDirectory(dir, mustCreate)
    }

    override fun deleteRecursively(fileOrDirectory: Path, mustExist: Boolean) {
        val target = chunkRedirectTarget(fileOrDirectory)
        if (target != null) {
            if (delegate.metadataOrNull(target) != null) {
                delegate.deleteRecursively(target)
            }
            if (delegate.metadataOrNull(fileOrDirectory) != null) {
                delegate.deleteRecursively(fileOrDirectory)
            }
            return
        }
        super.deleteRecursively(fileOrDirectory, mustExist)
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

        if (resolveAll) {
            if (functionName == "deleteRecursively") {
                // Remove from cache before returning
                removeCacheForPath(resolved)
            }
        } else {
            resolved = resolved / segments.last()
        }

        return resolved
    }

    private fun removeCacheForPath(deletedPath: Path) {
        log("Removing cache entries for deleted path '$deletedPath'")

        // Remove all cache entries that start with the deleted path
        val keysToRemove = mutableListOf<Path>()
        for (cachedParent in segmentCache.keys) {
            if (cachedParent.toString().startsWith(deletedPath.toString())) {
                keysToRemove.add(cachedParent)
            }
        }

        for (key in keysToRemove) {
            segmentCache.remove(key)
        }

        // Also remove the deleted directory from its parent's cache
        val parentPath = deletedPath.parent
        if (parentPath != null) {
            val parentChildren = segmentCache[parentPath]
            if (parentChildren != null) {
                val deletedDirName = deletedPath.name.lowercase()
                parentChildren.remove(deletedDirName)
                log("Removed '$deletedDirName' from parent cache")
            }
        }

        log("Removed ${keysToRemove.size} cache entries for deleted path")
    }

    private fun resolveAndCache(
        parent: Path,
        segment: String,
        lower: String,
        children: ConcurrentHashMap<String, Path>,
    ): Path {
        log("Resolving segment '$segment' in parent '$parent'")

        val exact = parent / segment
        if (delegate.metadataOrNull(exact) != null) {
            log("Found exact match for '$segment', caching")
            return children.putIfAbsent(lower, exact) ?: exact
        }

        log("Case mismatch for '$segment', listing directory '$parent'")
        // case mismatch — list directory once, pre-populate directory siblings only
        val listing = delegate.listOrNull(parent)
        if (listing != null) {
            log("Found ${listing.size} entries in '$parent'")
            var directoriesCached = 0
            var filesSkipped = 0

            for (entry in listing) {
                // Only cache directories, not files
                val metadata = delegate.metadataOrNull(entry)
                if (metadata?.isDirectory == true) {
                    val entryLower = lowercasePool.computeIfAbsent(entry.name) { it.lowercase() }
                    children.putIfAbsent(entryLower, entry)
                    directoriesCached++
                } else {
                    filesSkipped++
                }
            }

            log("Cached $directoriesCached directories, skipped $filesSkipped files")
        } else {
            log("Could not list directory '$parent'")
        }

        val result = children[lower] ?: (children.putIfAbsent(lower, exact) ?: exact)
        log("Resolved '$segment' to '$result'")
        return result
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
