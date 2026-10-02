package app.gamenative.savebackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gamenative.data.LibraryItem
import app.gamenative.data.SaveFilePattern
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.FileUtils
import com.winlator.container.Container
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.pathString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The export archive layout the user selected for an [SaveBackupEngine.export]. */
enum class ExportLayout {
    /** A single `.zip` with a `manifest.json` + `files/` tree — best for GameNative round-trips. */
    ARCHIVE,

    /** A plain game-relative folder tree with no manifest — for interop with other tools. */
    RAW_TREE,
}

/**
 * The terminal result of an export or import, returned directly to the caller the moment the
 * operation completes.
 *
 * **Authoritative delivery (Requirement 13.5).** The engine returns this value synchronously from
 * [SaveBackupEngine.export] / [SaveBackupEngine.import]; the UI drives its state from the returned
 * value, not from a snackbar. `SnackbarManager` is a secondary channel that can drop messages, so
 * the 2-second failed/cancelled reporting guarantee must not depend on it — it depends on this
 * return value reaching the caller as soon as the operation ends.
 */
sealed interface BackupResult {
    /** Every save file was copied and verified (Requirements 4.4, 5.5). */
    data object Success : BackupResult

    /**
     * No save files were found at the source, so nothing was copied and the destination is left
     * unchanged (Requirements 4.5, 5.6).
     */
    data object NoSavesFound : BackupResult

    /** The operation failed; [message] describes why (Requirements 13.1–13.3). */
    data class Failed(val message: String) : BackupResult

    /** The operation was cancelled mid-flight; never reported as success or failure (Req 13.4). */
    data object Cancelled : BackupResult
}

/**
 * The source-agnostic core that copies a game's saves out of its Wine container to an external SAF
 * location ([export]) and back ([import]). It replaces the Steam-only `SteamSaveTransfer`.
 *
 * The engine owns only the two endpoints of a transfer: the resolved save location inside the
 * container and the selected external location. It never touches official cloud-sync storage
 * (Requirement 11.2a) and is only ever entered through a direct caller action — there is no
 * scheduler or background trigger here (Requirement 11.1). Picker sequencing, cancellation of the
 * pickers, and container-unresolvable handling are the orchestrator's job (task 11.x); this engine
 * receives already-selected endpoints and performs the transfer, but still fails cleanly if the
 * container cannot be resolved.
 */
interface SaveBackupEngine {
    /**
     * Export the resolved save set at [loc] from [item]'s container to the external tree [dest].
     *
     * @param dest a SAF **tree** URI (from `OpenDocumentTree`); the engine writes a new `.zip`
     *   (ARCHIVE) or a fresh timestamped subdirectory (RAW_TREE) under it via `DocumentFile` +
     *   `ContentResolver` streams — the tree URI is never converted to a filesystem path (Req 7.2).
     * @param layout the archive layout the user chose.
     * @param loc the resolved container-side save location to export.
     * @return the terminal [BackupResult]; [BackupResult.NoSavesFound] when the resolved save set
     *   is empty, in which case no external change is made (Requirement 4.5).
     */
    suspend fun export(
        ctx: Context,
        item: LibraryItem,
        dest: Uri,
        layout: ExportLayout,
        loc: SaveLocation,
    ): BackupResult

    /**
     * Import saves from the external source [source] into [item]'s container at [loc].
     *
     * The layout is **sniffed** from [source]: if the selected source is a single `.zip` document
     * (or the tree contains exactly one top-level `.zip`) it is imported through [ArchiveCodec];
     * otherwise it is treated as a raw folder tree and imported through [RawTreeCodec]. See
     * [detectImportSource] for the rationale.
     *
     * @param source a SAF URI — either a document `.zip` or a tree — read via `ContentResolver`
     *   streams only (Requirement 7.2).
     * @param loc the resolved container-side destination save location.
     * @return the terminal [BackupResult]; [BackupResult.NoSavesFound] when the source contains no
     *   files, in which case the container is left unchanged (Requirement 5.6).
     */
    suspend fun import(
        ctx: Context,
        item: LibraryItem,
        source: Uri,
        loc: SaveLocation,
    ): BackupResult
}

