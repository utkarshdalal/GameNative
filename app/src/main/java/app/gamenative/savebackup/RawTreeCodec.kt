package app.gamenative.savebackup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.pathString
import timber.log.Timber

/**
 * Raw-tree-layout codec (Requirement 9): reads and writes a plain **game-relative folder tree**
 * with no manifest wrapper (Requirement 9.1), for consumption by other tools (Heroic, ludusavi) or
 * a manual copy on a laptop.
 *
 * ## Stream-only external access, SAF-friendly, testable
 *
 * The external side is SAF and may have **no real filesystem path** (WebDAV/rclone). Per the design
 * (Requirement 7.1, 7.2) all external I/O must go through `DocumentFile` + `ContentResolver`
 * streams and never convert the tree URI to a filesystem path. So this codec does not take a
 * filesystem path for the external side; instead it abstracts the external destination behind a
 * minimal [TreeWriter] (create subdirectories + open per-file output streams) and the external
 * source behind a minimal [TreeReader] (list relative entries + open per-entry input streams).
 *
 * Production wires a `DocumentFile`-backed [TreeWriter]/[TreeReader] (task 10.1 / 7.4 / 13.1); tests
 * supply an in-memory or temp-dir-backed fake. This mirrors how [ArchiveCodec] stays testable via
 * plain stream providers. The container side is always `java.nio.file.Path`.
 *
 * ## Export no-clobber via a fresh timestamped subdirectory (Requirement 13.2)
 *
 * Unlike an Archive export (a single new `.zip` that cannot clobber a prior export), a raw tree is
 * written into a user-selected SAF directory that may already hold a previous export. Writing files
 * directly would risk a partial failure leaving a half-overwritten tree mixed with the old one. So
 * [export] first creates a **fresh timestamped subdirectory** named `<gameName>_saves_<timestamp>/`
 * under the selected external location and writes the whole tree there. A failed run therefore never
 * mutates a previously completed export in place — the "writes a new tree rather than mutating an
 * existing one" guarantee in Error Handling holds for the raw layout just as it does for archives.
 *
 * ## Import atomicity (Requirements 9.3, 9.5, 9.6 — no partial writes on rejection)
 *
 * [import] never mutates a container destination until **every** entry has been validated. It first
 * extracts all source entries into a private staging directory, validating each entry as it is
 * written against the **real** destination:
 *  - **source empty**: if the source contains no files, fail with [ImportException.SourceEmpty] and
 *    leave the destination unchanged (Requirement 9.3).
 *  - **path-escape**: the entry's resolved destination must `startsWith(normalizedRoot)` — the exact
 *    guard from `SteamSaveTransfer` (Requirement 9.5).
 *  - **symlink**: the staged destination must not already be a symbolic link, and writes use
 *    `LinkOption.NOFOLLOW_LINKS` — the exact guard from `SteamSaveTransfer` (Requirement 9.6).
 *
 * Because staging happens under a fresh temp tree, any rejection throws **before** a single byte is
 * written into the real container destination, and the staging tree is deleted. Only after the full
 * tree validates does [import] commit the staged files into their container destinations.
 *
 * ## Rollback-safe commit (Requirements 5.9, 5.10, 11.3, 13.3 — task 10.2)
 *
 * The commit phase is itself transactional via [StagedCommit]. Before overwriting a destination
 * that already holds a regular file, its prior content is snapshotted aside; net-new destinations
 * are recorded. On success the snapshots are discarded. If **any** file operation fails partway
 * through the commit, [StagedCommit] restores the destination tree to its exact pre-import state —
 * overwritten files restored to their prior bytes, net-new files (and net-new empty dirs) removed —
 * then rethrows. Overwrite is by relative path without prompting (Req 5.10 / 11.3). Combined with
 * the validate-before-commit staging above, this gives [RawTreeCodec] an end-to-end no-partial,
 * exact-restore guarantee independent of any higher-level engine wrapper.
 */
