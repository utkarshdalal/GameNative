package app.gamenative.savebackup

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.pathString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Archive-layout codec (Requirement 8): reads and writes a single `.zip` containing exactly one
 * `manifest.json` entry plus a `files/<rootId>/<relative-path-under-root>` tree (schema version 5,
 * matching the retired [app.gamenative.ui.util.SteamSaveTransfer] format).
 *
 * ## Stream-oriented, SAF-friendly, testable
 *
 * The external side is SAF (`DocumentFile`/`ContentResolver` streams, Requirement 7.x) and has no
 * real filesystem path. So the codec is designed around **streams**: [export] writes the whole zip
 * to an [OutputStream] the caller obtains from the destination `DocumentFile`; [import] reads the
 * zip from an [InputStream] the caller obtains from the source `DocumentFile`. The container side
 * is `java.nio.file.Path`. Neither method touches a `ContentResolver`, so both can be exercised
 * with plain in-memory streams and temp directories in tests (tasks 7.4/7.5).
 *
 * ## rootId scheme compatibility
 *
 * Entries are laid out under `files/<rootId>/<relpath>` where `rootId` is exactly the identifier
 * recorded in [SaveArchiveManifest.roots] (as produced by the existing engine, e.g.
 * `winsavedgames/root` or `steamuserdata`). Version-5 archives — including those written with the
 * old `steamAppId` manifest field — are therefore layout-compatible, and the manifest itself
 * decodes through [SaveArchiveManifest]'s `@JsonNames("steamAppId")` alias.
 *
 * ## Atomicity (Requirements 8.5, 8.6, 8.7 — no partial writes on rejection)
 *
 * [import] never mutates a container destination until **every** entry has been validated. It
 * first extracts all `files/` entries into a private staging directory, validating each entry as it
 * is written:
 *  - **path-escape**: the entry's resolved destination (under its target root) must
 *    `startsWith(normalizedRoot)` — the exact guard from `SteamSaveTransfer` (Requirement 8.5).
 *  - **symlink**: the staged destination must not already be a symbolic link, and writes use
 *    `LinkOption.NOFOLLOW_LINKS` — the exact guard from `SteamSaveTransfer` (Requirement 8.6).
 *
 * Because staging happens under a fresh temp tree that mirrors each target root, any rejection
 * throws **before** a single byte is written into the real container destinations, and the staging
 * tree is deleted. Only after the full archive validates does [import] commit the staged files into
 * their container destinations. A missing or unparseable manifest fails immediately, before any
 * `files/` entry is read (Requirement 8.7).
 *
 * ## Rollback-safe commit (Requirements 5.9, 5.10, 11.3, 13.3 — task 10.2)
 *
 * The commit phase is itself transactional via [StagedCommit]. Before overwriting a destination
 * that already holds a regular file, its prior content is snapshotted aside; net-new destinations
 * are recorded. On success the snapshots are discarded. If **any** file operation fails partway
 * through the commit, [StagedCommit] restores the destination tree to its exact pre-import state —
 * overwritten files restored to their prior bytes, net-new files (and net-new empty dirs) removed —
 * then rethrows. Overwrite is by relative path without prompting (Req 5.10 / 11.3). Combined with
 * the validate-before-commit staging above, this gives [ArchiveCodec] an end-to-end no-partial,
 * exact-restore guarantee independent of any higher-level engine wrapper.
 */
object ArchiveCodec {

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"