/**
 * Default [SaveBackupEngine] wiring the resolver, layout codecs, and [StreamTransfer] together.
 *
 * ## Container + location resolution
 *
 * Both operations resolve the container via
 * [ContainerUtils.getOrCreateContainer]`(ctx, item.appId)` (source-agnostic — Requirement 6.4) and
 * resolve [loc] to an absolute path via [SaveLocationResolver.resolve]. A resolution failure yields
 * [BackupResult.Failed] so the engine fails cleanly even though the orchestrator is the primary
 * owner of unresolvable-container handling (task 11.1).
 *
 * ## rootId scheme
 *
 * Archive exports use a **single-segment** `rootId` derived from the location's `PathType`
 * (`pathType.name.lowercase()`, e.g. `winsavedgames`, `steamuserdata`). It must stay single-segment
 * because [ArchiveCodec] splits an entry name at the first `/` to separate the `rootId` from the
 * relative path. Import resolves that same single `rootId` back to the destination save location.
 *
 * ## DocumentFile-backed codec wiring
 *
 * - **Archive export**: a destination `.zip` `DocumentFile` is created under the tree
 *   (`DocumentFile.fromTreeUri(...).createFile("application/zip", "<GameName>_saves_<ts>.zip")`) and
 *   its `OutputStream` is opened through `ContentResolver`.
 * - **Raw-tree export**: [DocumentTreeWriter] adapts a tree `DocumentFile` to
 *   [RawTreeCodec.TreeWriter] (`createDirectory` / `createFile` + `ContentResolver.openOutputStream`).
 * - **Archive import**: `openSource` opens the `.zip` document's `InputStream` via `ContentResolver`.
 * - **Raw-tree import**: [DocumentTreeReader] adapts a tree `DocumentFile` to
 *   [RawTreeCodec.TreeReader], listing files recursively with relative paths + lengths and opening
 *   each entry's `InputStream` via `ContentResolver`.
 *
 * ## Staging directory (same filesystem for atomic commit)
 *
 * The codecs commit staged files with `Files.move`, which is only atomic within one filesystem. The
 * engine hands them a staging dir **under the container's own `rootDir`**
 * (`<container.rootDir>/.savebackup_staging`), guaranteeing it is on the same filesystem as the
 * resolved container destinations (which live under that same `rootDir/.wine/drive_c/...`). This is
 * preferred over `ctx.cacheDir`, which is not guaranteed to share a filesystem with the container.
 *
 * ## Deferred to later tasks
 *
 * - **Task 10.2 (staged rollback) — done:** both codecs commit through [StagedCommit], which
 *   snapshots pre-existing destinations before overwriting and, on any mid-commit failure, restores
 *   the exact pre-import state (overwritten files → prior bytes, net-new → removed) before failing.
 *   Overwrite is by relative path without prompting. So import failure paths here leave the
 *   container in exactly its pre-import state (Req 5.9/13.3), not merely free of partial writes.
 * - **Task 10.3 (Steam retirement):** routing Steam export/import through this engine and removing
 *   `SteamSaveTransfer` happens in 10.3.
 */
class DefaultSaveBackupEngine : SaveBackupEngine {