object RawTreeCodec {

    /**
     * Writes a game-relative folder tree to the external location using streams only (no filesystem
     * path). A production implementation is backed by `DocumentFile.createDirectory` /
     * `DocumentFile.createFile` + `ContentResolver.openOutputStream`; tests supply a temp-dir or
     * in-memory fake. All paths are forward-slash, relative to the tree root the writer represents.
     */
    interface TreeWriter {
        /**
         * Ensure the directory at [relativePath] (and any missing parents) exists under the tree
         * root, and return a child [TreeWriter] rooted at that directory. [relativePath] uses `/`
         * separators and is relative to this writer's root; an empty value returns a writer for the
         * current root.
         */
        fun createDir(relativePath: String): TreeWriter

        /**
         * Create (or replace) a file named [name] directly under this writer's root and return an
         * [OutputStream] positioned to overwrite it. The caller closes the stream.
         */
        fun createFile(name: String): OutputStream
    }

    /**
     * Reads a game-relative folder tree from the external location using streams only (no filesystem
     * path). A production implementation is backed by `DocumentFile.listFiles` +
     * `ContentResolver.openInputStream`; tests supply a temp-dir or in-memory fake.
     */
    interface TreeReader {
        /**
         * List every regular file entry in the tree, depth-first, as forward-slash relative paths
         * from the tree root. Directories themselves are not listed; only file leaves are returned.
         */
        fun listFiles(): List<Entry>

        /**
         * A single file entry in the source tree.
         *
         * @param relativePath forward-slash path relative to the tree root; never empty, never
         *   leading-separator.
         * @param length the byte size of the entry, used for byte-fidelity verification by callers.
         * @param isSymlink whether the entry is a symbolic link when determinable; when the backing
         *   provider cannot determine this (typical for SAF), implementations report `false` and the
         *   container-side [Files.isSymbolicLink] check on the resolved destination still guards
         *   symlink rejection (Requirement 9.6).
         * @param openInput obtains a fresh [InputStream] for the entry's bytes; the caller closes it.
         */
        data class Entry(
            val relativePath: String,
            val length: Long,
            val isSymlink: Boolean,
            val openInput: () -> InputStream,
        )
    }

    /**
     * A resolved save root on the container side to include in an [export]. Every regular file
     * beneath [absolutePath] is written into the external tree at its path relative to
     * [absolutePath] (Requirement 9.2 — same relative path, same bytes). Symbolic links are skipped.
     */
    data class ExportRoot(
        val absolutePath: Path,
    )

    /**
     * Typed failures an [import] can produce. Callers (the engine in task 10.x) and tests
     * (7.4/13.1) assert on these instead of parsing messages.
     */
    sealed class ImportException(message: String) : IOException(message) {
        /** The source tree contains no files (Requirement 9.3); the destination is left unchanged. */
        class SourceEmpty : ImportException("Raw-tree source is empty")

        /** An entry resolved outside its target save root (Requirement 9.5). [entryName] identifies it. */
        class PathEscape(val entryName: String) :
            ImportException("Raw-tree entry escapes save root: $entryName")

        /** An entry resolved to a symbolic link (Requirement 9.6). [entryName] identifies it. */
        class Symlink(val entryName: String) :
            ImportException("Raw-tree entry is a symlink: $entryName")
    }

    private const val TIMESTAMP_PATTERN = "yyyyMMdd_HHmmss"

    // -- Export ----------------------------------------------------------------

