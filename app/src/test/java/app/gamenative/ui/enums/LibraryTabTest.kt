package app.gamenative.ui.enums

import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTabTest {
    private val supported = listOf(
        LibraryTab.ALL,
        LibraryTab.STEAM,
        LibraryTab.GOG,
        LibraryTab.EPIC,
    )

    @Test
    fun normalizePreferences_preservesHiddenTabsAndOrder() {
        val result = LibraryTab.normalizePreferences("ALL,!EPIC,STEAM,!GOG", supported)

        assertEquals(
            listOf(LibraryTab.ALL, LibraryTab.EPIC, LibraryTab.STEAM, LibraryTab.GOG),
            result.map { it.tab },
        )
        assertFalse(result.first { it.tab == LibraryTab.EPIC }.isVisible)
        assertFalse(result.first { it.tab == LibraryTab.GOG }.isVisible)
    }

    @Test
    fun normalizePreferences_addsNewSupportedTabsVisibleAndDropsInvalidValues() {
        val result = LibraryTab.normalizePreferences("STEAM,UNKNOWN,STEAM", supported)

        assertEquals(supported, result.map { it.tab })
        assertTrue(result.all { it.isVisible })
    }

    @Test
    fun normalizePreferences_forcesAllVisibleAndFirst() {
        val result = LibraryTab.normalizePreferences("!GOG,!ALL,STEAM", supported)

        assertEquals(LibraryTab.ALL, result.first().tab)
        assertTrue(result.first().isVisible)
    }

    @Test
    fun serializePreferences_roundTripsVisibilityAndOrder() {
        val preferences = LibraryTab.normalizePreferences("ALL,!EPIC,GOG,!STEAM", supported)

        val restored = LibraryTab.normalizePreferences(
            LibraryTab.serializePreferences(preferences),
            supported,
        )

        assertEquals(preferences, restored)
    }

    @Test
    fun traversalUsesOnlyEffectiveVisibleTabs() {
        val visible = listOf(LibraryTab.ALL, LibraryTab.GOG)

        assertEquals(LibraryTab.GOG, LibraryTab.ALL.next(visible))
        assertEquals(LibraryTab.ALL, LibraryTab.GOG.next(visible))
        assertEquals(LibraryTab.GOG, LibraryTab.ALL.previous(visible))
    }
}
