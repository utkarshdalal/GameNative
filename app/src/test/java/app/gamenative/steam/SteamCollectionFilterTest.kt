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

    @Test fun visibleCollectionCountsKeepHiddenCollectionFullButExcludeHiddenElsewhere() {
        val hidden = SteamCollection(SteamCollection.ID_HIDDEN, "Hidden", setOf(440, 570))
        val favorites = SteamCollection("fav", "Favorites", setOf(440, 570, 730))

        val counts = SteamCollectionFilter.visibleCollectionCounts(
            collections = listOf(hidden, favorites),
            visibleAppIds = listOf(440, 730), // 570 is hidden and filtered out by default
            preHiddenAppIds = listOf(440, 570, 730),
        )

        assertEquals(2, counts[SteamCollection.ID_HIDDEN]) // full count, includes the hidden game
        assertEquals(2, counts["fav"]) // excludes the hidden game, so the badge matches the list
    }

    @Test fun visibleCollectionCountsAreEmptyWhenCollectionsNotLoaded() {
        assertEquals(
            emptyMap<String, Int>(),
            SteamCollectionFilter.visibleCollectionCounts(null, listOf(440), listOf(440)),
        )
    }

    @Test fun passesAllRequiresMembershipInEveryActiveGroup() {
        assertTrue(SteamCollectionFilter.passesAll(570, setOf(440, 570), setOf(570, 730)))
        assertFalse(SteamCollectionFilter.passesAll(440, setOf(440, 570), setOf(570, 730)))
    }

    @Test fun passesAllIgnoresInactiveGroups() {
        assertTrue(SteamCollectionFilter.passesAll(440, setOf(440, 570), null))
        assertTrue(SteamCollectionFilter.passesAll(999, null, null))
    }
}
