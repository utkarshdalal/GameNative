package app.gamenative.savebackup

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import timber.log.Timber

/**
 * The rollback-safe commit transaction shared by [ArchiveCodec] and [RawTreeCodec] (task 10.2,
 * Requirements 5.9, 5.10, 11.3, 13.3).
 *
 * ## Why this exists
 *
 * Both codecs first **stage + validate** every entry (path-escape / symlink rejection) into a
 * private temp tree, so a *validation* rejection never touches the container (Property 12). But the
 * final **commit** step still mutates the container: it moves each staged file onto its real
 * destination, overwriting any pre-existing file by relative path (Req 5.10 / 11.3 — overwrite
 * without prompting, no merge).
 *
 * A naive commit loop (`Files.move(staged, destination, REPLACE_EXISTING)` per file) is **not**
 * rollback-safe: if the Nth move throws partway through (e.g. an I/O error), the already-committed
 * moves are left in place — net-new files created and, worse, pre-existing files already
 * overwritten with new content. That violates Req 5.9 / 13.3, which require the destination to be
 * left in **exactly** its pre-import state on failure: overwritten files restored to their prior
 * bytes, net-new files removed (not merely deleted — *restored*).
 *
 * [commit] closes that gap by making the apply phase a transaction:
 *
 *  1. **Snapshot** — before overwriting any destination that already holds a regular file, move
 *     that original aside into a snapshot area and record it. Destinations with no pre-existing
 *     regular file are recorded as *net-new*.
 *  2. **Apply** — ensure parent directories exist (recording any directory chain created net-new so
 *     it can be cleaned on rollback) and move the staged file onto the destination.
 *  3. **Discard | Rollback** — on success, delete the snapshot area. On **any** failure during
 *     snapshot or apply, iterate the recorded operations in reverse: delete net-new destinations
 *     that were created, move each snapshot back onto its destination (restoring exact prior bytes),
 *     and remove net-new directories that are now empty. Then rethrow so the caller reports
 *     [BackupResult.Failed].
 *
 * ## Same-filesystem requirement
 *
 * [commit] uses `Files.move` for both the snapshot-aside and the apply steps so the operations are
 * atomic per file and cheap (a rename, not a copy). This requires the staging tree, the snapshot
 * area, and the destinations to all live on the **same filesystem**. The engine guarantees this by
 * rooting the staging dir under the container's own `rootDir`
 * (`<container.rootDir>/.savebackup_staging`), which is the same filesystem as the resolved
 * container destinations under `<container.rootDir>/.wine/drive_c/...`. The snapshot area is created
 * under that same staging root.
 *
 * ## Directory cleanup on rollback
 *
 * Restoring exact pre-import **file** state is the hard requirement (Property 9 checks file content
 * and that no partial files remain). Cleaning empty directories is a best-effort nicety: only
 * directories this transaction created net-new are removed on rollback, and only if they end up
 * empty. Pre-existing directories are never deleted.
 */
internal object StagedCommit {

    /**
     * A single staged file to commit: [staged] is the validated file in the staging tree, and
     * [destination] is the container-absolute path it should be moved onto (overwriting any prior
     * file at that relative path).
     */
    data class Operation(
        val staged: Path,
        val destination: Path,
    )

    /** One recorded destination write, retained so [commit] can undo it on failure. */
    private sealed interface AppliedOp {
        /** [destination] had no pre-existing regular file; on rollback it is deleted. */
        data class NetNew(val destination: Path) : AppliedOp

        /**
         * [destination] held a pre-existing regular file that was moved aside to [snapshot] before
         * being overwritten; on rollback the snapshot is moved back to restore prior bytes.
         */
        data class Overwrote(val destination: Path, val snapshot: Path) : AppliedOp
    }

