package app.gamenative.mods

import org.junit.Assert.assertTrue
import org.junit.Test

class NexusCollectionPrioritySuggesterTest {
    @Test
    fun priorities_placeDependenciesAfterRequiredMods() {
        val base = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 1,
            fileId = 10,
            modName = "Base mod",
            position = 0,
        )
        val patch = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 2,
            fileId = 20,
            modName = "Base mod patch",
            position = 1,
            dependencyModIds = listOf(1),
        )

        val priorities = NexusCollectionPrioritySuggester.priorities(listOf(patch, base))

        assertTrue(priorities[key(patch)]!! > priorities[key(base)]!!)
    }

    @Test
    fun priorities_preserveTransitiveDependencyOrder() {
        val dependent = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 3,
            fileId = 30,
            modName = "Dependent",
            position = 0,
            dependencyModIds = listOf(2),
        )
        val middle = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 2,
            fileId = 20,
            modName = "Middle",
            position = 1,
            dependencyModIds = listOf(1),
        )
        val base = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 1,
            fileId = 10,
            modName = "Base",
            position = 2,
        )

        val priorities = NexusCollectionPrioritySuggester.priorities(listOf(dependent, middle, base))

        assertTrue(priorities[key(middle)]!! > priorities[key(base)]!!)
        assertTrue(priorities[key(dependent)]!! > priorities[key(middle)]!!)
    }

    @Test
    fun priorities_ignoreDependencyModIdsFromOtherGameDomains() {
        val dependent = NexusCollectionFile(
            gameDomain = "skyrimspecialedition",
            modId = 2,
            fileId = 20,
            modName = "Dependent",
            position = 0,
            dependencyModIds = listOf(1),
        )
        val otherGameFile = NexusCollectionFile(
            gameDomain = "fallout4",
            modId = 1,
            fileId = 10,
            modName = "Other game",
            position = 1,
        )

        val priorities = NexusCollectionPrioritySuggester.priorities(listOf(dependent, otherGameFile))

        assertTrue(priorities[key(dependent)]!! < priorities[key(otherGameFile)]!!)
    }

    private fun key(file: NexusCollectionFile): String =
        "${file.gameDomain}:${file.modId}:${file.fileId}:${file.position}"
}
