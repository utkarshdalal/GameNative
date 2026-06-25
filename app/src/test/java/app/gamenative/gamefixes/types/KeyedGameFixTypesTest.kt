package app.gamenative.gamefixes

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedGameFixTypesTest {
    @Test
    fun keyedRegistryFix_exposesSourceAndId_forRegistryLookup() {
        val fix = STEAM_Fix_22330

        assertTrue(fix is KeyedRegistryKeyFix)
        assertEquals(GameSource.STEAM, fix.gameSource)
        assertEquals("22330", fix.gameId)
    }

    @Test
    fun keyedLaunchArgFix_exposesSourceAndId_forRegistryLookup() {
        val fix = GOG_Fix_1129934535

        assertTrue(fix is KeyedLaunchArgFix)
        assertEquals(GameSource.GOG, fix.gameSource)
        assertEquals("1129934535", fix.gameId)
    }

    @Test
    fun gogDependencyFix_exposesSourceAndId_forRegistryLookup() {
        val fix = GOG_Fix_2147483047

        assertTrue(fix is GOGDependencyFix)
        assertEquals(GameSource.GOG, fix.gameSource)
        assertEquals("2147483047", fix.gameId)
    }
}
