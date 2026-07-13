package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

data class ModPlacementConflict(
    val sourcePath: String,
    val targetPath: String,
    val directory: Boolean,
)

data class ModPlacementResult(
    val created: Int,
    val skipped: Int,
    val backedUp: Int,
    val errors: Map<String, String>,
    val manifests: List<ModOverwriteManifest>,
)

data class ModPlannedEntry(
    val installId: String,
    val source: File,
    val target: File,
)

object ModMaterializer {
    private const val COPY_SENTINEL = ".gamenative_mod_install"
    private const val APPLY_FREE_SPACE_RESERVE_BYTES = 512L * 1024L * 1024L

    fun filterUnapprovedConflicts(
        conflicts: List<ModPlacementConflict>,
        manifests: List<ModOverwriteManifest>,
    ): List<ModPlacementConflict> {
        if (conflicts.isEmpty() || manifests.isEmpty()) return conflicts
        val manifestsByTarget = manifests.groupBy { File(it.targetPath).absolutePath }
        return conflicts.filter { conflict ->
            val target = File(conflict.targetPath)
            val matchingManifests = manifestsByTarget[target.absolutePath].orEmpty()
            matchingManifests.none { manifest -> targetMatchesApprovedState(target, manifest) }
        }
    }

    suspend fun scanConflicts(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): List<ModPlacementConflict> = withContext(Dispatchers.IO) {
        buildList {
            recipes.filter { it.enabled }.forEach { recipe ->
                val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)
                plannedEntries(install, recipe, gameRootDir, winePrefix).forEach { entry ->
                    when (mode) {
                        ModPlacementMode.OVERWRITE_COPY -> addAll(overwriteConflicts(entry))
                        else -> {
                            if (entry.target.exists() || Files.isSymbolicLink(entry.target.toPath())) {
                                val alreadyCorrectSymlink = Files.isSymbolicLink(entry.target.toPath()) &&
                                    resolveSymlinkTarget(entry.target)?.canonicalFile == entry.source.canonicalFile
                                if (!alreadyCorrectSymlink) {
                                    add(
                                        ModPlacementConflict(
                                            sourcePath = entry.source.absolutePath,
                                            targetPath = entry.target.absolutePath,
                                            directory = entry.source.isDirectory,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun overwriteConflicts(entry: ModPlannedEntry): List<ModPlacementConflict> {
        if (entry.source.isFile) {
            return if (targetNeedsOverwrite(entry.target, entry.source)) {
                listOf(
                    ModPlacementConflict(
                        sourcePath = entry.source.absolutePath,
                        targetPath = entry.target.absolutePath,
                        directory = false,
                    ),
                )
            } else {
                emptyList()
            }
        }
        if (!entry.source.isDirectory) return emptyList()
        if (Files.isSymbolicLink(entry.target.toPath())) {
            return listOf(
                ModPlacementConflict(
                    sourcePath = entry.source.absolutePath,
                    targetPath = entry.target.absolutePath,
                    directory = true,
                ),
            )
        }
        val sourceRoot = entry.source.canonicalFile
        return entry.source.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { sourceFile ->
                val relative = sourceFile.canonicalFile.relativeToOrNull(sourceRoot)?.path ?: return@mapNotNull null
                val targetFile = safeChildTarget(entry.target, relative)
                if (targetNeedsOverwrite(targetFile, sourceFile)) {
                    ModPlacementConflict(
                        sourcePath = sourceFile.absolutePath,
                        targetPath = targetFile.absolutePath,
                        directory = false,
                    )
                } else {
                    null
                }
            }
            .toList()
    }

    suspend fun apply(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        backupRoot: File,
        allowOverwrite: Boolean,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        var created = 0
        var skipped = 0
        var backedUp = 0
        val errors = linkedMapOf<String, String>()
        val manifests = mutableListOf<ModOverwriteManifest>()
        val targetsWrittenThisApply = mutableSetOf<String>()

        backupRoot.mkdirs()
        recipes.filter { it.enabled }.forEach { recipe ->
            val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)
            val entries = runCatching { plannedEntries(install, recipe, gameRootDir, winePrefix) }
                .getOrElse { e ->
                    errors[recipe.sourceSubpath.ifBlank { recipe.targetRelativePath.ifBlank { install.modName } }] =
                        e.message ?: e::class.simpleName.orEmpty()
                    emptyList()
                }
            entries.forEach { entry ->
                try {
                    when (mode) {
                        ModPlacementMode.SYMLINK -> {
                            val result = ensureSymlink(entry.target, entry.source)
                            if (result) created++ else skipped++
                        }
                        ModPlacementMode.COPY -> {
                            val result = copyWithoutOverwrite(entry.target, entry.source, install.installId)
                            if (result) created++ else skipped++
                        }
                        ModPlacementMode.OVERWRITE_COPY -> {
                            val result = copyWithBackups(
                                install = install,
                                target = entry.target,
                                source = entry.source,
                                backupRoot = backupRoot,
                                allowOverwrite = allowOverwrite,
                                targetsWrittenThisApply = targetsWrittenThisApply,
                            )
                            created += result.created
                            backedUp += result.backedUp
                            manifests += result.manifests
                        }
                    }
                } catch (e: Exception) {
                    errors[entry.target.absolutePath] = "${e::class.simpleName}: ${e.message}"
                }
            }
        }

        ModPlacementResult(created, skipped, backedUp, errors, manifests)
    }

    suspend fun repairMissingTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        var created = 0
        var skipped = 0
        val errors = linkedMapOf<String, String>()

        recipes.filter { it.enabled }.forEach { recipe ->
            val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)
            val entries = runCatching { plannedEntries(install, recipe, gameRootDir, winePrefix) }
                .getOrElse { e ->
                    errors[recipe.sourceSubpath.ifBlank { recipe.targetRelativePath.ifBlank { install.modName } }] =
                        e.message ?: e::class.simpleName.orEmpty()
                    emptyList()
                }
            entries.forEach { entry ->
                try {
                    when (mode) {
                        ModPlacementMode.SYMLINK -> {
                            if (entry.target.exists() || Files.isSymbolicLink(entry.target.toPath())) {
                                skipped++
                            } else if (ensureSymlink(entry.target, entry.source)) {
                                created++
                            } else {
                                skipped++
                            }
                        }
                        ModPlacementMode.COPY,
                        ModPlacementMode.OVERWRITE_COPY -> {
                            val result = copyMissingFiles(entry.target, entry.source)
                            created += result.created
                            skipped += result.skipped
                        }
                    }
                } catch (e: Exception) {
                    errors[entry.target.absolutePath] = "${e::class.simpleName}: ${e.message}"
                }
            }
        }

        ModPlacementResult(created, skipped, backedUp = 0, errors = errors, manifests = emptyList())
    }

    suspend fun restoreBackups(manifests: List<ModOverwriteManifest>): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        val handledTargets = mutableSetOf<String>()
        manifests.sortedByDescending { it.targetPath.length }.forEach { manifest ->
            if (!handledTargets.add(manifest.targetPath)) return@forEach
            val target = File(manifest.targetPath)
            if (manifest.backupPath.isBlank()) return@forEach
            val backup = File(manifest.backupPath)
            if (!backup.isFile) {
                skipped += manifest.targetPath
                return@forEach
            }
            val currentHash = if (target.isFile) sha256(target) else ""
            if (currentHash.isNotBlank() && currentHash != manifest.installedHash) {
                skipped += manifest.targetPath
                return@forEach
            }
            deleteTargetSymlinkIfPresent(target)
            target.parentFile?.mkdirs()
            backup.copyTo(target, overwrite = true)
        }
        skipped
    }

    suspend fun removeAppliedFiles(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        restoredOverwriteTargets: Set<String> = emptySet(),
    ): List<String> = withContext(Dispatchers.IO) {
        val skipped = mutableListOf<String>()
        recipes.filter { it.enabled }.forEach { recipe ->
            val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)
            runCatching {
                plannedEntries(install, recipe, gameRootDir, winePrefix).forEach { entry ->
                    when (mode) {
                        ModPlacementMode.SYMLINK -> removeSymlink(entry.target, entry.source, skipped)
                        ModPlacementMode.COPY -> removeCopiedEntry(
                            target = entry.target,
                            source = entry.source,
                            installId = install.installId,
                            skipped = skipped,
                            allowOwnedDirectoryDelete = true,
                            reportChangedFiles = true,
                        )
                        ModPlacementMode.OVERWRITE_COPY -> removeCopiedEntry(
                            target = entry.target,
                            source = entry.source,
                            installId = install.installId,
                            skipped = skipped,
                            allowOwnedDirectoryDelete = false,
                            reportChangedFiles = true,
                            ignoredChangedTargets = restoredOverwriteTargets,
                            removeLegacySentinel = true,
                        )
                    }
                }
            }.onFailure { skipped += "${recipe.targetRoot}:${recipe.targetRelativePath}" }
        }
        skipped.distinct()
    }

    fun plannedEntries(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): List<ModPlannedEntry> =
        recipes.filter { it.enabled }.flatMap { recipe ->
            plannedEntries(install, recipe, gameRootDir, winePrefix)
        }

    private fun plannedEntries(
        install: ModInstall,
        recipe: ModPlacementRecipe,
        gameRootDir: File?,
        winePrefix: String,
    ): List<ModPlannedEntry> =
        sourceSubpathsForPlacement(recipe.sourceSubpath).flatMap { sourceSubpath ->
            plannedEntriesForSource(install, recipe, sourceSubpath, gameRootDir, winePrefix)
        }

    private fun sourceSubpathsForPlacement(sourceSubpath: String): List<String> =
        ModPlacementSources.decode(sourceSubpath).ifEmpty { listOf("") }

    private fun plannedEntriesForSource(
        install: ModInstall,
        recipe: ModPlacementRecipe,
        sourceSubpath: String,
        gameRootDir: File?,
        winePrefix: String,
    ): List<ModPlannedEntry> {
        val extractedRoot = File(install.extractedPath).canonicalFile
        val normalizedSource = ModPlacementSources.normalize(sourceSubpath)
        val source = resolveSource(extractedRoot, normalizedSource)
        if (source != extractedRoot && !source.path.startsWith(extractedRoot.path + File.separator)) {
            throw IOException("Source path escapes extracted mod directory")
        }
        if (!source.exists()) {
            throw IOException("Source path does not exist: $sourceSubpath")
        }
        val targetDir = ModTargetResolver.resolve(
            targetRoot = recipe.targetRoot,
            targetRelativePath = recipe.targetRelativePath,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
        ) ?: throw IOException("Target root is unavailable: ${recipe.targetRoot}")

        val effectiveSource = stripPrefix(source, recipe.stripPrefixSegments)
        return when {
            effectiveSource.isFile -> listOf(ModPlannedEntry(install.installId, effectiveSource, File(targetDir, effectiveSource.name)))
            recipe.includeSourceDirectory && effectiveSource != extractedRoot ->
                listOf(ModPlannedEntry(install.installId, effectiveSource, File(targetDir, effectiveSource.name)))
            else -> effectiveSource.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.map { ModPlannedEntry(install.installId, it, File(targetDir, it.name)) }
                ?: emptyList()
        }
    }

    private fun resolveSource(extractedRoot: File, normalizedSource: String): File {
        if (normalizedSource.isBlank()) return extractedRoot
        if (normalizedSource.split('/').any { it == ".." }) {
            throw IOException("Source path escapes extracted mod directory")
        }
        val direct = File(extractedRoot, normalizedSource).canonicalFile
        if (direct.exists()) return direct
        resolveCaseInsensitive(extractedRoot, normalizedSource)?.let { return it.canonicalFile }
        extractedRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.forEach { wrapper ->
                resolveCaseInsensitive(wrapper, normalizedSource)?.let { return it.canonicalFile }
            }
        return extractedRoot.walkTopDown()
            .firstOrNull { file ->
                val relative = file.relativeToOrNull(extractedRoot)
                    ?.path
                    ?.replace(File.separatorChar, '/')
                    .orEmpty()
                relative.equals(normalizedSource, ignoreCase = true) ||
                    relative.endsWith("/$normalizedSource", ignoreCase = true)
            }
            ?.canonicalFile
            ?: resolveSingleDirectoryByName(extractedRoot, normalizedSource.substringAfterLast('/'))?.canonicalFile
            ?: direct
    }

    private fun resolveSingleDirectoryByName(root: File, name: String): File? {
        if (!name.equals("Data", ignoreCase = true)) return null
        val matches = root.walkTopDown()
            .filter { it.isDirectory && it.name.equals(name, ignoreCase = true) }
            .take(2)
            .toList()
        return matches.singleOrNull()
    }

    private fun resolveCaseInsensitive(root: File, path: String): File? {
        var current = root
        path.split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = current.listFiles()
                ?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current
    }

    private fun stripPrefix(source: File, segments: Int): File {
        var current = source
        repeat(segments.coerceAtLeast(0)) {
            val children = current.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()
            current = children.singleOrNull { it.isDirectory } ?: current
        }
        return current
    }

    private fun ensureSymlink(target: File, source: File): Boolean {
        target.parentFile?.mkdirs()
        val targetPath = target.toPath()
        if (Files.isSymbolicLink(targetPath)) {
            val current = resolveSymlinkTarget(target)
            if (current?.canonicalFile == source.canonicalFile) return false
            Files.delete(targetPath)
        } else if (target.exists()) {
            return false
        }
        Files.createSymbolicLink(targetPath, source.toPath().toAbsolutePath().normalize())
        return true
    }

    private fun resolveSymlinkTarget(file: File): File? {
        val path = file.toPath()
        val raw = runCatching { Files.readSymbolicLink(path) }.getOrNull() ?: return null
        val resolved = if (raw.isAbsolute) raw else path.parent.resolve(raw)
        return resolved.normalize().toFile()
    }

    private fun removeSymlink(target: File, source: File, skipped: MutableList<String>) {
        val targetPath = target.toPath()
        if (!Files.isSymbolicLink(targetPath)) return
        val current = resolveSymlinkTarget(target)
        if (current?.canonicalFile == source.canonicalFile) {
            Files.deleteIfExists(targetPath)
        } else {
            skipped += target.absolutePath
        }
    }

    private fun removeCopiedEntry(
        target: File,
        source: File,
        installId: String,
        skipped: MutableList<String>,
        allowOwnedDirectoryDelete: Boolean,
        reportChangedFiles: Boolean,
        ignoredChangedTargets: Set<String> = emptySet(),
        removeLegacySentinel: Boolean = false,
    ) {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return
        if (source.isDirectory) {
            val sentinel = File(target, COPY_SENTINEL)
            if (allowOwnedDirectoryDelete && target.isDirectory && sentinel.isFile && sentinel.readText() == installId) {
                target.deleteRecursively()
                return
            }
            if (removeLegacySentinel && sentinel.isFile && sentinel.readText() == installId) {
                sentinel.delete()
            }
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { sourceFile ->
                    val targetFile = File(target, sourceFile.relativeTo(source).path)
                    removeCopiedFileIfUnchanged(targetFile, sourceFile, skipped, reportChangedFiles, ignoredChangedTargets)
                }
            if (allowOwnedDirectoryDelete || removeLegacySentinel) {
                deleteEmptyDirs(target, stopAt = target.parentFile)
            }
        } else {
            removeCopiedFileIfUnchanged(target, source, skipped, reportChangedFiles, ignoredChangedTargets)
        }
    }

    private fun removeCopiedFileIfUnchanged(
        target: File,
        source: File,
        skipped: MutableList<String>,
        reportChangedFiles: Boolean,
        ignoredChangedTargets: Set<String> = emptySet(),
    ) {
        if (!target.exists() || !target.isFile || !source.isFile) return
        if (target.absolutePath in ignoredChangedTargets) return
        if (sha256(target) == sha256(source)) {
            target.delete()
        } else if (reportChangedFiles && target.absolutePath !in ignoredChangedTargets) {
            skipped += target.absolutePath
        }
    }

    private fun deleteEmptyDirs(dir: File, stopAt: File?) {
        if (!dir.isDirectory || dir == stopAt) return
        dir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { deleteEmptyDirs(it, stopAt = dir) }
        if (dir.listFiles()?.isEmpty() == true) {
            dir.delete()
        }
    }

    private fun copyWithoutOverwrite(target: File, source: File, installId: String): Boolean {
        if (target.exists() || Files.isSymbolicLink(target.toPath())) return false
        target.parentFile?.mkdirs()
        copyEntry(source, target, overwrite = false)
        if (target.isDirectory) File(target, COPY_SENTINEL).writeText(installId)
        return true
    }

    private data class CopyBackupResult(
        val created: Int,
        val backedUp: Int,
        val manifests: List<ModOverwriteManifest>,
    )

    private data class CopyMissingResult(
        val created: Int,
        val skipped: Int,
    )

    private fun copyMissingFiles(target: File, source: File): CopyMissingResult {
        var created = 0
        var skipped = 0

        if (source.isDirectory) {
            if (Files.isSymbolicLink(target.toPath())) return CopyMissingResult(created, skipped + 1)
            if (!target.exists()) target.mkdirs()
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(source).path
                    val targetFile = safeChildTarget(target, relative)
                    if (targetFile.exists() || Files.isSymbolicLink(targetFile.toPath())) {
                        skipped++
                        return@forEach
                    }
                    ensureSpaceForCopy(existingSpaceRoot(targetFile), targetFile, file)
                    ensureRealParentDirectories(targetFile, stopAt = target)
                    file.copyTo(targetFile, overwrite = false)
                    created++
                }
            return CopyMissingResult(created, skipped)
        }

        if (!source.isFile) return CopyMissingResult(created, skipped + 1)
        if (target.exists() || Files.isSymbolicLink(target.toPath())) return CopyMissingResult(created, skipped + 1)
        ensureSpaceForCopy(existingSpaceRoot(target), target, source)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = false)
        return CopyMissingResult(created + 1, skipped)
    }

    private fun copyWithBackups(
        install: ModInstall,
        target: File,
        source: File,
        backupRoot: File,
        allowOverwrite: Boolean,
        targetsWrittenThisApply: MutableSet<String>,
    ): CopyBackupResult {
        if (!allowOverwrite && source.isFile && targetNeedsOverwrite(target, source) && target.absolutePath !in targetsWrittenThisApply) {
            throw IOException("Overwrite was not confirmed for ${target.absolutePath}")
        }
        if (!allowOverwrite && source.isDirectory && Files.isSymbolicLink(target.toPath())) {
            throw IOException("Overwrite was not confirmed for ${target.absolutePath}")
        }

        var created = 0
        var backedUp = 0
        val manifests = mutableListOf<ModOverwriteManifest>()

        if (source.isDirectory) {
            deleteTargetSymlinkIfPresent(target)
            source.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(source).path
                    val targetFile = safeChildTarget(target, relative)
                    val sameApplyTarget = targetFile.absolutePath in targetsWrittenThisApply
                    if (!allowOverwrite && !sameApplyTarget && targetNeedsOverwrite(targetFile, file)) {
                        throw IOException("Overwrite was not confirmed for ${targetFile.absolutePath}")
                    }
                    val backup = if (sameApplyTarget) BackupIfNeededResult() else backupIfNeeded(install, targetFile, file, backupRoot)
                    if (backup.manifest != null) {
                        if (backup.backedUp) backedUp++
                        manifests += backup.manifest
                    }
                    if (backup.skipCopy) {
                        return@forEach
                    }
                    ensureSpaceForCopy(existingSpaceRoot(targetFile), targetFile, file)
                    deleteTargetSymlinkIfPresent(targetFile)
                    ensureRealParentDirectories(targetFile, stopAt = target)
                    file.copyTo(targetFile, overwrite = true)
                    targetsWrittenThisApply += targetFile.absolutePath
                    created++
                    manifests.replaceLastForTarget(targetFile, install)
                }
        } else {
            val sameApplyTarget = target.absolutePath in targetsWrittenThisApply
            val backup = if (sameApplyTarget) BackupIfNeededResult() else backupIfNeeded(install, target, source, backupRoot)
            if (backup.manifest != null) {
                if (backup.backedUp) {
                    backedUp++
                }
                manifests += backup.manifest
            }
            if (backup.skipCopy) {
                return CopyBackupResult(created, backedUp, manifests)
            }
            ensureSpaceForCopy(existingSpaceRoot(target), target, source)
            deleteTargetSymlinkIfPresent(target)
            ensureRealParentDirectories(target, stopAt = target.parentFile)
            source.copyTo(target, overwrite = true)
            targetsWrittenThisApply += target.absolutePath
            created++
            manifests.replaceLastForTarget(target, install)
        }
        return CopyBackupResult(created, backedUp, manifests)
    }

    private fun MutableList<ModOverwriteManifest>.replaceLastForTarget(target: File, install: ModInstall) {
        val index = indexOfLast { it.targetPath == target.absolutePath }
        if (index < 0 || !target.isFile) return
        val current = this[index]
        this[index] = current.copy(
            installedHash = sha256(target),
            installedSize = target.length(),
            installedMtime = target.lastModified(),
        )
    }

    private data class BackupIfNeededResult(
        val manifest: ModOverwriteManifest? = null,
        val backedUp: Boolean = false,
        val skipCopy: Boolean = false,
    )

    private fun backupIfNeeded(
        install: ModInstall,
        target: File,
        source: File,
        backupRoot: File,
    ): BackupIfNeededResult {
        if (Files.isSymbolicLink(target.toPath())) return BackupIfNeededResult()
        if (!target.exists() || !target.isFile) return BackupIfNeededResult()
        val targetHash = sha256(target)
        val sourceHash = sha256(source)
        val relative = target.absolutePath
            .replace(':', '_')
            .replace('\\', '/')
            .trimStart('/')
        val backup = File(File(backupRoot, install.installId), relative)
        if (sourceHash.isNotBlank() && targetHash == sourceHash) {
            return BackupIfNeededResult(
                manifest = if (backup.isFile) {
                    overwriteManifest(install, target, backup, sourceHash, source)
                } else if (install.status != ModInstallStatus.APPLIED.name) {
                    protectiveManifest(install, target, sourceHash, source)
                } else {
                    null
                },
                skipCopy = true,
            )
        }
        val backedUp = if (backup.isFile) {
            false
        } else {
            ensureSpace(backupRoot, target.length())
            backup.parentFile?.mkdirs()
            target.copyTo(backup, overwrite = false)
            true
        }
        return BackupIfNeededResult(
            manifest = overwriteManifest(install, target, backup, sourceHash, source),
            backedUp = backedUp,
            skipCopy = false,
        )
    }

    private fun overwriteManifest(
        install: ModInstall,
        target: File,
        backup: File,
        sourceHash: String,
        source: File,
    ): ModOverwriteManifest =
        ModOverwriteManifest(
            installId = install.installId,
            targetPath = target.absolutePath,
            backupPath = backup.absolutePath,
            originalHash = sha256(backup),
            originalSize = backup.length(),
            originalMtime = backup.lastModified(),
            installedHash = sourceHash,
            installedSize = source.length(),
            installedMtime = source.lastModified(),
        )

    private fun protectiveManifest(
        install: ModInstall,
        target: File,
        sourceHash: String,
        source: File,
    ): ModOverwriteManifest =
        ModOverwriteManifest(
            installId = install.installId,
            targetPath = target.absolutePath,
            backupPath = "",
            originalHash = sourceHash,
            originalSize = target.length(),
            originalMtime = target.lastModified(),
            installedHash = sourceHash,
            installedSize = source.length(),
            installedMtime = source.lastModified(),
        )

    private fun ensureSpaceForCopy(spaceRoot: File, target: File, source: File) {
        if (!source.isFile) return
        val extraBytes = when {
            target.isFile -> 0L
            else -> source.length()
        }
        if (extraBytes <= 0L) return
        ensureSpace(spaceRoot, extraBytes)
    }

    private fun ensureSpace(spaceRoot: File, extraBytes: Long) {
        if (spaceRoot.usableSpace < extraBytes + APPLY_FREE_SPACE_RESERVE_BYTES) {
            throw IOException("Storage is too low to safely apply mods. Free more space and retry.")
        }
    }

    private fun existingSpaceRoot(target: File): File =
        generateSequence(target.parentFile ?: target) { it.parentFile }
            .firstOrNull { it.exists() }
            ?: target

    private fun deleteTargetSymlinkIfPresent(target: File) {
        val path = target.toPath()
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
        }
    }

