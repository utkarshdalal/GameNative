package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

data class ModConflictParticipant(
    val installId: String,
    val modName: String,
    val sourcePath: String,
    val priority: Int,
    val wins: Boolean,
)

data class ModFileConflictReport(
    val targetPath: String,
    val targetRelativePath: String,
    val winnerInstallId: String,
    val participants: List<ModConflictParticipant>,
)

object ModConflictAnalyzer {
    suspend fun analyze(
        installs: List<ModInstall>,
        recipesByInstallId: Map<String, List<ModPlacementRecipe>>,
        prioritiesByInstallId: Map<String, Int>,
        gameRootDir: File?,
        winePrefix: String,
    ): List<ModFileConflictReport> = withContext(Dispatchers.IO) {
        val installById = installs.associateBy { it.installId }
        val plannedFiles = installs.flatMap { install ->
            val recipes = recipesByInstallId[install.installId].orEmpty()
            runCatching {
                ModMaterializer.plannedEntries(install, recipes, gameRootDir, winePrefix)
                    .flatMap { it.toPlannedFiles() }
            }.getOrElse { error ->
                Timber.w(error, "Skipping Nexus conflict analysis for install %s", install.installId)
                emptyList()
            }
        }

        plannedFiles
            .groupBy { it.target.safeCanonicalPath() }
            .filterValues { it.map { file -> file.installId }.distinct().size > 1 }
            .map { (targetPath, files) ->
                val sorted = files.sortedWith(
                    compareByDescending<PlannedFile> { prioritiesByInstallId[it.installId] ?: 0 }
                        .thenByDescending { installById[it.installId]?.updatedAt ?: 0L }
                        .thenByDescending { installById[it.installId]?.createdAt ?: 0L },
                )
                val winner = sorted.first()
                ModFileConflictReport(
                    targetPath = targetPath,
                    targetRelativePath = relativeTargetPath(targetPath, gameRootDir, winePrefix),
                    winnerInstallId = winner.installId,
                    participants = sorted.map { file ->
                        val install = installById[file.installId]
                        ModConflictParticipant(
                            installId = file.installId,
                            modName = install?.modName ?: file.installId,
                            sourcePath = file.source.absolutePath,
                            priority = prioritiesByInstallId[file.installId] ?: 0,
                            wins = file.installId == winner.installId,
                        )
                    },
                )
            }
            .sortedWith(compareBy<ModFileConflictReport> { it.targetRelativePath.lowercase() }.thenBy { it.targetPath })
    }

    private data class PlannedFile(
        val installId: String,
        val source: File,
        val target: File,
    )

    private fun ModPlannedEntry.toPlannedFiles(): List<PlannedFile> {
        if (source.isFile) {
            return listOf(PlannedFile(installId, source, target))
        }
        if (!source.isDirectory) return emptyList()
        val sourceRoot = source.canonicalFile
        return source.walkTopDown()
            .filter { it.isFile }
            .mapNotNull { file ->
                val relative = file.canonicalFile.relativeToOrNull(sourceRoot)?.path ?: return@mapNotNull null
                PlannedFile(installId, file, File(target, relative))
            }
            .toList()
    }

    private fun relativeTargetPath(path: String, gameRootDir: File?, winePrefix: String): String {
        val target = File(path)
        val roots = ModTargetResolver.roots(gameRootDir, winePrefix)
            .sortedByDescending { it.dir.absolutePath.length }
        val root = roots.firstOrNull { target.isInsideOrEqual(it.dir) } ?: return path
        val relative = runCatching {
            target.canonicalFile.relativeToOrNull(root.dir.canonicalFile)?.path.orEmpty()
        }.getOrDefault("")
        return if (relative.isBlank()) root.label else "${root.label}/${relative.replace(File.separatorChar, '/')}"
    }

    private fun File.isInsideOrEqual(root: File): Boolean =
        runCatching {
            val rootCanonical = root.canonicalFile
            val fileCanonical = canonicalFile
            fileCanonical == rootCanonical || fileCanonical.path.startsWith(rootCanonical.path + File.separator)
        }.getOrDefault(false)

    private fun File.safeCanonicalPath(): String =
        runCatching { canonicalFile.absolutePath }.getOrDefault(absolutePath)
}
