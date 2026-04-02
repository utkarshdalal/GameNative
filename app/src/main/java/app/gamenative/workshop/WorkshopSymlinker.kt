package app.gamenative.workshop

import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Applies a [WorkshopModPathStrategy] by linking or copying Workshop items
 * into every location the game expects.
 *
 * Stale-entry cleanup policy:
 *   - A SYMLINK is removed only if it points into workshopContentBase
 *     (i.e. we created it, and it's stale).
 *   - A DIRECTORY is removed only if it contains a [COPY_SENTINEL]
 *     sentinel file that we wrote when we copied it.
 *   - Anything else is left completely alone.
 */
class WorkshopSymlinker {

    companion object {
        private const val TAG = "WorkshopSymlinker"
        private const val COPY_SENTINEL = ".gamenative_workshop"

        /** Directory names that always expect one subdirectory per mod item. */
        private val MOD_CONTAINER_NAMES get() = WorkshopModPathDetector.HIGH_CONFIDENCE_NAMES
    }

    data class SyncResult(
        val created: Int,
        val skipped: Int,
        val removed: Int,
        val errors: Map<String, String>,
    ) {
        val hasErrors get() = errors.isNotEmpty()
    }

    fun sync(
        strategy: WorkshopModPathStrategy,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        itemTitles: Map<Long, String> = emptyMap(),
    ): SyncResult {
        return when (strategy) {
            is WorkshopModPathStrategy.Standard -> {
                Timber.tag(TAG).d("Strategy is Standard — no filesystem manipulation needed")
                SyncResult(0, activeItemDirs.size, 0, emptyMap())
            }
            is WorkshopModPathStrategy.SymlinkIntoDir ->
                syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, true, itemTitles)
            is WorkshopModPathStrategy.CopyIntoDir ->
                syncIntoAllDirs(strategy.effectiveDirs, activeItemDirs, workshopContentBase, false, itemTitles)
        }
    }


    // ─────────────────────────────────────────────────────────────────────────

    private fun syncIntoAllDirs(
        targetDirs: List<File>,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String> = emptyMap(),
    ): SyncResult {
        var totalCreated = 0; var totalSkipped = 0; var totalRemoved = 0
        val allErrors = mutableMapOf<String, String>()

        for (dir in targetDirs) {
            val r = syncIntoOneDir(dir, activeItemDirs, workshopContentBase, useSymlinks, itemTitles)
            totalCreated += r.created; totalSkipped += r.skipped; totalRemoved += r.removed
            r.errors.forEach { (k, v) -> allErrors["${dir.name}/$k"] = v }
        }

        Timber.tag(TAG).i(
            "sync complete (${targetDirs.size} dir(s)): created=$totalCreated " +
                "skipped=$totalSkipped removed=$totalRemoved errors=${allErrors.size}"
        )
        return SyncResult(totalCreated, totalSkipped, totalRemoved, allErrors)
    }

    private fun syncIntoOneDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
        useSymlinks: Boolean,
        itemTitles: Map<Long, String> = emptyMap(),
    ): SyncResult {
        Timber.tag(TAG).i(
            "syncIntoOneDir: ${targetDir.absolutePath} " +
                "(${if (useSymlinks) "symlink" else "copy"}, ${activeItemDirs.size} items)"
        )

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            Timber.tag(TAG).e("Failed to create target dir: ${targetDir.absolutePath}")
            val errors = activeItemDirs.keys.associate {
                it.toString() to "Could not create ${targetDir.absolutePath}"
            }
            return SyncResult(0, 0, 0, errors)
        }

        // Detect if all workshop items are "flat single-file" — they each
        // contain exactly one non-hidden file and no subdirectories. These
        // items should be placed as individual file symlinks in the target
        // dir, not as directory symlinks. This handles games like Stronghold
        // Legends (maps/*.slm) where the game expects loose files. Items
        // with multiple files (like Generals ZH maps: .map+.tga+.png+.ini)
        // need directory symlinks so each map stays in its own subdirectory.
        //
        // Exception: directories whose name is a HIGH_CONFIDENCE mod-container
        // name (mods/, addons/, plugins/ etc.) expect one subdirectory per mod
        // even for single-file items. E.g. Prison Architect wants
        // mods/<modTitle>/file.mod.zip, not mods/file.mod.zip.
        if (useSymlinks) {
            val isModContainerDir = targetDir.name.lowercase() in MOD_CONTAINER_NAMES
            val allSingleFile = !isModContainerDir && activeItemDirs.values.all { srcDir ->
                val children = srcDir.listFiles()
                    ?.filter { !it.name.startsWith(".") }
                    ?: emptyList()
                children.size == 1 && children.single().isFile
            }
            if (allSingleFile) {
                return syncFlatFilesIntoDir(
                    targetDir, activeItemDirs, workshopContentBase,
                )
            }
        }

        val errors = mutableMapOf<String, String>()
        var created = 0; var skipped = 0; var removed = 0

        // Build map of expected entry names (titled or numeric ID),
        // ensuring globally unique names even with secondary collisions.
        // Sort keys so the lowest ID consistently gets the unsuffixed base name.
        val usedNames = mutableSetOf<String>()
        val entryNameForId = mutableMapOf<Long, String>()
        for (id in activeItemDirs.keys.sorted()) {
            val title = itemTitles[id]
            val baseName = if (!title.isNullOrBlank()) sanitizeFileName(title) else id.toString()
            var candidate = baseName
            if (candidate in usedNames) candidate = "${baseName}_$id"
            var counter = 1
            while (candidate in usedNames) {
                candidate = "${baseName}_${id}_$counter"
                counter++
            }
            entryNameForId[id] = candidate
            usedNames.add(candidate)
        }
        val expectedNames = entryNameForId.values.toSet()

        // Remove stale entries that we created (safe cleanup)
        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedNames) return@forEach // active item — leave it

            val ours = when {
                Files.isSymbolicLink(entry.toPath()) -> isOurSymlink(entry, workshopContentBase)
                entry.isDirectory -> hasCopySentinel(entry)
                else -> false
            }

            if (ours) {
                if (deleteEntry(entry)) {
                    Timber.tag(TAG).i("  Removed stale (ours): ${entry.name}")
                    removed++
                } else {
                    Timber.tag(TAG).w("  Could not remove stale: ${entry.name}")
                }
            }
        }

        // Create / verify each active item
        for ((itemId, sourceDir) in activeItemDirs) {
            val entryName = entryNameForId[itemId] ?: itemId.toString()
            val entryPath = File(targetDir, entryName)

            if (!sourceDir.isDirectory) {
                val msg = "Source dir missing: ${sourceDir.absolutePath}"
                Timber.tag(TAG).w("  item $itemId: $msg")
                errors[itemId.toString()] = msg
                continue
            }

            try {
                val result = if (useSymlinks) ensureSymlink(entryPath, sourceDir, workshopContentBase)
                else ensureCopy(entryPath, sourceDir, workshopContentBase)
                if (result == LinkResult.CREATED) created++ else skipped++
            } catch (e: Exception) {
                val msg = "${e::class.simpleName}: ${e.message}"
                Timber.tag(TAG).e("  item $itemId: $msg")
                errors[itemId.toString()] = msg
            }
        }

        Timber.tag(TAG).d(
            "  ${targetDir.name}: created=$created skipped=$skipped " +
                "removed=$removed errors=${errors.size}"
        )
        return SyncResult(created, skipped, removed, errors)
    }

    // ── Flat-file placement ─────────────────────────────────────────────────

    /**
     * Places individual files from flat workshop items as file-level symlinks
     * in the target directory. Used when workshop items contain only loose
     * files (no subdirectories), e.g. .slm map files or .upk packages.
     *
     * Cleanup: stale file symlinks pointing into [workshopContentBase] are
     * removed. Real files in the target directory are never touched.
     */
    private fun syncFlatFilesIntoDir(
        targetDir: File,
        activeItemDirs: Map<Long, File>,
        workshopContentBase: File,
    ): SyncResult {
        Timber.tag(TAG).i(
            "syncFlatFilesIntoDir: ${targetDir.absolutePath} (${activeItemDirs.size} items)"
        )
        val errors = mutableMapOf<String, String>()
        var created = 0; var skipped = 0; var removed = 0

        // Collect all files from active workshop items.
        // Sort by item ID so the lowest ID consistently wins on filename collisions.
        val expectedFiles = linkedMapOf<String, File>() // filename → source file
        for ((id, sourceDir) in activeItemDirs.entries.sortedBy { it.key }) {
            sourceDir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.forEach { f ->
                    if (f.name in expectedFiles) {
                        Timber.tag(TAG).w(
                            "Flat-file collision: '%s' from item %d skipped, already provided by %s",
                            f.name, id, expectedFiles[f.name]?.parentFile?.name ?: "unknown"
                        )
                    } else {
                        expectedFiles[f.name] = f
                    }
                }
        }

        // Remove stale file symlinks that we created (point into workshopContentBase)
        targetDir.listFiles()?.forEach { entry ->
            if (entry.name in expectedFiles) return@forEach
            if (Files.isSymbolicLink(entry.toPath()) && isOurSymlink(entry, workshopContentBase)) {
                if (deleteEntry(entry)) {
                    Timber.tag(TAG).i("  Removed stale flat file (ours): ${entry.name}")
                    removed++
                }
            }
        }

        // Also clean up any stale directory symlinks from a previous non-flat run
        targetDir.listFiles()?.forEach { entry ->
            if (entry.isDirectory || Files.isSymbolicLink(entry.toPath())) {
                if (Files.isSymbolicLink(entry.toPath()) && isOurSymlink(entry, workshopContentBase)
                    && entry.name !in expectedFiles
                ) {
                    if (deleteEntry(entry)) {
                        Timber.tag(TAG).i("  Removed stale dir symlink (ours): ${entry.name}")
                        removed++
                    }
                }
            }
        }

        // Create file symlinks
        for ((fileName, srcFile) in expectedFiles) {
            val link = File(targetDir, fileName)
            try {
                val linkPath = link.toPath()
                val targetPath = srcFile.toPath().toAbsolutePath().normalize()

                if (Files.isSymbolicLink(linkPath)) {
                    val currentTarget = resolveSymlinkTarget(linkPath)
                    val resolvedTargetPath = resolvePath(targetPath) ?: targetPath
                    if (currentTarget == resolvedTargetPath) {
                        skipped++
                        continue
                    }
                    Files.delete(linkPath)
                } else if (Files.exists(linkPath)) {
                    // Real file exists — don't overwrite game content
                    skipped++
                    continue
                }

                Files.createSymbolicLink(linkPath, targetPath)
                created++
            } catch (e: Exception) {
                errors[fileName] = "${e::class.simpleName}: ${e.message}"
            }
        }

        Timber.tag(TAG).d(
            "  ${targetDir.name} (flat): created=$created skipped=$skipped " +
                "removed=$removed errors=${errors.size}"
        )
        return SyncResult(created, skipped, removed, errors)
    }

    // ── Ownership checks ─────────────────────────────────────────────────────

    /**
     * Resolves a [Path] to its real (symlink-resolved) location, falling back
     * to normalize().toAbsolutePath() when the target no longer exists on disk.
     */
    private fun resolvePath(path: Path): Path? =
        runCatching { path.toRealPath() }.getOrElse {
            runCatching { path.normalize().toAbsolutePath() }.getOrNull()
        }

    /** Reads a symlink and resolves its target to a real path. */
    private fun resolveSymlinkTarget(symlink: Path): Path? {
        val raw = runCatching { Files.readSymbolicLink(symlink) }.getOrNull() ?: return null
        val resolved = if (raw.isAbsolute) raw else symlink.parent.resolve(raw)
        return resolvePath(resolved)
    }

    private fun isOurSymlink(symlink: File, workshopContentBase: File): Boolean {
        val target = resolveSymlinkTarget(symlink.toPath()) ?: return false
        val base = resolvePath(workshopContentBase.toPath()) ?: return false
        return target.startsWith(base)
    }

    private fun hasCopySentinel(dir: File): Boolean = File(dir, COPY_SENTINEL).exists()

    // ── Symlink management ───────────────────────────────────────────────────

    private enum class LinkResult { CREATED, ALREADY_OK }

    private fun ensureSymlink(link: File, target: File, workshopContentBase: File): LinkResult {
        val linkPath = link.toPath()
        val targetPath = target.toPath().toAbsolutePath().normalize()

        if (Files.isSymbolicLink(linkPath)) {
            // Compare using real (symlink-resolved) paths so that
            // xuser/.wine/... matches xuser-STEAM_*/.wine/...
            val currentTarget = resolveSymlinkTarget(linkPath)
            val resolvedTargetPath = resolvePath(targetPath) ?: targetPath

            if (currentTarget == resolvedTargetPath) {
                return LinkResult.ALREADY_OK
            }
            // Only replace symlinks we created (pointing into workshopContentBase)
            if (!isOurSymlink(link, workshopContentBase)) {
                Timber.tag(TAG).w("  Skipping ${link.name}: foreign symlink (not ours)")
                return LinkResult.ALREADY_OK
            }
            Timber.tag(TAG).d("  Stale symlink ${link.name}: $currentTarget -> $targetPath, recreating")
            Files.delete(linkPath)
        } else if (link.exists()) {
            Timber.tag(TAG).w("  Skipping ${link.name}: real dir exists (manually placed mod?)")
            return LinkResult.ALREADY_OK
        }

        Timber.tag(TAG).i("  Creating symlink: ${link.name} -> ${target.absolutePath}")
        Files.createSymbolicLink(linkPath, targetPath)
        return LinkResult.CREATED
    }

    // ── Copy management ──────────────────────────────────────────────────────

    private data class FileEntry(val path: String, val size: Long, val mtime: Long)

    private fun fingerprint(dir: File): Set<FileEntry> {
        val base = dir.absolutePath
        return dir.walkTopDown()
            .filter { it.isFile && it.name != COPY_SENTINEL }
            .mapTo(HashSet()) { f ->
                val rel = f.absolutePath.removePrefix(base).trimStart(File.separatorChar)
                FileEntry(rel, f.length(), f.lastModified())
            }
    }

    private fun ensureCopy(dest: File, source: File, workshopContentBase: File): LinkResult {
        if (Files.isSymbolicLink(dest.toPath())) {
            // Only remove symlinks we created
            if (!isOurSymlink(dest, workshopContentBase)) {
                Timber.tag(TAG).w("  Skipping ${dest.name}: foreign symlink (not ours)")
                return LinkResult.ALREADY_OK
            }
            Files.delete(dest.toPath())
        } else if (dest.isDirectory) {
            if (!hasCopySentinel(dest)) {
                Timber.tag(TAG).w("  Skipping ${dest.name}: foreign directory (no sentinel)")
                return LinkResult.ALREADY_OK
            }
            if (fingerprint(dest) == fingerprint(source)) {
                return LinkResult.ALREADY_OK
            }
            Timber.tag(TAG).d("  Fingerprint mismatch ${dest.name} — re-copying")
            dest.deleteRecursively()
        }

        Timber.tag(TAG).i("  Copying: ${source.name} -> ${dest.absolutePath}")
        if (!source.copyRecursively(dest, overwrite = true))
            throw IOException("copyRecursively returned false for ${source.name}")

        File(dest, COPY_SENTINEL).createNewFile()
        return LinkResult.CREATED
    }

    private fun deleteEntry(entry: File): Boolean = try {
        val path: Path = entry.toPath()
        if (Files.isSymbolicLink(path)) { Files.delete(path); true }
        else entry.deleteRecursively()
    } catch (e: Exception) {
        Timber.tag(TAG).w("deleteEntry failed for ${entry.name}: ${e.message}")
        false
    }

    /**
     * Sanitizes a string for use as a directory name by removing characters
     * that are illegal on Windows/ext4 and trimming trailing dots/spaces.
     */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { "unnamed" }
    }
}
