package app.gamenative.utils

import app.gamenative.data.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContainerStorageManagerTest {
    @Test
    fun normalizeContainerId_stripsDuplicateSuffix() {
        assertEquals("STEAM_123", ContainerStorageManager.normalizeContainerId("STEAM_123(1)"))
        assertEquals("CUSTOM_GAME_42", ContainerStorageManager.normalizeContainerId("CUSTOM_GAME_42(99)"))
        assertEquals("GOG_7", ContainerStorageManager.normalizeContainerId("GOG_7"))
    }

    @Test
    fun detectGameSource_onlyRecognizesKnownPrefixes() {
        assertEquals(GameSource.STEAM, ContainerStorageManager.detectGameSource("STEAM_123"))
        assertEquals(GameSource.CUSTOM_GAME, ContainerStorageManager.detectGameSource("CUSTOM_GAME_42"))
        assertEquals(GameSource.GOG, ContainerStorageManager.detectGameSource("GOG_7"))
        assertEquals(GameSource.EPIC, ContainerStorageManager.detectGameSource("EPIC_8"))
        assertEquals(GameSource.AMAZON, ContainerStorageManager.detectGameSource("AMAZON_9"))
        assertNull(ContainerStorageManager.detectGameSource("12345"))
    }

    @Test
    fun extractGameId_returnsTrailingNumericId() {
        assertEquals(123, ContainerStorageManager.extractGameId("STEAM_123"))
        assertEquals(42, ContainerStorageManager.extractGameId("CUSTOM_GAME_42"))
        assertNull(ContainerStorageManager.extractGameId("CUSTOM_GAME_42_test"))
        assertNull(ContainerStorageManager.extractGameId("BROKEN"))
    }
}
