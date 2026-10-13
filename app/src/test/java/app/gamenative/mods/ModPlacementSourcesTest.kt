package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModPlacementSourcesTest {
    @Test
    fun decode_returnsEmptyListWhenNoExplicitSourcePathsExist() {
        assertTrue(ModPlacementSources.decode("").isEmpty())
        assertTrue(ModPlacementSources.decode("[]").isEmpty())
        assertTrue(ModPlacementSources.decode("[\"\"]").isEmpty())
        assertTrue(ModPlacementSources.decode("/").isEmpty())
    }

    @Test
    fun decode_normalizesAndDeduplicatesExplicitSourcePaths() {
        assertEquals(
            listOf("Data/Plugin.esp", "textures"),
            ModPlacementSources.decode("[\"/Data/Plugin.esp\", \"Data/Plugin.esp\", \"textures/\"]"),
        )
    }
}
