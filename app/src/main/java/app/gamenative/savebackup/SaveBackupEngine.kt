package app.gamenative.savebackup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.gamenative.data.LibraryItem
import app.gamenative.utils.ContainerUtils
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
                    "Could not resolve save location ${loc.pathType} for ${item.appId}",
                )
            val (_, savePath) = resolved

            // Determine the save set: regular (non-symlink) files under the resolved location.
            val saveFiles = regularFilesUnder(savePath)
            if (saveFiles.isEmpty()) {
                // Empty set → NoSavesFound with NO external changes (Req 4.5).
                return@withContext BackupResult.NoSavesFound
            }

            val gameName = item.name.ifBlank { item.appId }
            when (layout) {
                ExportLayout.ARCHIVE -> exportArchive(ctx, dest, item, loc, savePath, gameName)
                ExportLayout.RAW_TREE -> exportRawTree(ctx, dest, savePath, gameName)
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
                    "Could not resolve save location ${loc.pathType} for ${item.appId}",
                )
            val (container, savePath) = resolved
            val staging = stagingDir(container)

            when (val detected = detectImportSource(ctx, source)) {
                is ImportSource.Archive -> importArchive(ctx, detected.zipUri, loc, savePath, staging)
                is ImportSource.RawTree -> importRawTree(ctx, detected.tree, savePath, staging)
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
        loc: SaveLocation,
        savePath: Path,
        gameName: String,
    ): BackupResult {
        // [dest] is a DOCUMENT URI from CreateDocument (the .zip the user named), not a tree URI.
        // Write to it directly via ContentResolver — never DocumentFile.fromTreeUri, which is only
        // valid for OpenDocumentTree results and misbehaves on a document URI.
        val rootId = rootIdFor(loc)
        val manifest = SaveArchiveManifest(
            version = 5,
            gameId = item.gameId,
            gameName = gameName,
            exportedAt = System.currentTimeMillis(),
            roots = listOf(SaveRoot(rootId = rootId, path = savePath.pathString)),
        )

        return try {
            ArchiveCodec.export(
                manifest = manifest,
                roots = listOf(ArchiveCodec.ExportRoot(rootId, savePath)),
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
        savePath: Path,
        gameName: String,
    ): BackupResult {
        val tree = DocumentFile.fromTreeUri(ctx, dest)
            ?: return BackupResult.Failed("Could not open destination folder")

        return try {
            // The codec creates its own fresh `<gameName>_saves_<ts>/` subdir under the tree (Req 13.2).
            RawTreeCodec.export(
                roots = listOf(RawTreeCodec.ExportRoot(savePath)),
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
        zipUri: Uri,
        loc: SaveLocation,
        savePath: Path,
        staging: Path,
    ): BackupResult {
        val rootId = rootIdFor(loc)
        return try {
            ArchiveCodec.import(
                openSource = {
                    ctx.contentResolver.openInputStream(zipUri)
                        ?: throw java.io.IOException("Could not open source stream")
                },
                // Single-root export: any manifest rootId resolves to this save location. Falling
                // back for the exact rootId keeps back-compat with archives whose recorded rootId
                // differs from the current PathType-derived one.
                resolveRoot = { savePath },
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
    ): Pair<Container, Path>? {
        val container = try {
            ContainerUtils.getOrCreateContainer(ctx, item.appId)
        } catch (e: Exception) {
            Timber.w(e, "Could not resolve container for ${item.appId}")
            return null
        }
        return when (val result = SaveLocationResolver.resolve(container, item.gameId, loc)) {
            is SaveLocationResult.Resolved -> container to result.absolutePath
            is SaveLocationResult.Unresolved -> {
                Timber.w("Save location unresolved for ${item.appId}: ${result.reason}")
                null
            }
            SaveLocationResult.Unset -> null
        }
    }

    /** The set of regular (non-directory, non-symlink) files under [root], or empty when absent. */
    private fun regularFilesUnder(root: Path): List<Path> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .collect(java.util.stream.Collectors.toList())
        }
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

    // -- Misc helpers ----------------------------------------------------------

    /** Single-segment rootId for [loc] (the codec splits at the first `/`). */
    private fun rootIdFor(loc: SaveLocation): String = loc.pathType.name.lowercase()

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