    override suspend fun export(
        ctx: Context,
        item: LibraryItem,
        dest: Uri,
        layout: ExportLayout,
        loc: SaveLocation,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveAbsolute(ctx, item, loc)
                ?: return@withContext BackupResult.Failed(
                    "Could not resolve any save root for ${item.appId}",
                )
            val (_, resolvedRoots) = resolved

            // Build the per-root export set: pattern-matched regular (non-symlink) files under each
            // resolved root (Req 2.7). Roots that contain no matching files are dropped.
            val exportRoots = resolvedRoots.mapNotNull { (root, absPath) ->
                val files = saveFilesUnder(absPath, root.pattern)
                if (files.isEmpty()) null else ResolvedExportRoot(root, absPath, files)
            }
            if (exportRoots.isEmpty()) {
                // Empty set → NoSavesFound with NO external changes (Req 4.5).
                return@withContext BackupResult.NoSavesFound
            }

            val gameName = item.name.ifBlank { item.appId }
            when (layout) {
                ExportLayout.ARCHIVE -> exportArchive(ctx, dest, item, exportRoots, gameName)
                ExportLayout.RAW_TREE -> exportRawTree(ctx, dest, exportRoots, gameName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Export failed for ${item.appId}")
            BackupResult.Failed(e.message ?: "Export failed")
        }
    }

    override suspend fun import(
        ctx: Context,
        item: LibraryItem,
        source: Uri,
        loc: SaveLocation,
    ): BackupResult = withContext(Dispatchers.IO) {
        try {
            val resolved = resolveAbsolute(ctx, item, loc)
                ?: return@withContext BackupResult.Failed(
                    "Could not resolve any save root for ${item.appId}",
                )
            val (container, resolvedRoots) = resolved
            val staging = stagingDir(container)

            when (val detected = detectImportSource(ctx, source)) {
                is ImportSource.Archive -> importArchive(ctx, item, detected.zipUri, resolvedRoots, staging)
                // Raw-tree import has no manifest/rootId, so it restores into the FIRST resolved
                // root (the primary save location) — matching the pre-revision single-destination
                // behaviour for folder imports.
                is ImportSource.RawTree -> importRawTree(ctx, detected.tree, resolvedRoots.first().second, staging)
                ImportSource.Empty -> BackupResult.NoSavesFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Import failed for ${item.appId}")
            BackupResult.Failed(e.message ?: "Import failed")
        }
    }

    // -- Export helpers --------------------------------------------------------

    private suspend fun exportArchive(
        ctx: Context,
        dest: Uri,
        item: LibraryItem,
        exportRoots: List<ResolvedExportRoot>,
        gameName: String,
    ): BackupResult {
        // [dest] is a DOCUMENT URI from CreateDocument (the .zip the user named), not a tree URI.
        // Write to it directly via ContentResolver — never DocumentFile.fromTreeUri, which is only
        // valid for OpenDocumentTree results and misbehaves on a document URI.
        //
        // One manifest root + one files/<rootId>/ subtree per resolved save root (multi-root). The
        // rootId uses the retired engine's multi-segment patternRootId scheme so archives round-trip
        // with the old format and resolve by longest prefix on import (Req 8.1/8.2/8.3).
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = item.gameId,
            gameName = gameName,
            exportedAt = System.currentTimeMillis(),
            roots = exportRoots.map { r ->
                SaveRootManifest(rootId = patternRootId(r.root), path = r.absolutePath.pathString)
            },
        )

        return try {
            ArchiveCodec.export(
                manifest = manifest,
                roots = exportRoots.map { r ->
                    ArchiveCodec.ExportRoot(patternRootId(r.root), r.absolutePath, r.files)
                },
                openDest = {
                    ctx.contentResolver.openOutputStream(dest, "wt")
                        ?: throw java.io.IOException("Could not open destination stream")
                },
            )
            BackupResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Archive export failed")
            // Delete the partially written .zip so a truncated archive is not left behind (a later
            // import could otherwise sniff it as a valid single-archive source).
            runCatching { DocumentFile.fromSingleUri(ctx, dest)?.takeIf { it.isFile }?.delete() }
            BackupResult.Failed(e.message ?: "Archive export failed")
        }
    }

    private suspend fun exportRawTree(
        ctx: Context,
        dest: Uri,
        exportRoots: List<ResolvedExportRoot>,
        gameName: String,
    ): BackupResult {
        val tree = DocumentFile.fromTreeUri(ctx, dest)
            ?: return BackupResult.Failed("Could not open destination folder")

        return try {
            // The codec creates its own fresh `<gameName>_saves_<ts>/` subdir under the tree (Req 13.2).
            // A SINGLE root writes its files directly under that subdir (blank rootId) so the raw
            // tree mirrors the save folder's own layout — best for interop and the common case.
            // Only when a game has MULTIPLE roots that could collide by relative path do we nest
            // each under its own rootId subdirectory.
            val multiRoot = exportRoots.size > 1
            RawTreeCodec.export(
                roots = exportRoots.map { r ->
                    val rootId = if (multiRoot) patternRootId(r.root) else ""
                    RawTreeCodec.ExportRoot(r.absolutePath, rootId, r.files)
                },
                gameName = gameName,
                dest = DocumentTreeWriter(ctx, tree),
            )
            BackupResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Raw-tree export failed")
            BackupResult.Failed(e.message ?: "Raw-tree export failed")
        }
    }

    // -- Import helpers --------------------------------------------------------

    private suspend fun importArchive(
        ctx: Context,
        item: LibraryItem,
        zipUri: Uri,
        resolvedRoots: List<Pair<SaveRoot, Path>>,
        staging: Path,
    ): BackupResult {
        val openSource = {
            ctx.contentResolver.openInputStream(zipUri)
                ?: throw java.io.IOException("Could not open source stream")
        }
        return try {
            // Reject an archive exported for a DIFFERENT game before touching the container: it
            // would otherwise overwrite this game's saves with another game's data. Only reject a
            // clear mismatch (both ids known and different) so back-compat archives with an unknown
            // id still import.
            val manifest = ArchiveCodec.readManifest(openSource)
            if (manifest.gameId != 0 && item.gameId != 0 && manifest.gameId != item.gameId) {
                return BackupResult.Failed(
                    "This archive is for game ${manifest.gameId} (${manifest.gameName}), " +
                        "not ${item.name.ifBlank { item.appId }}.",
                )
            }

            // Build the destination map: each resolved save root keyed by its patternRootId. The
            // codec matches each archive rootId to a destination by LONGEST PREFIX (Req 8.3/8.3a),
            // so multi-segment rootIds written by the retired engine resolve, and distinct archive
            // roots map to distinct destinations rather than collapsing onto one folder.
            val destinationsByRootId: Map<String, Path> =
                resolvedRoots.associate { (root, absPath) -> patternRootId(root) to absPath }

            // Back-compat fallback: if the archive has exactly one root and we have exactly one
            // destination, map any rootId to it (older archives may record a rootId that no longer
            // matches the current PathType-derived scheme).
            val singleFallback = resolvedRoots.singleOrNull()?.second

            ArchiveCodec.import(
                openSource = openSource,
                resolveRoot = { rootId ->
                    longestPrefixMatch(rootId, destinationsByRootId) ?: singleFallback
                },
                stagingDir = staging,
            )
            BackupResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Archive import failed")
            BackupResult.Failed(e.message ?: "Archive import failed")
        }
    }

    /**
     * Resolve an archive [rootId] to a destination by longest-prefix match against the recorded
     * [destinations] (Req 8.3/8.3a). An exact match wins; otherwise the destination whose key is
     * the longest prefix of [rootId] (on a `/` boundary) is used. Ports #1935's slash-tolerant
     * matching. Returns null if no key matches.
     */
    private fun longestPrefixMatch(rootId: String, destinations: Map<String, Path>): Path? {
        destinations[rootId]?.let { return it }
        return destinations.entries
            .filter { (key, _) -> rootId == key || rootId.startsWith("$key/") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    private suspend fun importRawTree(
        ctx: Context,
        tree: DocumentFile,
        savePath: Path,
        staging: Path,
    ): BackupResult {
        return try {
            RawTreeCodec.import(
                source = DocumentTreeReader(ctx, tree),
                destinationRoot = savePath,
                stagingDir = staging,
            )
            BackupResult.Success
        } catch (e: RawTreeCodec.ImportException.SourceEmpty) {
            // Empty source → NoSavesFound, container unchanged (Req 5.6, 9.3).
            BackupResult.NoSavesFound
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Raw-tree import failed")
            BackupResult.Failed(e.message ?: "Raw-tree import failed")
        }
    }

    // -- Resolution + enumeration ---------------------------------------------

    /**
     * Resolve [item]'s container and the absolute path of [loc] within it, or `null` when either
     * the container cannot be obtained or the location cannot be resolved (Req 1.7, 6.5).
     */
    private fun resolveAbsolute(
        ctx: Context,
        item: LibraryItem,
        loc: SaveLocation,
    ): Pair<Container, List<Pair<SaveRoot, Path>>>? {
        val container = try {
            ContainerUtils.getOrCreateContainer(ctx, item.appId)
        } catch (e: Exception) {
            Timber.w(e, "Could not resolve container for ${item.appId}")
            return null
        }
        return when (val result = SaveLocationResolver.resolve(container, item.gameId, loc)) {
            is SaveLocationResult.Resolved -> container to result.resolvedRoots
            is SaveLocationResult.Unresolved -> {
                Timber.w("Save location unresolved for ${item.appId}: ${result.reason}")
                null
            }
            SaveLocationResult.Unset -> null
        }
    }

    /**
     * The save files under [root]: regular (non-directory, non-symlink) files, filtered by
     * [pattern] when present (Req 2.7). A null [pattern] (e.g. a browser-confirmed root) selects
     * every regular file under the root (whole subtree). Empty when the root is absent.
     */
    private fun saveFilesUnder(root: Path, pattern: SaveFilePattern?): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (pattern == null) {
            return Files.walk(root).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .collect(java.util.stream.Collectors.toList())
            }
        }
        val depth = if (pattern.recursive > 0) pattern.recursive else 5
        return FileUtils.findFilesRecursive(
            rootPath = root,
            pattern = pattern.pattern,
            maxDepth = depth,
        )
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
            .collect(java.util.stream.Collectors.toList())
    }

    /**
     * Detect whether [source] is an archive `.zip` or a raw folder tree.
     *
     * Rationale: export writes either a single `.zip` (ARCHIVE) or a raw folder tree (RAW_TREE), so
     * sniffing the source content is sufficient and keeps the import API free of an extra layout
     * parameter. If [source] is a document whose name ends in `.zip`, or a tree containing exactly
     * one top-level `.zip` and no other files/dirs, it is treated as ARCHIVE; otherwise as a raw
     * tree. A source that resolves to nothing usable is reported as [ImportSource.Empty].
     */
    private fun detectImportSource(ctx: Context, source: Uri): ImportSource {
        // Try as a single document first (the user may have picked a .zip directly).
        val asFile = runCatching { DocumentFile.fromSingleUri(ctx, source) }.getOrNull()
        if (asFile != null && asFile.isFile) {
            return if (asFile.name?.endsWith(".zip", ignoreCase = true) == true) {
                ImportSource.Archive(source)
            } else {
                // A single non-zip file is not a tree we can import as raw; treat as empty.
                ImportSource.Empty
            }
        }

        val tree = runCatching { DocumentFile.fromTreeUri(ctx, source) }.getOrNull()
        if (tree == null || !tree.isDirectory) return ImportSource.Empty

        val children = tree.listFiles()
        val zips = children.filter { it.isFile && it.name?.endsWith(".zip", ignoreCase = true) == true }
        val nonZipEntries = children.filter { it !in zips }
        if (zips.size == 1 && nonZipEntries.isEmpty()) {
            return ImportSource.Archive(zips.first().uri)
        }
        return ImportSource.RawTree(tree)
    }

    private sealed interface ImportSource {
        data class Archive(val zipUri: Uri) : ImportSource
        data class RawTree(val tree: DocumentFile) : ImportSource
        data object Empty : ImportSource
    }

    /** A resolved save root plus the pattern-matched save files to export from it. */
    private data class ResolvedExportRoot(
        val root: SaveRoot,
        val absolutePath: Path,
        val files: List<Path>,
    )

    // -- Misc helpers ----------------------------------------------------------

    /**
     * Multi-segment rootId for [root], matching the retired `SteamSaveTransfer.patternRootId`
     * (`<pathtype>/<normalized-subpath-or-"root">`) so archives round-trip with the old format and
     * resolve by longest prefix on import (Req 8.1/8.3). The subpath is taken from the root's
     * pattern path when present (the UFS-declared path), else from the relative subpath.
     */
    private fun patternRootId(root: SaveRoot): String {
        val type = root.pathType.name.lowercase()
        val rawPath = root.pattern?.path?.takeIf { it.isNotBlank() } ?: root.relativeSubpath
        val normalizedPath = rawPath
            .replace('\\', '/')
            .trim('/')
            .ifBlank { "root" }
            .lowercase()
        return "$type/$normalizedPath"
    }

    /**
     * A staging dir on the SAME filesystem as the container destinations, so the codecs'
     * `Files.move` commit is atomic. Placed under the container's own `rootDir`.
     */
    private fun stagingDir(container: Container): Path =
        java.nio.file.Paths.get(container.rootDir.absolutePath, ".savebackup_staging")

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { "game" }
    }
}

