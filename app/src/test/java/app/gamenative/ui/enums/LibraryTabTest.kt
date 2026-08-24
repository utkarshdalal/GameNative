package app.gamenative.ui.enums

import app.gamenative.ui.enums.LibraryTab.Companion.next
import app.gamenative.ui.enums.LibraryTab.Companion.previous
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTabTest {
    private val supported = listOf(
        LibraryTab.ALL,
        LibraryTab.STEAM,
        LibraryTab.GOG,
        LibraryTab.EPIC,
    )

    @Test
    fun normalizeVisibleTabs_usesSupportedTabOrder() {
        val result = LibraryTab.normalizeVisibleTabs("v2:GOG,ALL,STEAM", supported)

        assertEquals(listOf(LibraryTab.ALL, LibraryTab.STEAM, LibraryTab.GOG), result)
    }

    @Test
    fun normalizeVisibleTabs_defaultsToAllSupportedTabs() {
        val result = LibraryTab.normalizeVisibleTabs("", supported)

        assertEquals(supported, result)
    }

    @Test
    fun normalizeVisibleTabs_keepsAllVisibleAndDropsInvalidValues() {
        val result = LibraryTab.normalizeVisibleTabs("v2:UNKNOWN,STEAM,STEAM", supported)

        assertEquals(listOf(LibraryTab.ALL, LibraryTab.STEAM), result)
    }

    @Test
    fun normalizeVisibleTabs_migratesHiddenPreferenceFormat() {
        val result = LibraryTab.normalizeVisibleTabs("ALL,!EPIC,GOG,!STEAM", supported)

        assertEquals(listOf(LibraryTab.ALL, LibraryTab.GOG), result)
    }

    @Test
    fun normalizeVisibleTabs_addsNewTabsWhenMigratingLegacyPreferences() {
        val result = LibraryTab.normalizeVisibleTabs("ALL,STEAM,GOG", supported)

        assertEquals(supported, result)
    }

    @Test
    fun serializeVisibleTabs_roundTripsSelection() {
        val visibleTabs = listOf(LibraryTab.ALL, LibraryTab.GOG)

        val restored = LibraryTab.normalizeVisibleTabs(
            LibraryTab.serializeVisibleTabs(visibleTabs),
            supported,
        )

        assertEquals(visibleTabs, restored)
    }

    @Test
    fun traversalUsesOnlyEffectiveVisibleTabs() {
        val visible = listOf(LibraryTab.ALL, LibraryTab.GOG)

        assertEquals(LibraryTab.GOG, LibraryTab.ALL.next(visible))
        assertEquals(LibraryTab.ALL, LibraryTab.GOG.next(visible))
        assertEquals(LibraryTab.GOG, LibraryTab.ALL.previous(visible))
    }
}