    /** Reader/writer `Json`: tolerant of unknown keys and honoring `@JsonNames` aliases. */
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // useAlternativeNames defaults to true; stated explicitly so @JsonNames("steamAppId") works.
        useAlternativeNames = true
        encodeDefaults = false
        explicitNulls = false
    }

    /**
     * A resolved save root on the container side to include in an [export].
     *
     * @param rootId the identifier written into the manifest and used as the `files/<rootId>/`
     *   directory name (must match the engine's rootId scheme so archives round-trip).
     * @param absolutePath the container-absolute directory the root resolved to; every regular file
     *   beneath it is written into the archive at its path relative to [absolutePath].
     */
    data class ExportRoot(
        val rootId: String,
        val absolutePath: Path,
    )

    /**
     * Typed failures an [import] can produce. Callers (the engine in task 10.x) and tests
     * (7.4/7.5) assert on these instead of parsing messages.
     */
    sealed class ImportException(message: String) : IOException(message) {
        /** The manifest is absent from the archive or could not be parsed (Requirement 8.7). */
        class InvalidManifest(message: String) : ImportException(message)

        /** An entry resolved outside its target root (Requirement 8.5). [entryName] identifies it. */
        class PathEscape(val entryName: String) :
            ImportException("Archive entry escapes save root: $entryName")

        /** An entry resolved to a symbolic link (Requirement 8.6). [entryName] identifies it. */
        class Symlink(val entryName: String) :
            ImportException("Archive entry is a symlink: $entryName")

        /** A manifest root referenced by an entry has no resolved container destination. */
        class UnknownRoot(val rootId: String) :
            ImportException("Archive save root not available: $rootId")
    }

    // -- Export ----------------------------------------------------------------

    /**
     * Write a single archive `.zip` to [openDest]: one `manifest.json` entry followed by the
     * `files/<rootId>/<relpath>` tree for every regular file under each root in [roots]
     * (Requirement 8.1). The [manifest] is written verbatim (its `roots` should describe [roots]).
     *
     * Symbolic links under a root are skipped (only regular files are archived), matching the
     * export behavior of the retired engine.
     *
     * @param openDest obtains the destination zip [OutputStream] (e.g. from a `DocumentFile`); it
     *   is closed by this method.
     * @return the number of file entries written (excluding the manifest).
     */
    fun export(
        manifest: SaveArchiveManifest,
        roots: List<ExportRoot>,
        openDest: () -> OutputStream,
    ): Int {
        var fileCount = 0
        openDest().buffered().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(json.encodeToString(SaveArchiveManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()

                roots.forEach { root ->
                    if (!Files.isDirectory(root.absolutePath)) return@forEach
                    Files.walk(root.absolutePath).use { stream ->
                        stream
                            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                            .forEach { file ->
                                val relativePath =
                                    normalizeRelativePath(root.absolutePath.relativize(file).pathString)
                                if (relativePath.isEmpty()) return@forEach
                                zip.putNextEntry(ZipEntry("$FILES_PREFIX${root.rootId}/$relativePath"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                                fileCount += 1
                            }
                    }
                }
            }
        }
        return fileCount
    }

    // -- Import ----------------------------------------------------------------

    /**
     * Read the manifest from an archive [InputStream] without importing anything. Useful for the
     * engine to resolve roots before committing (task 10.x) and for validating an archive.
     *
     * @throws ImportException.InvalidManifest if the manifest is missing or cannot be parsed
     *   (Requirement 8.7).
     */
    fun readManifest(openSource: () -> InputStream): SaveArchiveManifest {
        openSource().buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    try {
                        if (!entry.isDirectory && entry.name == MANIFEST_ENTRY) {
                            val bytes = zip.readBytes()
                            return try {
                                json.decodeFromString(
                                    SaveArchiveManifest.serializer(),
                                    bytes.decodeToString(),
                                )
                            } catch (e: Exception) {
                                throw ImportException.InvalidManifest(
                                    "Save archive manifest could not be parsed: ${e.message}",
                                )
                            }
                        }
                    } finally {
                        zip.closeEntry()
                    }
                }
            }
        }
        throw ImportException.InvalidManifest("Missing save archive manifest")
    }

    /**
     * Import an archive [InputStream] into the container.
     *
     * Steps:
     *  1. Read the manifest (fails cleanly with [ImportException.InvalidManifest] if missing or
     *     unparseable — Requirement 8.7 — before any `files/` entry is touched).
     *  2. Ask the caller to resolve each manifest root's `rootId` to a container destination
     *     directory via [resolveRoot] (Requirement 8.3). A `null` for a `rootId` referenced by an
     *     entry raises [ImportException.UnknownRoot].
     *  3. Extract every `files/<rootId>/<relpath>` entry into a private staging tree, validating
     *     path-escape (Requirement 8.5) and symlink (Requirement 8.6) against the **real**
     *     destination as it goes. Any rejection throws here, before the commit step, so no
     *     container file is created or modified.
     *  4. Commit via [StagedCommit]: snapshot each pre-existing destination before overwriting it,
     *     then move staged files into place, overwriting by relative path (Req 5.10/11.3). On any
     *     mid-commit failure the destination tree is restored to its exact pre-import state
     *     (overwritten files → prior bytes, net-new → removed — Req 5.9/13.3) and the failure is
     *     rethrown. Only reached when the whole archive validated.
     *
     * On any exception the staging tree is deleted and, for validation rejections (steps 1–3), no
     * container destination has been modified at all; for a commit-phase failure (step 4) the
     * destination is rolled back to its pre-import state. This is the codec's own atomic guarantee.
     *
     * @param openSource obtains a fresh archive [InputStream]. Called at most twice (once to read
     *   the manifest, once to stream entries); each returned stream is closed by this method.
     * @param resolveRoot maps a manifest `rootId` to the container-absolute destination directory
     *   for that root, or `null` if it cannot be resolved.
     * @param stagingDir a directory under which the codec creates its private staging tree; must be
     *   on the same filesystem as the destinations for an atomic commit. Typically a temp dir the
     *   caller supplies. Created if absent.
     * @return the number of files imported.
     */
    fun import(
        openSource: () -> InputStream,
        resolveRoot: (rootId: String) -> Path?,
        stagingDir: Path,
    ): Int {
        // 1. Manifest first — fail cleanly and write nothing on a bad/missing manifest (Req 8.7).
        val manifest = readManifest(openSource)

        // 2. Resolve declared roots to destinations (Req 8.3). Only entries whose rootId resolves
        //    are importable; an entry referencing an unresolved root fails in step 3.
        val destinationRoots: Map<String, Path> = manifest.roots.associate { root ->
            root.rootId to (
                resolveRoot(root.rootId) ?: run {
                    // A declared root with no destination is recorded as absent; if an entry uses it,
                    // step 3 raises UnknownRoot. A declared-but-unused missing root is harmless.
                    null
                }
                )
        }.filterValues { it != null }.mapValues { it.value!! }

        stagingDir.createDirectories()
        val staging = Files.createTempDirectory(stagingDir, "archive-import-")

        // Staged file -> final destination, populated only after each entry validates.
        val committed = mutableListOf<Pair<Path, Path>>()
        try {
            // 3. Stage + validate every entry. No real destination is written yet.
            openSource().buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        try {
                            if (entry.isDirectory ||
                                entry.name == MANIFEST_ENTRY ||
                                !entry.name.startsWith(FILES_PREFIX)
                            ) {
                                continue
                            }
                            stageEntry(zip, entry.name, destinationRoots, staging)
                                ?.let { committed += it }
                        } finally {
                            zip.closeEntry()
                        }
                    }
                }
            }

            // 4. Commit — every entry validated; apply staged files as a rollback-safe
            //    transaction that snapshots pre-existing destinations before overwriting, so a
            //    mid-commit failure restores the exact pre-import state (Req 5.9/13.3). Overwrite
            //    is by relative path, without prompting (Req 5.10/11.3). Reached only when the
            //    whole archive passed validation.
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
     * Validate and stage a single `files/<rootId>/<relpath>` entry.
     *
     * Returns the `(stagedFile, finalDestination)` pair when the entry is valid, or `null` when the
     * entry is not an importable file (blank relative path). Throws the appropriate
     * [ImportException] on a path-escape, symlink, or unknown-root rejection — before any real
     * destination is written.
     */
    private fun stageEntry(
        zip: ZipInputStream,
        entryName: String,
        destinationRoots: Map<String, Path>,
        staging: Path,
    ): Pair<Path, Path>? {
        val relativeEntry = entryName.removePrefix(FILES_PREFIX).replace('\\', '/')
        val slashIndex = relativeEntry.indexOf('/')
        if (slashIndex <= 0) return null

        val rootId = relativeEntry.substring(0, slashIndex)
        val relativePath = normalizeRelativePath(relativeEntry.substring(slashIndex + 1))
        if (relativePath.isEmpty()) return null

        val destinationRoot = destinationRoots[rootId]
            ?: throw ImportException.UnknownRoot(rootId)

        // Path-escape guard against the REAL destination root (Req 8.5) — same check as the engine.
        val normalizedRoot = destinationRoot.normalize()
        val destination = normalizedRoot.resolve(relativePath).normalize()
        if (!destination.startsWith(normalizedRoot)) {
            throw ImportException.PathEscape(entryName)
        }

        // Symlink guard against the REAL destination AND every ancestor under the root (Req 8.6,
        // CWE-59): a symlinked parent directory would let Files.move write outside the save root,
        // which a leaf-only isSymbolicLink check misses. Reject before staging any bytes.
        if (SymlinkGuard.hasSymlinkAncestor(normalizedRoot, destination)) {
            throw ImportException.Symlink(entryName)
        }

        // Stage the bytes under a mirror of the destination inside the staging tree. Nothing is
        // written to the real destination here.
        val stagedRoot = staging.resolve(rootId)
        val staged = stagedRoot.resolve(relativePath).normalize()
        // Defensive: the staged path must also stay under its staged root.
        if (!staged.startsWith(stagedRoot.normalize())) {
            throw ImportException.PathEscape(entryName)
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
                zip.copyTo(output)
            }
        }
        return staged to destination
    }

    // -- Helpers ---------------------------------------------------------------

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
