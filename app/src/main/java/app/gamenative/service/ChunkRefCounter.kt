package app.gamenative.service

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Tracks how many not-yet-assembled game files still need each chunk.
 *
 * Call [trackFile] for every file before assembly begins, passing the IDs of all
 * chunks that file requires.  After a file is fully assembled, call [releaseFile]
 * with the same IDs.  When the reference count for a chunk reaches zero it is
 * deleted from [cacheDir], freeing disk space incrementally rather than keeping
 * the entire cache until all files are done.
 *
 * Thread-safe: Epic assembles up to four files concurrently, so both maps and
 * counters must tolerate concurrent access.
 *
 * @param cacheDir   Directory that holds the on-disk chunk files.
 * @param chunkExtension  Optional suffix appended to the chunk ID when building
 *                        the filename (e.g. ".chunk" for GOG; "" for Epic).
 */
class ChunkRefCounter(
    private val cacheDir: File,
    private val chunkExtension: String = "",
) {
    private val refCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val totalChunks get() = refCounts.size
    private val deletedChunks = AtomicInteger(0)

    // Tracked once in logInitialState; decremented as chunks are deleted.
    // Avoids re-scanning the directory on every deletion (would be O(n²)).
    private var initialCacheBytes = 0L
    private val freedBytes = AtomicLong(0L)

    /**
     * Register that the file about to be assembled needs [chunkIds].
     * Must be called for every file before [releaseFile] is ever called.
     */
    fun trackFile(chunkIds: List<String>) {
        for (id in chunkIds) {
            refCounts.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
        }
    }

    /**
     * Log the starting state once all files have been tracked.
     * Performs a single directory scan to record the initial cache size.
     * Call this after all [trackFile] calls and before the first [releaseFile].
     */
    fun logInitialState() {
        initialCacheBytes = cacheDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        Timber.tag("ChunkRefCounter").i(
            "Assembly starting — tracking $totalChunks unique chunks, " +
                "cache size: ${"%.1f".format(initialCacheBytes / 1_048_576.0)} MB",
        )
    }

    /**
     * Signal that a file has been fully assembled and no longer needs [chunkIds].
     * Chunks whose reference count drops to zero are deleted immediately.
     *
     * Safe to call from multiple coroutines concurrently.
     */
    fun releaseFile(chunkIds: List<String>) {
        val canonicalCache = cacheDir.canonicalPath
        for (id in chunkIds) {
            // Only delete when the count transitions from 1 → 0 (getAndDecrement returns the
            // previous value). This avoids double-deletion if releaseFile is called more
            // times than trackFile for the same id.
            val prev = refCounts[id]?.getAndDecrement() ?: continue
            if (prev != 1) continue

            val chunkFile = File(cacheDir, "$id$chunkExtension")
            // Validate the resolved path stays inside cacheDir to prevent path traversal
            // if a chunk id ever contains ".." or path separators.
            val canonicalChunk = chunkFile.canonicalPath
            if (!canonicalChunk.startsWith(canonicalCache + File.separator) &&
                canonicalChunk != canonicalCache) {
                Timber.tag("ChunkRefCounter").w("Skipping unsafe chunk path: $id")
                continue
            }

            val fileSize = chunkFile.length()
            val deleted = chunkFile.delete()
            if (deleted) {
                freedBytes.addAndGet(fileSize)
                val count = deletedChunks.incrementAndGet()
                if (count % 100 == 0 || count == totalChunks) {
                    val remainingMb = (initialCacheBytes - freedBytes.get()) / 1_048_576.0
                    Timber.tag("ChunkRefCounter").i(
                        "Deleted chunk $count/$totalChunks — " +
                            "cache remaining: ${"%.1f".format(remainingMb)} MB",
                    )
                }
            }
        }
    }

    /** Exposed for testing only. Returns the current reference count for [chunkId], or null if not tracked. */
    @androidx.annotation.VisibleForTesting
    fun getRefCount(chunkId: String): Int? = refCounts[chunkId]?.get()
}
