package app.gamenative.steam

import app.gamenative.data.SteamCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamCollectionFilterTest {
    private val favorites = SteamCollection("fav", "Favorites", setOf(440, 570))
    private val shooters = SteamCollection("sht", "Shooters", setOf(730))
    private val all = listOf(favorites, shooters)

    @Test fun notLoadedShowsAll() =
        assertTrue(SteamCollectionFilter.passes(999, setOf("fav"), collections = null))

    @Test fun emptySelectionShowsAll() =
        assertTrue(SteamCollectionFilter.passes(999, emptySet(), all))

    @Test fun unionMatchAcrossSelected() {
        val sel = setOf("fav", "sht")
        assertTrue(SteamCollectionFilter.passes(440, sel, all))
        assertTrue(SteamCollectionFilter.passes(730, sel, all))
        assertFalse(SteamCollectionFilter.passes(999, sel, all))
    }

    @Test fun selectionAllDeletedShowsAll() {
        // selected id no longer exists in collections -> effective selection empty -> show all
        assertTrue(SteamCollectionFilter.passes(999, setOf("gone"), all))
    }

    @Test fun reconcileDropsMissingIds() {
        val r = SteamCollectionFilter.reconcile(setOf("fav", "gone"), all)
        assertEquals(setOf("fav"), r.cleaned)
        assertTrue(r.removedAny)
    }

    @Test fun reconcileNoChangeWhenAllPresent() {
        val r = SteamCollectionFilter.reconcile(setOf("fav"), all)
        assertEquals(setOf("fav"), r.cleaned)
        assertFalse(r.removedAny)
    }

    @Test fun reconcileSkippedWhenNotLoaded() {
        // not loaded -> don't drop anything (avoid wiping a valid selection before data arrives)
        val r = SteamCollectionFilter.reconcile(setOf("fav"), collections = null)
        assertEquals(setOf("fav"), r.cleaned)
        assertFalse(r.removedAny)
    }

    @Test fun allowedAppIdsNullForFailOpenCases() {
        // null collections, empty selection, and selection matching no known collection all show all.
        assertEquals(null, SteamCollectionFilter.allowedAppIds(setOf("fav"), collections = null))
        assertEquals(null, SteamCollectionFilter.allowedAppIds(emptySet(), all))
        assertEquals(null, SteamCollectionFilter.allowedAppIds(setOf("gone"), all))
    }

    @Test fun allowedAppIdsUnionsSelectedCollections() {
        assertEquals(setOf(440, 570), SteamCollectionFilter.allowedAppIds(setOf("fav"), all))
        assertEquals(setOf(440, 570, 730), SteamCollectionFilter.allowedAppIds(setOf("fav", "sht"), all))
    }
}