    /**
     * Write a raw game-relative folder tree to the external location (Requirement 9.1, 9.2).
     *
     * A fresh subdirectory named `<gameName>_saves_<timestamp>/` is created under [dest] first, and
     * the whole tree is written there, so a failed run never mutates a prior export in place
     * (Requirement 13.2). For every regular file under each root in [roots], the file's path
     * relative to that root and its exact bytes are reproduced under the timestamped subdirectory.
     * Symbolic links are skipped (only regular files are exported), matching the archive export.
     *
     * When [roots] contain multiple roots that share relative paths, later roots overwrite earlier
     * ones by relative path; callers that need per-root separation should pass a single root.
     *
     * @param dest the external destination tree (e.g. a `DocumentFile`-backed [TreeWriter] for the
     *   user-selected SAF location).
     * @param gameName used to name the fresh timestamped subdirectory; sanitized to a safe folder
     *   name.
     * @param timestampMillis the export time used in the subdirectory name; defaults to now.
     * @return the number of files written (excluding directories).
     */
    fun export(
        roots: List<ExportRoot>,
        gameName: String,
        dest: TreeWriter,
        timestampMillis: Long = System.currentTimeMillis(),
    ): Int {
        val subdirName = exportSubdirName(gameName, timestampMillis)
        // Fresh timestamped subdirectory — a failed run never mutates a prior export (Req 13.2).
        val exportRoot = dest.createDir(subdirName)

        var fileCount = 0
        roots.forEach { root ->
            if (!Files.isDirectory(root.absolutePath)) return@forEach
            Files.walk(root.absolutePath).use { stream ->
                stream
                    .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { file ->
                        val relativePath =
                            normalizeRelativePath(root.absolutePath.relativize(file).pathString)
                        if (relativePath.isEmpty()) return@forEach
                        writeFile(exportRoot, relativePath) { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                        fileCount += 1
                    }
            }
        }
        return fileCount
    }

    /** Create parent directories under [root] for [relativePath] then write the leaf via [write]. */
    private fun writeFile(root: TreeWriter, relativePath: String, write: (OutputStream) -> Unit) {
        val slashIndex = relativePath.lastIndexOf('/')
        val (parentPath, name) =
            if (slashIndex < 0) {
                "" to relativePath
            } else {
                relativePath.substring(0, slashIndex) to relativePath.substring(slashIndex + 1)
            }
        val parent = if (parentPath.isEmpty()) root else root.createDir(parentPath)
        parent.createFile(name).use(write)
    }

    // -- Import ----------------------------------------------------------------

    /**
     * Import a raw game-relative folder tree from [source] into the container [destinationRoot]
     * (Requirement 9.3), preserving each file's relative path and exact bytes.
     *
     * Steps:
     *  1. List the source entries. If there are none, fail with [ImportException.SourceEmpty] and
     *     leave the destination unchanged (Requirement 9.3) — no staging tree is created.
     *  2. Stage + validate every entry into a private staging tree, checking path-escape
     *     (Requirement 9.5) and symlink (Requirement 9.6) against the **real** destination as it
     *     goes. Any rejection throws here, before the commit step, so no container file is created
     *     or modified.
     *  3. Commit via [StagedCommit]: snapshot each pre-existing destination before overwriting it,
     *     then move staged files into place, overwriting by relative path (Req 5.10/11.3). On any
     *     mid-commit failure the destination tree is restored to its exact pre-import state
     *     (overwritten files → prior bytes, net-new → removed — Req 5.9/13.3) and the failure is
     *     rethrown. Only reached when the whole tree validated.
     *
     * On any exception the staging tree is deleted and, for validation rejections (steps 1–2), no
     * container destination has been modified at all; for a commit-phase failure (step 3) the
     * destination is rolled back to its pre-import state. This is the codec's own atomic guarantee.
     *
     * @param source the external source tree (e.g. a `DocumentFile`-backed [TreeReader]).
     * @param destinationRoot the container-absolute directory the tree is imported into.
     * @param stagingDir a directory under which the codec creates its private staging tree; must be
     *   on the same filesystem as [destinationRoot] for an atomic commit. Created if absent.
     * @return the number of files imported.
     */
    fun import(
        source: TreeReader,
        destinationRoot: Path,
        stagingDir: Path,
    ): Int {
        // 1. Empty source → fail cleanly, destination unchanged (Req 9.3). No staging created.
        val entries = source.listFiles()
        if (entries.isEmpty()) throw ImportException.SourceEmpty()

        stagingDir.createDirectories()
        val staging = Files.createTempDirectory(stagingDir, "rawtree-import-")

        val normalizedRoot = destinationRoot.normalize()
        val stagedRoot = staging.normalize()
        // Staged file -> final destination, populated only after each entry validates.
        val committed = mutableListOf<Pair<Path, Path>>()
        try {
            // 2. Stage + validate every entry. No real destination is written yet.
            entries.forEach { entry ->
                stageEntry(entry, normalizedRoot, stagedRoot)?.let { committed += it }
            }

            // 3. Commit — every entry validated; apply staged files as a rollback-safe
            //    transaction that snapshots pre-existing destinations before overwriting, so a
            //    mid-commit failure restores the exact pre-import state (Req 5.9/13.3). Overwrite
            //    is by relative path, without prompting (Req 5.10/11.3). Reached only when the
            //    whole tree passed validation.
            StagedCommit.commit(
                operations = committed.map { (staged, destination) ->
                    StagedCommit.Operation(staged, destination)
                },
                snapshotParent = stagingDir,
            )
            return committed.size
        } finally {
            // Always clean the staging tree, whether we committed or rejected.
            deleteRecursively(staging)
        }
    }

    /**
     * Validate and stage a single source entry.
     *
     * Returns the `(stagedFile, finalDestination)` pair when the entry is valid, or `null` when the
     * entry is not an importable file (blank relative path). Throws the appropriate
     * [ImportException] on a path-escape or symlink rejection — before any real destination is
     * written.
     */
    private fun stageEntry(
        entry: TreeReader.Entry,
        normalizedRoot: Path,
        stagedRoot: Path,
    ): Pair<Path, Path>? {
        val relativePath = normalizeRelativePath(entry.relativePath)
        if (relativePath.isEmpty()) return null

        // Path-escape guard against the REAL destination root (Req 9.5) — same check as the engine.
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw ImportException.PathEscape(entry.relativePath)
        }

        // Symlink guard: reject a source entry reported as a symlink, and reject when the REAL
        // destination OR any ancestor under the root is a symbolic link (Req 9.6, CWE-59): a
        // symlinked parent directory would let Files.move write outside the save root, which a
        // leaf-only isSymbolicLink check misses. Reject before staging any bytes.
        if (entry.isSymlink || SymlinkGuard.hasSymlinkAncestor(normalizedRoot, destination)) {
            throw ImportException.Symlink(entry.relativePath)
        }

        // Stage the bytes under a mirror of the destination inside the staging tree. Nothing is
        // written to the real destination here.
        val staged = stagedRoot.resolve(relativePath).normalize()
        // Defensive: the staged path must also stay under its staged root.
        if (!staged.startsWith(stagedRoot)) {
            throw ImportException.PathEscape(entry.relativePath)
        }
        staged.parent?.createDirectories()
        Files.newByteChannel(
            staged,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            Channels.newOutputStream(channel).use { output ->
                entry.openInput().use { it.copyTo(output) }
            }
        }
        return staged to destination
    }

    // -- Helpers ---------------------------------------------------------------

    /** `<sanitizedGameName>_saves_<timestamp>` — the fresh export subdirectory (Req 13.2). */
    internal fun exportSubdirName(gameName: String, timestampMillis: Long): String {
        val timestamp =
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US).format(Date(timestampMillis))
        return "${sanitizeName(gameName)}_saves_$timestamp"
    }

    /** Reduce a game name to a safe single-segment folder name. */
    private fun sanitizeName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "game" }
    }

    private fun normalizeRelativePath(value: String): String =
        value.replace('\\', '/').trimStart('/').trim()

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        try {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to delete staging path $path")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to clean staging tree $root")
        }
    }
}