/**
 * A [RawTreeCodec.TreeWriter] backed by a SAF tree [DocumentFile]. Creates subdirectories with
 * `DocumentFile.createDirectory` and files with `createFile` + `ContentResolver.openOutputStream`,
 * never converting the tree URI to a filesystem path (Requirements 7.1, 7.2).
 */
internal class DocumentTreeWriter(
    private val context: Context,
    private val dir: DocumentFile,
) : RawTreeCodec.TreeWriter {

    override fun createDir(relativePath: String): RawTreeCodec.TreeWriter {
        var current = dir
        relativePath.split('/').filter { it.isNotEmpty() && it != "." }.forEach { segment ->
            val existing = current.findFile(segment)
            current = if (existing != null && existing.isDirectory) {
                existing
            } else {
                current.createDirectory(segment)
                    ?: throw java.io.IOException("Could not create directory '$segment'")
            }
        }
        return DocumentTreeWriter(context, current)
    }

    override fun createFile(name: String): OutputStream {
        // Replace any existing file of the same name so a re-export overwrites cleanly.
        dir.findFile(name)?.takeIf { it.isFile }?.delete()
        val file = dir.createFile("application/octet-stream", name)
            ?: throw java.io.IOException("Could not create file '$name'")
        return context.contentResolver.openOutputStream(file.uri, "wt")
            ?: throw java.io.IOException("Could not open output stream for '$name'")
    }
}

