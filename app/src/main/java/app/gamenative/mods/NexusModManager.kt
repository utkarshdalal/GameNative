package app.gamenative.mods

import android.content.Context
import app.gamenative.NetworkMonitor
import app.gamenative.PrefManager
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModOverwriteManifest
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import app.gamenative.db.dao.ModDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

data class ModImportProgress(
    val status: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

class ModImportPausedException(message: String) : IOException(message)

class ModImportCanceledException(message: String) : IOException(message)

data class ModStoragePreflight(
    val estimatedRequiredBytes: Long,
    val availableBytes: Long,
) {
    val canImport: Boolean
        get() = availableBytes >= estimatedRequiredBytes
}

data class ModCleanupResult(
    val reclaimedBytes: Long,
)

data class ModStorageBreakdown(
    val cleanableBytes: Long,
    val failedArchiveBytes: Long,
    val extractedCacheBytes: Long,
    val backupBytes: Long,
    val redundantBackupBytes: Long = 0L,
    val redundantBackupCount: Int = 0,
)

enum class ModHealthSeverity {
    ERROR,
    WARNING,
}

data class ModHealthIssue(
    val severity: ModHealthSeverity,
    val title: String,
    val detail: String,
    val installName: String = "",
)

data class ModHealthReport(
    val issues: List<ModHealthIssue>,
) {
    val errorCount: Int get() = issues.count { it.severity == ModHealthSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == ModHealthSeverity.WARNING }
}

object NexusModManager {
    private const val KEEP_IMPORTED_ARCHIVES = false
    private const val DOWNLOAD_BUFFER_SIZE = 256 * 1024
    private const val UNKNOWN_IMPORT_BYTES = 512L * 1024L * 1024L
    private const val ESTIMATED_EXTRACTED_MULTIPLIER = 2L

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ModDaoEntryPoint {
        fun modDao(): ModDao
    }

    private val downloadClient = OkHttpClient()

    fun dao(context: Context): ModDao =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ModDaoEntryPoint::class.java,
        ).modDao()

    fun cacheRoot(context: Context, appId: String): File =
        File(context.filesDir, "mods/$appId/nexus")

    fun backupRoot(context: Context, appId: String): File =
        File(context.filesDir, "mods/$appId/backups")

    fun installIdFor(appId: String, gameDomain: String, modId: Long, fileId: Long): String =
        installId(appId, gameDomain, modId, fileId)

    fun estimateImportScratchBytes(files: List<NexusModFile>): Long =
        files.fold(0L) { total, file ->
            val estimate = estimateImportScratchBytes(file.sizeBytes)
            if (Long.MAX_VALUE - total < estimate) Long.MAX_VALUE else total + estimate
        }

    fun estimateSequentialImportScratchBytes(files: List<NexusModFile>): Long {
        if (files.isEmpty()) return 0L
        val archiveBytes = files.map { it.sizeBytes.takeIf { size -> size > 0L } ?: UNKNOWN_IMPORT_BYTES }
        val retainedExtractedBytes = archiveBytes.fold(0L) { total, size ->
            val extracted = estimatedExtractedBytes(size)
            if (Long.MAX_VALUE - total < extracted) Long.MAX_VALUE else total + extracted
        }
        val largestActiveImportBytes = archiveBytes.maxOrNull()?.let { size ->
            val extracted = estimatedExtractedBytes(size)
            if (Long.MAX_VALUE - size < extracted) Long.MAX_VALUE else size + extracted
        } ?: 0L
        return if (Long.MAX_VALUE - retainedExtractedBytes < largestActiveImportBytes) {
            Long.MAX_VALUE
        } else {
            retainedExtractedBytes + largestActiveImportBytes
        }
    }

    suspend fun checkImportStorage(
        context: Context,
        appId: String,
        files: List<NexusModFile>,
        sequential: Boolean = false,
    ): ModStoragePreflight = withContext(Dispatchers.IO) {
        val root = cacheRoot(context, appId).apply { mkdirs() }
        val estimatedBytes = if (sequential) {
            estimateSequentialImportScratchBytes(files)
        } else {
            estimateImportScratchBytes(files)
        }
        ModStoragePreflight(
            estimatedRequiredBytes = estimatedBytes,
            availableBytes = root.usableSpace,
        )
    }

    suspend fun importNexusFile(
        context: Context,
        appId: String,
        reference: NexusModReference,
        modInfo: NexusModInfo,
        file: NexusModFile,
        apiClient: NexusApiClient = NexusApiClient(),
        onDetailedProgress: (ModImportProgress) -> Unit = {},
    ): ModInstall = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installId = installId(appId, reference.gameDomain, reference.modId, file.fileId)
        val root = cacheRoot(context, appId)
        val archiveDir = File(root, "archives")
        val extractDir = File(root, "extracted/$installId")
        val tempExtractDir = File(root, "extracted/$installId.tmp")
        archiveDir.mkdirs()

        val archiveFile = File(archiveDir, sanitizeFileName("${installId}_${file.fileName}"))
        val tempArchiveFile = File(archiveDir, "${archiveFile.name}.part")
        val previousInstall = dao.getInstall(installId)
        val restorablePreviousInstall = NexusImportState.restorablePreviousInstall(previousInstall)
        val importing = ModInstall(
            installId = installId,
            appId = appId,
            nexusGameDomain = reference.gameDomain,
            nexusModId = reference.modId,
            nexusFileId = file.fileId,
            modName = modInfo.name,
            fileName = file.fileName,
            version = file.version.ifBlank { modInfo.version },
            sizeBytes = file.sizeBytes,
            archivePath = archiveFile.absolutePath,
            extractedPath = extractDir.absolutePath,
            enabled = restorablePreviousInstall?.enabled ?: true,
            status = ModInstallStatus.IMPORTING.name,
            downloadedAt = 0L,
            metadataJson = NexusImportState.importMetadata(modInfo.summary, restorablePreviousInstall),
        )
        dao.upsertInstall(importing)
        suspend fun recordTerminalImport(status: ModInstallStatus, message: String) {
            dao.upsertInstall(
                NexusImportState.terminalInstall(
                    importing = importing,
                    summary = modInfo.summary,
                    status = status,
                    message = message,
                    previousInstall = restorablePreviousInstall,
                ),
            )
        }

        ModDownloadRegistry.start(installId, appId, modInfo.name)
        var downloadCompleted = false
        var extractionCompleted = false
        try {
            ensureDownloadNetworkAllowed()
            val links = apiClient.getDownloadLinks(reference.gameDomain, reference.modId, file.fileId)
            val downloadUrl = links.firstOrNull()?.uri ?: throw IOException("Nexus did not return a download link")
            download(installId, downloadUrl, tempArchiveFile, file.sizeBytes) {
                ModDownloadRegistry.update(
                    installId = installId,
                    progress = it.progress,
                    status = it.status,
                    downloadedBytes = it.downloadedBytes,
                    totalBytes = it.totalBytes,
                )
                onDetailedProgress(it)
            }
            downloadCompleted = true
            val unpacking = ModImportProgress("Unpacking", progress = 0f)
            ModDownloadRegistry.update(installId, 0f, unpacking.status)
            onDetailedProgress(unpacking)
            val extraction = ModArchiveExtractor.extract(
                archiveFile = tempArchiveFile,
                destination = tempExtractDir,
                preservedSingleFileName = file.fileName,
            ) { extractProgress ->
                if (ModDownloadRegistry.isCancelRequested(installId)) {
                    throw ModImportCanceledException("Import canceled")
                }
                val unpackProgress = when {
                    extractProgress.totalBytes > 0L ->
                        (extractProgress.extractedBytes.toFloat() / extractProgress.totalBytes.toFloat()).coerceIn(0f, 1f)
                    extractProgress.totalEntries > 0 ->
                        (extractProgress.entriesProcessed.toFloat() / extractProgress.totalEntries.toFloat()).coerceIn(0f, 1f)
                    else -> 0f
                }
                val detail = ModImportProgress(
                    status = "Unpacking",
                    progress = unpackProgress,
                    downloadedBytes = extractProgress.extractedBytes,
                    totalBytes = extractProgress.totalBytes,
                )
                ModDownloadRegistry.update(
                    installId = installId,
                    progress = detail.progress,
                    status = detail.status,
                    downloadedBytes = detail.downloadedBytes,
                    totalBytes = detail.totalBytes,
                )
                onDetailedProgress(detail)
            }
            extractionCompleted = true
            if (extractDir.exists()) extractDir.deleteRecursively()
            moveFileOrDirectory(extraction.destination, extractDir)
            val storedArchivePath = if (KEEP_IMPORTED_ARCHIVES) {
                archiveFile.delete()
                moveFileOrDirectory(tempArchiveFile, archiveFile)
                archiveFile.absolutePath
            } else {
                archiveFile.delete()
                ""
            }
            val ready = importing.copy(
                archivePath = storedArchivePath,
                extractedPath = extractDir.absolutePath,
                status = ModInstallStatus.READY.name,
                downloadedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                metadataJson = NexusImportState.importMetadata(modInfo.summary),
            )
            dao.upsertInstall(ready)
            if (!KEEP_IMPORTED_ARCHIVES) {
                tempArchiveFile.delete()
            }
            val profile = ModProfileManager.ensureActiveProfile(dao, appId)
            ModProfileManager.ensureStateForInstall(dao, profile, ready.installId)
            ready
        } catch (e: OutOfMemoryError) {
            tempExtractDir.deleteRecursively()
            val failure = IOException(
                "Archive needs more memory than Android allows. Retry after updating GameNative or choose a smaller file.",
                e,
            )
            recordTerminalImport(ModInstallStatus.ERROR, NexusImportState.userMessage(failure))
            throw failure
        } catch (e: ModImportPausedException) {
            tempExtractDir.deleteRecursively()
            recordTerminalImport(ModInstallStatus.PAUSED, e.message ?: "Import paused")
            throw e
        } catch (e: ModImportCanceledException) {
            tempExtractDir.deleteRecursively()
            tempArchiveFile.delete()
            recordTerminalImport(ModInstallStatus.CANCELED, e.message ?: "Import canceled")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (downloadCompleted && !extractionCompleted && shouldRedownloadArchiveAfterExtractionFailure(e)) {
                tempArchiveFile.delete()
            }
            tempExtractDir.deleteRecursively()
            val message = NexusImportState.userMessage(e)
            recordTerminalImport(ModInstallStatus.ERROR, message)
            throw IOException(message, e)
        } finally {
            ModDownloadRegistry.finish(installId)
        }
    }

    suspend fun applyInstall(
        context: Context,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
        allowOverwrite: Boolean,
        saveLastPlacement: Boolean = true,
        preserveStatusOnError: Boolean = false,
    ): ModPlacementResult = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val existingBackupPaths = dao.getOverwriteManifests(install.installId)
            .mapNotNull { it.backupPath.takeIf(String::isNotBlank) }
            .toSet()
        val result = ModMaterializer.apply(
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            backupRoot = backupRoot(context, install.appId),
            allowOverwrite = allowOverwrite,
        )
        if (result.errors.isEmpty()) {
            if (result.manifests.isNotEmpty()) {
                dao.replaceOverwriteManifestsForTargets(install.installId, result.manifests)
            }
            if (install.status != ModInstallStatus.APPLIED.name) {
                dao.updateInstallStatus(install.installId, ModInstallStatus.APPLIED.name)
            }
            if (saveLastPlacement) saveLastPlacementForApp(install.appId, recipes)
        } else if (!preserveStatusOnError && install.status != ModInstallStatus.ERROR.name) {
            dao.updateInstallStatus(install.installId, ModInstallStatus.ERROR.name)
        }
        if (result.errors.isEmpty()) return@withContext result

        val restoreSkipped = ModMaterializer.restoreBackups(result.manifests)
        val restoredTargets = result.manifests
            .map { it.targetPath }
            .filterNot { it in restoreSkipped }
            .toSet()
        val removeSkipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            restoredOverwriteTargets = restoredTargets,
        )
        result.manifests
            .filter { it.targetPath in restoredTargets }
            .mapNotNull { it.backupPath.takeIf(String::isNotBlank) }
            .filterNot { it in existingBackupPaths }
            .distinct()
            .forEach { deleteFileBytes(File(it)) }
        val rollbackSkipped = (restoreSkipped + removeSkipped).distinct()
        if (rollbackSkipped.isEmpty()) {
            result
        } else {
            result.copy(
                errors = result.errors + ("Rollback" to "${rollbackSkipped.size} changed file(s) left in place after failed apply"),
            )
        }
    }

    suspend fun repairMissingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): ModPlacementResult =
        ModMaterializer.repairMissingTargets(
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
        )

    suspend fun cleanupBeforeRecipeReplacement(
        context: Context,
        install: ModInstall,
        oldRecipes: List<ModPlacementRecipe>,
        newRecipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): List<String> = withContext(Dispatchers.IO) {
        if (oldRecipes.isEmpty() || samePlacementRecipes(oldRecipes, newRecipes)) return@withContext emptyList()
        val dao = dao(context)
        val manifests = dao.getOverwriteManifests(install.installId)
        val restoreSkipped = ModMaterializer.restoreBackups(manifests)
        val restoredTargets = manifests
            .map { it.targetPath }
            .filterNot { it in restoreSkipped }
            .toSet()
        val removeSkipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = oldRecipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            restoredOverwriteTargets = restoredTargets,
        )
        if (restoredTargets.isNotEmpty()) {
            dao.deleteOverwriteManifestsForTargets(install.installId, restoredTargets.toList())
        }
        restoreSkipped + removeSkipped
    }

    fun lastPlacementRecipesForApp(appId: String, installId: String): List<ModPlacementRecipe> {
        val root = runCatching { JSONObject(PrefManager.nexusLastPlacementJson) }.getOrElse { JSONObject() }
        val recipes = root.optJSONArray(appId) ?: return emptyList()
        return buildList {
            for (index in 0 until recipes.length()) {
                val recipe = recipes.optJSONObject(index) ?: continue
                add(
                    ModPlacementRecipe(
                        installId = installId,
                        sourceSubpath = recipe.optString("sourceSubpath"),
                        targetRoot = recipe.optString("targetRoot", ModTargetRoot.GAME_DIR.name),
                        targetRelativePath = recipe.optString("targetRelativePath"),
                        mode = recipe.optString("mode", ModPlacementMode.SYMLINK.name),
                        stripPrefixSegments = recipe.optInt("stripPrefixSegments", 0),
                        includeSourceDirectory = recipe.optBoolean("includeSourceDirectory", false),
                        enabled = recipe.optBoolean("enabled", true),
                    ),
                )
            }
        }
    }

    fun saveLastPlacementForApp(appId: String, recipes: List<ModPlacementRecipe>) {
        val enabledRecipes = recipes.filter { it.enabled }
        val root = runCatching { JSONObject(PrefManager.nexusLastPlacementJson) }.getOrElse { JSONObject() }
        if (enabledRecipes.isEmpty()) {
            root.remove(appId)
            PrefManager.nexusLastPlacementJson = root.toString()
            return
        }
        val savedRecipes = JSONArray()
        enabledRecipes.forEach { recipe ->
            savedRecipes.put(
                JSONObject()
                    .put("sourceSubpath", recipe.sourceSubpath)
                    .put("targetRoot", recipe.targetRoot)
                    .put("targetRelativePath", recipe.targetRelativePath)
                    .put("mode", recipe.mode)
                    .put("stripPrefixSegments", recipe.stripPrefixSegments)
                    .put("includeSourceDirectory", recipe.includeSourceDirectory)
                    .put("enabled", recipe.enabled),
            )
        }
        root.put(appId, savedRecipes)
        PrefManager.nexusLastPlacementJson = root.toString()
    }

    private fun samePlacementRecipes(
        first: List<ModPlacementRecipe>,
        second: List<ModPlacementRecipe>,
    ): Boolean =
        first.map(::recipeKey).sorted() == second.map(::recipeKey).sorted()

    private fun recipeKey(recipe: ModPlacementRecipe): String =
        listOf(
            recipe.sourceSubpath,
            recipe.targetRoot,
            normalizedRecipeTarget(recipe).lowercase(),
            recipe.mode,
            recipe.stripPrefixSegments.toString(),
            recipe.includeSourceDirectory.toString(),
            recipe.enabled.toString(),
        ).joinToString("|")

    private fun normalizedRecipeTarget(recipe: ModPlacementRecipe): String =
        if (recipe.targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
            recipe.targetRelativePath.trim().replace('\\', '/')
        } else {
            ModTargetResolver.normalizeRelativePath(recipe.targetRelativePath)
        }

    suspend fun disableInstall(
        context: Context,
        install: ModInstall,
        restoreBackups: Boolean,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, install.appId),
    ): List<String> = withContext(Dispatchers.IO) {
        val dao = dao(context)
        if (install.status != ModInstallStatus.APPLIED.name) {
            dao.updateInstallEnabled(install.installId, false, ModInstallStatus.DISABLED.name)
            return@withContext emptyList()
        }
        val recipes = dao.getRecipesForInstall(install.installId)
        val manifests = dao.getOverwriteManifests(install.installId)
        val skipped = if (restoreBackups) {
            ModMaterializer.restoreBackups(manifests)
        } else {
            emptyList()
        }
        val removalSkipped = ModMaterializer.removeAppliedFiles(
            install = install,
            recipes = recipes,
            gameRootDir = gameRootDir,
            winePrefix = winePrefix,
            restoredOverwriteTargets = manifests.map { it.targetPath }.toSet(),
        )
        dao.updateInstallEnabled(install.installId, false, ModInstallStatus.DISABLED.name)
        skipped + removalSkipped
    }

    suspend fun deleteInstall(
        context: Context,
        install: ModInstall,
        restoreBackups: Boolean,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, install.appId),
    ): List<String> = withContext(Dispatchers.IO) {
        val skipped = disableInstall(context, install, restoreBackups, gameRootDir, winePrefix)
        val dao = dao(context)
        dao.deleteOverwriteManifests(install.installId)
        dao.deleteInstall(install.installId)
        if (install.archivePath.isNotBlank()) {
            val archiveFile = File(install.archivePath)
            archiveFile.delete()
            archiveFile.parentFile?.let { File(it, "${archiveFile.name}.part").delete() }
        }
        File(install.extractedPath).deleteRecursively()
        File(backupRoot(context, install.appId), install.installId).deleteRecursively()
        skipped
    }

    suspend fun deleteInstallsForApp(
        context: Context,
        appId: String,
        restoreBackups: Boolean = true,
        gameRootDir: File? = null,
        winePrefix: String = ModContainerResolver.getWinePrefix(context, appId),
    ): List<String> = withContext(Dispatchers.IO) {
        val installs = dao(context).getInstallsForApp(appId)
        val skipped = installs.flatMap { install ->
            deleteInstall(
                context = context,
                install = install,
                restoreBackups = restoreBackups,
                gameRootDir = gameRootDir,
                winePrefix = winePrefix,
            )
        }
        cacheRoot(context, appId).deleteRecursively()
        backupRoot(context, appId).deleteRecursively()
        skipped
    }

    suspend fun cleanupOrphanedFilesForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val installs = dao(context).getInstallsForApp(appId)
        val installIds = installs.map { it.installId }.toSet()
        val activeImportIds = installs
            .filter { it.status in resumableImportStatuses }
            .map { it.installId }
            .toSet()
        var reclaimedBytes = 0L

        val root = cacheRoot(context, appId)
        val archiveDir = File(root, "archives")
        val referencedArchives = installs
            .filter { it.status in resumableImportStatuses || (KEEP_IMPORTED_ARCHIVES && it.status in reusableStatuses) }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .toSet()
        val referencedPartials = installs
            .filter { it.status in resumableImportStatuses || it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .map { File(it.parentFile, "${it.name}.part") }
            .toSet()
        archiveDir.listFiles().orEmpty().forEach { file ->
            when {
                file.extension.equals("part", ignoreCase = true) && file !in referencedPartials -> reclaimedBytes += deleteFileBytes(file)
                file.isFile && file !in referencedArchives -> reclaimedBytes += deleteFileBytes(file)
            }
        }

        val extractedDir = File(root, "extracted")
        extractedDir.listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".tmp") && file.name.removeSuffix(".tmp") !in activeImportIds -> reclaimedBytes += deleteDirectoryBytes(file)
                file.isDirectory && !file.name.endsWith(".tmp") && file.name !in installIds -> reclaimedBytes += deleteDirectoryBytes(file)
            }
        }

        backupRoot(context, appId).listFiles().orEmpty().forEach { backup ->
            if (backup.isDirectory && backup.name !in installIds) {
                reclaimedBytes += deleteDirectoryBytes(backup)
            }
        }

        ModCleanupResult(reclaimedBytes)
    }

    suspend fun cleanupFailedArchivesForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val installs = dao(context).getInstallsForApp(appId)
        val reclaimedBytes = installs
            .filter { it.status == ModInstallStatus.ERROR.name }
            .sumOf { install ->
                val archive = install.archivePath.takeIf(String::isNotBlank)?.let(::File)
                deleteFileBytes(archive) + deleteFileBytes(archive?.let(::partialFileFor))
            }
        ModCleanupResult(reclaimedBytes)
    }

    suspend fun cleanupRedundantBackupsForApp(context: Context, appId: String): ModCleanupResult = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val manifests = dao.getRedundantOverwriteManifestsForApp(appId)
        val backupRoot = backupRoot(context, appId)
        val safePaths = safeBackupPaths(backupRoot, manifests.map { it.backupPath }).toSet()
        val reclaimedBytes = safePaths.sumOf { deleteFileBytes(File(it)) }
        manifests
            .filter { manifest -> safeBackupPathOrNull(backupRoot, manifest.backupPath) in safePaths }
            .map { it.manifestId }
            .chunked(500)
            .forEach { dao.deleteOverwriteManifestsByIds(it) }
        deleteEmptyDirectories(backupRoot)
        ModCleanupResult(reclaimedBytes)
    }

    suspend fun scanStorageForApp(context: Context, appId: String): ModStorageBreakdown = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installs = dao.getInstallsForApp(appId)
        val redundantBackupManifests = dao.getRedundantOverwriteManifestsForApp(appId)
        val safeRedundantBackupPaths = safeBackupPaths(backupRoot(context, appId), redundantBackupManifests.map { it.backupPath })
        val redundantBackups = safeRedundantBackupPaths.sumOf { File(it).takeIf(File::isFile)?.length() ?: 0L }
        val installIds = installs.map { it.installId }.toSet()
        val activeImportIds = installs.filter { it.status in resumableImportStatuses }.map { it.installId }.toSet()
        val failedArchives = installs
            .filter { it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .flatMap { listOfNotNull(it, partialFileFor(it)) }
            .toSet()
        val referencedArchives = installs
            .filter { it.status in resumableImportStatuses || (KEEP_IMPORTED_ARCHIVES && it.status in reusableStatuses) }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File) }
            .toSet()
        val referencedPartials = installs
            .filter { it.status in resumableImportStatuses || it.status == ModInstallStatus.ERROR.name }
            .mapNotNull { it.archivePath.takeIf(String::isNotBlank)?.let(::File)?.let(::partialFileFor) }
            .toSet()
        var cleanable = 0L
        var failedArchiveBytes = 0L
        File(cacheRoot(context, appId), "archives").listFiles().orEmpty().forEach { file ->
            when {
                file in failedArchives -> failedArchiveBytes += file.length()
                file.extension.equals("part", ignoreCase = true) && file !in referencedPartials -> cleanable += file.length()
                file.isFile && file !in referencedArchives -> cleanable += file.length()
            }
        }
        var extractedCache = 0L
        File(cacheRoot(context, appId), "extracted").listFiles().orEmpty().forEach { file ->
            when {
                file.name.endsWith(".tmp") && file.name.removeSuffix(".tmp") !in activeImportIds -> cleanable += directorySize(file)
                file.isDirectory && !file.name.endsWith(".tmp") && file.name !in installIds -> cleanable += directorySize(file)
                file.isDirectory && !file.name.endsWith(".tmp") -> extractedCache += directorySize(file)
            }
        }
        var backups = 0L
        backupRoot(context, appId).listFiles().orEmpty().forEach { backup ->
            if (backup.isDirectory && backup.name in installIds) backups += directorySize(backup) else cleanable += directorySize(backup)
        }
        ModStorageBreakdown(cleanable, failedArchiveBytes, extractedCache, backups, redundantBackups, safeRedundantBackupPaths.size)
    }

    suspend fun checkInstallHealthForApp(
        context: Context,
        appId: String,
        gameRootDir: File?,
        winePrefix: String,
    ): ModHealthReport = withContext(Dispatchers.IO) {
        val dao = dao(context)
        val installs = dao.getInstallsForApp(appId)
        val installIds = installs.map { it.installId }.toSet()
        val backupRoot = backupRoot(context, appId)
        val issues = mutableListOf<ModHealthIssue>()
        var missingBackupCount = 0
        var redundantMissingBackupCount = 0
        var unsafeBackupCount = 0
        val missingBackupTargets = mutableListOf<String>()
        val unsafeBackupExamples = mutableListOf<String>()

        fun add(
            severity: ModHealthSeverity,
            title: String,
            detail: String,
            install: ModInstall? = null,
        ) {
            issues += ModHealthIssue(severity, title, detail, install?.modName.orEmpty())
        }

        installs.forEach { install ->
            val status = runCatching { ModInstallStatus.valueOf(install.status) }.getOrNull()
            val extracted = File(install.extractedPath)
            val recipes = dao.getRecipesForInstall(install.installId)
            val manifests = dao.getOverwriteManifests(install.installId)

            if (status == null) {
                add(ModHealthSeverity.ERROR, "Unknown install status", install.status, install)
            }
            if (install.status in reusableStatuses && !extracted.isDirectory) {
                add(
                    if (install.status == ModInstallStatus.APPLIED.name) ModHealthSeverity.ERROR else ModHealthSeverity.WARNING,
                    "Extracted cache is missing",
                    "Reapply, disable/delete, or placement changes may require redownloading this mod.",
                    install,
                )
            }
            if (install.status == ModInstallStatus.APPLIED.name) {
                if (recipes.none { it.enabled }) {
                    add(ModHealthSeverity.ERROR, "Applied mod has no placement recipe", "GameNative cannot verify or safely remove deployed files.", install)
                } else if (extracted.isDirectory) {
                    val missing = missingAppliedTargets(install, recipes, gameRootDir, winePrefix).take(3)
                    if (missing.isNotEmpty()) {
                        add(
                            ModHealthSeverity.ERROR,
                            "Some mod files are missing from the game folder",
                            "Apply order can restore them if the mod cache is still available.\n${missing.joinToString("\n")}",
                            install,
                        )
                    }
                }
            }
            if (install.status == ModInstallStatus.READY.name && manifests.isNotEmpty()) {
                add(ModHealthSeverity.WARNING, "Ready mod has overwrite records", "This mod is not applied but still has ${manifests.size} overwrite record(s).", install)
            }
            manifests.forEach { manifest ->
                if (manifest.backupPath.isBlank()) return@forEach
                val safePath = safeBackupPathOrNull(backupRoot, manifest.backupPath)
                when {
                    safePath == null -> {
                        unsafeBackupCount++
                        if (unsafeBackupExamples.size < 3) unsafeBackupExamples += manifest.backupPath
                    }
                    !File(safePath).isFile && manifest.originalHash.isNotBlank() && manifest.originalHash == manifest.installedHash -> {
                        redundantMissingBackupCount++
                    }
                    !File(safePath).isFile -> {
                        missingBackupCount++
                        missingBackupTargets += manifest.targetPath
                    }
                }
            }
        }

        if (unsafeBackupCount > 0) {
            add(
                ModHealthSeverity.ERROR,
                "Unsafe backup records",
                "$unsafeBackupCount backup record(s) point outside GameNative's backup folder.\n${unsafeBackupExamples.joinToString("\n")}",
            )
        }
        if (missingBackupCount > 0) {
            add(
                ModHealthSeverity.WARNING,
                "Backup files are missing",
                "$missingBackupCount non-redundant backup file(s) are missing. Disable/delete may not restore original files for those targets. If you need the originals back, repair/verify the game files before disabling these mods.\n\nMissing backup targets:\n${missingBackupTargets.joinToString("\n")}",
            )
        }
        if (redundantMissingBackupCount > 0) {
            add(
                ModHealthSeverity.WARNING,
                "Stale redundant backup records",
                "$redundantMissingBackupCount redundant backup record(s) point to already-cleaned files. Storage cleanup may show 0 B because only database records remain; use Clean redundant backups to remove them.",
            )
        }

        val extractedDir = File(cacheRoot(context, appId), "extracted")
        extractedDir.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.endsWith(".tmp") && it.name !in installIds }
            .take(3)
            .forEach { add(ModHealthSeverity.WARNING, "Orphaned extracted cache", it.name) }

        ModHealthReport(issues)
    }

    fun hasMissingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): Boolean =
        install.status == ModInstallStatus.APPLIED.name &&
            recipes.any { it.enabled } &&
            missingAppliedTargets(install, recipes, gameRootDir, winePrefix).isNotEmpty()

    suspend fun archiveEntries(install: ModInstall): List<ModArchiveEntry> =
        withContext(Dispatchers.IO) { ModArchiveExtractor.listExtractedEntries(File(install.extractedPath)) }

    private fun installId(appId: String, gameDomain: String, modId: Long, fileId: Long): String =
        "${sanitizeFileName(appId)}_${sanitizeFileName(gameDomain)}_${modId}_$fileId"

    private val reusableStatuses = NexusImportState.reusableStatuses

    val resumableImportStatuses = NexusImportState.resumableImportStatuses

    private fun moveFileOrDirectory(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (source.isDirectory) {
            if (!source.copyRecursively(target, overwrite = true)) {
                throw IOException("Failed to move extracted mod files")
            }
            source.deleteRecursively()
        } else {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun download(
        installId: String,
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (ModImportProgress) -> Unit,
    ) {
        ensureDownloadNetworkAllowed()
        destination.parentFile?.mkdirs()
        var existingBytes = if (destination.isFile) destination.length() else 0L
        if (expectedBytes > 0L && existingBytes == expectedBytes) {
            onProgress(
                ModImportProgress(
                    status = "Downloading",
                    progress = 1f,
                    downloadedBytes = existingBytes,
                    totalBytes = expectedBytes,
                ),
            )
            return
        }
        if (expectedBytes > 0L && existingBytes > expectedBytes) {
            destination.delete()
            existingBytes = 0L
        }

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }
        val request = requestBuilder.build()
        downloadClient.newCall(request).execute().use { response ->
            ensureDownloadNetworkAllowed()
            if (ModDownloadRegistry.isCancelRequested(installId)) {
                throw ModImportCanceledException("Import canceled")
            }
            if (response.code == 416 && existingBytes > 0L) {
                val remoteBytes = response.header("Content-Range")
                    ?.substringAfter("*/", "")
                    ?.toLongOrNull()
                if (remoteBytes == existingBytes || (remoteBytes == null && expectedBytes > 0L && existingBytes >= expectedBytes)) {
                    onProgress(
                        ModImportProgress(
                            status = "Downloading",
                            progress = 1f,
                            downloadedBytes = existingBytes,
                            totalBytes = existingBytes,
                        ),
                    )
                    return
                }
            }
            if (response.code == 416 && expectedBytes > 0L && existingBytes >= expectedBytes) {
                onProgress(
                    ModImportProgress(
                        status = "Downloading",
                        progress = 1f,
                        downloadedBytes = existingBytes,
                        totalBytes = expectedBytes,
                    ),
                )
                return
            }
            if (!response.isSuccessful) throw NexusApiException("Download failed (${response.code})", response.code)
            val append = existingBytes > 0L && response.code == 206
            if (existingBytes > 0L && !append) {
                destination.delete()
                existingBytes = 0L
            }
            val responseLength = response.body.contentLength().takeIf { it > 0 }
            val total = when {
                append && responseLength != null -> existingBytes + responseLength
                responseLength != null -> responseLength
                expectedBytes > 0L -> expectedBytes
                else -> 0L
            }
            var downloaded = existingBytes
            onProgress(
                ModImportProgress(
                    status = "Downloading",
                    progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                ),
            )
            response.body.byteStream().use { input ->
                FileOutputStream(destination, append).buffered(DOWNLOAD_BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        ensureDownloadNetworkAllowed()
                        if (ModDownloadRegistry.isCancelRequested(installId)) {
                            throw ModImportCanceledException("Import canceled")
                        }
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (total > 0L) {
                            (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        onProgress(
                            ModImportProgress(
                                status = "Downloading",
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                            ),
                        )
                    }
                }
            }
            if (total > 0L && downloaded < total) {
                throw IOException("Download ended early ($downloaded of $total bytes)")
            }
        }
        onProgress(
            ModImportProgress(
                status = "Downloading",
                progress = 1f,
                downloadedBytes = destination.length(),
                totalBytes = destination.length(),
            ),
        )
    }

    private fun ensureDownloadNetworkAllowed() {
        if (PrefManager.downloadOnWifiOnly && !NetworkMonitor.hasWifiOrEthernet.value) {
            throw ModImportPausedException("Download paused because Wi-Fi/LAN-only downloads are enabled")
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { "mod" }
    }

    private fun estimateImportScratchBytes(sizeBytes: Long): Long {
        val archiveBytes = sizeBytes.takeIf { it > 0L } ?: UNKNOWN_IMPORT_BYTES
        val extractedBytes = estimatedExtractedBytes(archiveBytes)
        return if (Long.MAX_VALUE - archiveBytes < extractedBytes) Long.MAX_VALUE else archiveBytes + extractedBytes
    }

    private fun estimatedExtractedBytes(archiveBytes: Long): Long =
        safeMultiply(archiveBytes, ESTIMATED_EXTRACTED_MULTIPLIER)

    private fun safeMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun shouldRedownloadArchiveAfterExtractionFailure(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return listOf(
            "checksum",
            "corrupt",
            "truncated",
            "unexpected end",
            "invalid header",
            "download ended early",
        ).any(message::contains)
    }

    private fun missingAppliedTargets(
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
        gameRootDir: File?,
        winePrefix: String,
    ): List<String> {
        val missing = mutableListOf<String>()
        recipes.filter { it.enabled }.forEach { recipe ->
            val mode = runCatching { ModPlacementMode.valueOf(recipe.mode) }.getOrDefault(ModPlacementMode.SYMLINK)
            val entries = runCatching {
                ModMaterializer.plannedEntries(install, listOf(recipe), gameRootDir, winePrefix)
            }.getOrElse { e ->
                missing += e.message ?: "${recipe.targetRoot}:${recipe.targetRelativePath}"
                return@forEach
            }
            entries.forEach { entry ->
                missing += missingTargetsForEntry(entry, mode)
                if (missing.size >= 3) return missing
            }
        }
        return missing
    }

    private fun missingTargetsForEntry(entry: ModPlannedEntry, mode: ModPlacementMode): List<String> {
        if (mode == ModPlacementMode.SYMLINK) {
            return if (Files.isSymbolicLink(entry.target.toPath()) || entry.target.exists()) emptyList() else listOf(entry.target.absolutePath)
        }
        if (entry.source.isFile) {
            return if (entry.target.isFile) emptyList() else listOf(entry.target.absolutePath)
        }
        if (!entry.source.isDirectory) return emptyList()
        return entry.source.walkTopDown()
            .filter { it.isFile }
            .take(50)
            .mapNotNull { sourceFile ->
                val relative = sourceFile.relativeTo(entry.source).path
                File(entry.target, relative).takeUnless { it.isFile }?.absolutePath
            }
            .take(3)
            .toList()
    }

    private fun partialFileFor(archiveFile: File): File? =
        archiveFile.parentFile?.let { File(it, "${archiveFile.name}.part") }

    private fun deleteFileBytes(file: File?): Long {
        if (file?.isFile != true) return 0L
        val size = file.length()
        return if (file.delete()) size else 0L
    }

    private fun deleteDirectoryBytes(dir: File): Long {
        if (!dir.isDirectory) return 0L
        val size = directorySize(dir)
        return if (dir.deleteRecursively()) size else 0L
    }

    private fun safeBackupPaths(root: File, paths: Iterable<String>): List<String> =
        paths.mapNotNull { safeBackupPathOrNull(root, it) }.distinct()

    private fun safeBackupPathOrNull(root: File, path: String): String? {
        return runCatching {
            val rootPath = root.canonicalFile.toPath()
            File(path).canonicalFile
                .takeIf { file -> file.toPath().startsWith(rootPath) }
                ?.absolutePath
        }.getOrNull()
    }

    private fun deleteEmptyDirectories(root: File) {
        root.listFiles().orEmpty().filter { it.isDirectory }.forEach(::deleteEmptyDirectories)
        if (root.listFiles()?.isEmpty() == true) root.delete()
    }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

}