    private fun ensureRealParentDirectories(target: File, stopAt: File?) {
        val parents = generateSequence(target.parentFile) { parent ->
            if (parent == stopAt) null else parent.parentFile
        }.toList().asReversed()
        parents.forEach { dir ->
            val path = dir.toPath()
            if (Files.isSymbolicLink(path)) {
                Files.deleteIfExists(path)
            }
            if (!dir.exists()) {
                dir.mkdir()
            }
        }
    }

    private fun targetNeedsOverwrite(target: File, source: File): Boolean {
        if (!target.exists() && !Files.isSymbolicLink(target.toPath())) return false
        if (Files.isSymbolicLink(target.toPath())) {
            return resolveSymlinkTarget(target)?.canonicalFile != source.canonicalFile
        }
        if (!target.isFile || !source.isFile) return true
        if (target.length() != source.length()) return true
        return sha256(target) != sha256(source)
    }

    private fun safeChildTarget(root: File, relative: String): File {
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath().toAbsolutePath().normalize()
        val targetPath = rootPath.resolve(relative).normalize()
        if (targetPath != rootPath && !targetPath.startsWith(rootPath)) {
            throw IOException("Target path escapes destination directory: $relative")
        }
        return targetPath.toFile()
    }

    private fun copyEntry(source: File, target: File, overwrite: Boolean) {
        if (source.isDirectory) {
            if (!source.copyRecursively(target, overwrite = overwrite)) {
                throw IOException("Failed to copy ${source.absolutePath}")
            }
        } else {
            source.copyTo(target, overwrite = overwrite)
        }
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun targetMatchesApprovedState(target: File, manifest: ModOverwriteManifest): Boolean {
        if (!target.isFile || Files.isSymbolicLink(target.toPath())) return false
        val currentHash = sha256(target)
        return currentHash.isNotBlank() &&
            (currentHash == manifest.installedHash || currentHash == manifest.originalHash)
    }
}