/**
 * A [RawTreeCodec.TreeReader] backed by a SAF tree [DocumentFile]. Lists every file leaf
 * recursively as forward-slash relative paths and opens each entry via `ContentResolver`, never
 * converting the tree URI to a filesystem path (Requirements 7.1, 7.2).
 *
 * SAF cannot report symbolic links, so [RawTreeCodec.TreeReader.Entry.isSymlink] is always `false`
 * here; the codec's container-side `Files.isSymbolicLink` guard on the resolved destination still
 * enforces symlink rejection (Requirement 9.6).
 */
internal class DocumentTreeReader(
    private val context: Context,
    private val root: DocumentFile,
) : RawTreeCodec.TreeReader {

    override fun listFiles(): List<RawTreeCodec.TreeReader.Entry> {
        val entries = mutableListOf<RawTreeCodec.TreeReader.Entry>()
        collect(root, "", entries)
        return entries
    }

    private fun collect(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<RawTreeCodec.TreeReader.Entry>,
    ) {
        dir.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                collect(child, relativePath, out)
            } else if (child.isFile) {
                out += RawTreeCodec.TreeReader.Entry(
                    relativePath = relativePath,
                    length = child.length(),
                    isSymlink = false,
                    openInput = {
                        context.contentResolver.openInputStream(child.uri)
                            ?: throw java.io.IOException("Could not open input stream for '$relativePath'")
                    },
                )
            }
        }
    }
}
