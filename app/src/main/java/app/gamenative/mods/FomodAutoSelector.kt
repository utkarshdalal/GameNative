package app.gamenative.mods

import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModPlacementRecipe
import app.gamenative.data.ModTargetRoot

data class FomodAutoSelectionResult(
    val recipes: List<ModPlacementRecipe>,
    val selectedOptions: List<String>,
    val reasons: List<String>,
)

object FomodAutoSelector {
    fun selectDeterministic(
        installId: String,
        installer: FomodInstaller,
        targetRoot: String = ModTargetRoot.GAME_DIR.name,
        targetRelativePath: String = "Data",
    ): FomodAutoSelectionResult? {
        if (installer.unsupportedWarnings.isNotEmpty()) return null
        if (installer.steps.any { step -> step.groups.any { group -> group.plugins.any { it.typePatterns.isNotEmpty() } } }) {
            return null
        }

        val selectedKeys = linkedSetOf<String>()
        val selectedLabels = mutableListOf<String>()
        installer.steps.forEachIndexed { stepIndex, step ->
            step.groups.forEachIndexed { groupIndex, group ->
                val candidates = group.plugins
                    .mapIndexed { pluginIndex, plugin ->
                        FomodRecipeGenerator.pluginKey(stepIndex, groupIndex, pluginIndex) to plugin
                    }
                    .filter { (_, plugin) -> plugin.type != FomodPluginType.NOT_USABLE }
                val preferred = candidates.filter { (_, plugin) ->
                    plugin.type == FomodPluginType.REQUIRED || plugin.type == FomodPluginType.RECOMMENDED
                }
                val picks = when (group.type) {
                    FomodGroupType.SELECT_EXACTLY_ONE -> when {
                        preferred.size == 1 -> preferred
                        candidates.size == 1 -> candidates
                        else -> return null
                    }
                    FomodGroupType.SELECT_AT_LEAST_ONE -> preferred.ifEmpty {
                        if (candidates.size == 1) candidates else return null
                    }
                    FomodGroupType.SELECT_AT_MOST_ONE -> when {
                        preferred.size <= 1 -> preferred
                        else -> return null
                    }
                    FomodGroupType.SELECT_ANY -> preferred
                }
                picks.forEach { (key, plugin) ->
                    selectedKeys += key
                    selectedLabels += listOf(step.name, group.name, plugin.name)
                        .filter { it.isNotBlank() }
                        .joinToString(" / ")
                }
            }
        }

        val result = FomodRecipeGenerator.generateForPluginKeys(
            installId = installId,
            installer = installer,
            selectedPluginKeys = selectedKeys,
            targetRoot = targetRoot,
            targetRelativePath = targetRelativePath,
            mode = ModPlacementMode.OVERWRITE_COPY.name,
        )
        if (result.unsupportedMappings.isNotEmpty()) return null
        if (result.recipes.isEmpty()) return null

        return FomodAutoSelectionResult(
            recipes = result.recipes,
            selectedOptions = selectedLabels.distinct(),
            reasons = listOf("FOMOD choices were deterministic") +
                selectedLabels.distinct().take(4).map { "Selected: $it" },
        )
    }
}
