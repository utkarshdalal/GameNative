package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot
import java.io.File

object BethesdaPlacementRecipeExpander {
    private val pluginExtensions = setOf("esp", "esm", "esl")
    private val sidecarExtensions = setOf("bsa", "bsl", "ba2", "ini")

    fun expand(
        gameName: String,
        install: ModInstall,
        recipes: List<ModPlacementRecipe>,
    ): List<ModPlacementRecipe> {
        if (BethesdaPluginManager.detectGame(gameName) == null) return recipes
        val extractedRoot = runCatching { File(install.extractedPath).canonicalFile }.getOrNull()
            ?: return recipes
        val expanded = mutableListOf<ModPlacementRecipe>()
        val existingRecipeKeys = recipes.mapTo(mutableSetOf()) { recipeKey(toBethesdaDataRecipe(it)) }
        val coveredDataSourceFiles = recipes
            .filter { it.enabled && isBethesdaDataTarget(it) }
            .flatMap { recipe -> ModPlacementSources.decode(recipe.sourceSubpath) }
            .mapNotNull { sourceSubpath -> resolveSource(extractedRoot, sourceSubpath)?.takeIf { it.isFile } }
            .mapTo(mutableSetOf()) { it.canonicalFile.path }

        recipes.forEach { recipe ->
            val dataTarget = isBethesdaDataTarget(recipe)
            val effectiveRecipe = toBethesdaDataRecipe(recipe)
            expanded += effectiveRecipe

            if (!dataTarget || !recipe.enabled) return@forEach
            ModPlacementSources.decode(recipe.sourceSubpath).forEach { sourceSubpath ->
                val sourceFile = resolveSource(extractedRoot, sourceSubpath) ?: return@forEach
                if (!sourceFile.isFile || sourceFile.extension.lowercase() !in pluginExtensions) return@forEach
                pluginSidecars(sourceFile).forEach { sidecar ->
                    val sidecarPath = sidecar.canonicalFile.path
                    if (sidecarPath in coveredDataSourceFiles) return@forEach
                    val relative = sidecar.relativeTo(extractedRoot).path.replace(File.separatorChar, '/')
                    val sidecarRecipe = effectiveRecipe.copy(
                        recipeId = 0L,
                        sourceSubpath = ModPlacementSources.encode(listOf(relative)),
                        mode = ModPlacementMode.OVERWRITE_COPY.name,
                        stripPrefixSegments = 0,
                        includeSourceDirectory = false,
                    )
                    val key = recipeKey(sidecarRecipe)
                    if (key !in existingRecipeKeys) {
                        expanded += sidecarRecipe
                        existingRecipeKeys += key
                        coveredDataSourceFiles += sidecarPath
                    }
                }
            }
        }

        return expanded.distinctBy(::recipeKey)
    }

    private fun toBethesdaDataRecipe(recipe: ModPlacementRecipe): ModPlacementRecipe =
        if (isBethesdaDataTarget(recipe) && recipe.mode == ModPlacementMode.SYMLINK.name) {
            recipe.copy(mode = ModPlacementMode.OVERWRITE_COPY.name)
        } else {
            recipe
        }

    private fun isBethesdaDataTarget(recipe: ModPlacementRecipe): Boolean =
        recipe.targetRoot == ModTargetRoot.GAME_DIR.name &&
            ModPlacementSources.normalize(recipe.targetRelativePath).equals("Data", ignoreCase = true)

    private fun recipeKey(recipe: ModPlacementRecipe): String {
        return listOf(
            recipe.installId,
            recipe.sourceSubpath,
            recipe.targetRoot,
            ModPlacementSources.normalize(recipe.targetRelativePath).lowercase(),
            recipe.mode,
            recipe.stripPrefixSegments.toString(),
            recipe.includeSourceDirectory.toString(),
            recipe.enabled.toString(),
        ).joinToString("|")
    }

    private fun resolveSource(root: File, sourceSubpath: String): File? {
        val normalized = ModPlacementSources.normalize(sourceSubpath)
        val candidate = if (normalized.isBlank()) root else File(root, normalized)
        val file = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun pluginSidecars(plugin: File): List<File> {
        val base = plugin.nameWithoutExtension.lowercase()
        return plugin.parentFile
            ?.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file != plugin &&
                    file.extension.lowercase() in sidecarExtensions &&
                    file.nameWithoutExtension.lowercase().let { it == base || it.startsWith("$base - ") }
            }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }
}