    /**
     * Apply every [operations] entry to its destination as an all-or-nothing transaction.
     *
     * On success every staged file has been moved onto its destination (net-new created,
     * pre-existing overwritten) and all snapshots discarded. On any failure the destination tree is
     * restored to **exactly** its pre-commit state — overwritten files restored to their prior
     * bytes, net-new files (and net-new empty directories) removed — and the original exception is
     * rethrown so the caller can report failure.
     *
     * @param operations the validated `(staged, destination)` pairs to commit.
     * @param snapshotParent a directory on the SAME filesystem as the destinations under which the
     *   private snapshot area is created (typically the codec's staging tree). Created if absent.
     */
    fun commit(operations: List<Operation>, snapshotParent: Path) {
        if (operations.isEmpty()) return

        snapshotParent.createDirectories()
        val snapshotArea = Files.createTempDirectory(snapshotParent, "commit-snapshot-")

        val applied = ArrayList<AppliedOp>(operations.size)
        // Directories this transaction created net-new, in creation order; cleaned in reverse on
        // rollback (children before parents) if empty.
        val createdDirs = LinkedHashSet<Path>()
        var snapshotIndex = 0

        try {
            operations.forEach { op ->
                val destination = op.destination.normalize()

                // Record any parent directory chain we create net-new so rollback can remove it.
                recordAndCreateParents(destination.parent, createdDirs)

                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) &&
                    Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                ) {
                    // Snapshot the pre-existing file aside before overwriting (Req 13.3).
                    val snapshot = snapshotArea.resolve("snap-${snapshotIndex++}")
                    Files.move(destination, snapshot, StandardCopyOption.REPLACE_EXISTING)
                    applied += AppliedOp.Overwrote(destination, snapshot)
                    Files.move(op.staged, destination, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    // Net-new destination (nothing pre-existed, or a non-regular entry we replace).
                    applied += AppliedOp.NetNew(destination)
                    Files.move(op.staged, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } catch (t: Throwable) {
            rollback(applied, createdDirs)
            deleteRecursively(snapshotArea)
            throw t
        }

        // Success: discard snapshots. Leaving them would be harmless but wastes space.
        deleteRecursively(snapshotArea)
    }

    /**
     * Restore the destination tree to its pre-commit state by undoing [applied] in reverse order,
     * then removing net-new [createdDirs] that are now empty (children before parents).
     */
    private fun rollback(applied: List<AppliedOp>, createdDirs: Set<Path>) {
        applied.asReversed().forEach { op ->
            try {
                when (op) {
                    is AppliedOp.NetNew ->
                        // Nothing pre-existed here — remove what we created.
                        Files.deleteIfExists(op.destination)
                    is AppliedOp.Overwrote ->
                        // Move the original bytes back onto the destination (restore prior content).
                        Files.move(op.snapshot, op.destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } catch (e: Exception) {
                Timber.w(e, "Rollback failed to restore destination for $op")
            }
        }
        // Remove net-new directories that ended up empty, deepest first so parents can go too.
        createdDirs.sortedByDescending { it.nameCount }.forEach { dir ->
            try {
                if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && isEmptyDir(dir)) {
                    Files.deleteIfExists(dir)
                }
            } catch (e: Exception) {
                Timber.w(e, "Rollback failed to remove net-new directory $dir")
            }
        }
    }

    /**
     * Ensure [dir] (and its missing ancestors) exist, recording into [createdDirs] every directory
     * this call actually creates so rollback can remove only net-new dirs (never pre-existing ones).
     */
    private fun recordAndCreateParents(dir: Path?, createdDirs: MutableSet<Path>) {
        if (dir == null) return
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return

        // Walk up to the nearest existing ancestor, then create downward, recording each new dir.
        val toCreate = ArrayList<Path>()
        var current: Path? = dir
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            toCreate.add(current)
            current = current.parent
        }
        toCreate.asReversed().forEach { path ->
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(path)
                createdDirs.add(path.normalize())
            }
        }
    }

    private fun isEmptyDir(dir: Path): Boolean =
        Files.newDirectoryStream(dir).use { !it.iterator().hasNext() }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete snapshot path $path")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clean snapshot area $root")
        }
    }
}
