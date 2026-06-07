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

    private fun key(file: NexusCollectionFile): String =
        "${file.gameDomain}:${file.modId}:${file.fileId}:${file.position}"
}
