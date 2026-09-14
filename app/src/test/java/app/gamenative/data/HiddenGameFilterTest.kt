package app.gamenative.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenGameFilterTest {
    private val hiddenSteamIds = setOf(440, 570)

    @Test
    fun steamHiddenGameIsHiddenByDefault() {
        assertFalse(
            HiddenGameFilter.passesSteam(440, hiddenSteamIds, showHiddenByDefault = false, hiddenCollectionSelected = false),
        )
    }

    @Test
    fun steamHiddenGameIsShownWhenSettingOn() {
        assertTrue(
            HiddenGameFilter.passesSteam(440, hiddenSteamIds, showHiddenByDefault = true, hiddenCollectionSelected = false),
        )
    }

    @Test
    fun steamHiddenGameIsShownWhenHiddenCollectionSelected() {
        assertTrue(
            HiddenGameFilter.passesSteam(440, hiddenSteamIds, showHiddenByDefault = false, hiddenCollectionSelected = true),
        )
    }

    @Test
    fun steamHiddenGameIsShownWhenHiddenPlusAnotherCollectionSelected() {
        // Only the Hidden-collection flag matters; other selected collections do not override it.
        assertTrue(
            HiddenGameFilter.passesSteam(440, hiddenSteamIds, showHiddenByDefault = false, hiddenCollectionSelected = true),
        )
    }

    @Test
    fun steamHiddenGameIsHiddenWhenOnlyAnotherCollectionSelected() {
        assertFalse(
            HiddenGameFilter.passesSteam(440, hiddenSteamIds, showHiddenByDefault = false, hiddenCollectionSelected = false),
        )
    }

    @Test
    fun steamEmptyHiddenSetShowsAll() {
        assertTrue(
            HiddenGameFilter.passesSteam(440, emptySet(), showHiddenByDefault = false, hiddenCollectionSelected = false),
        )
    }

    @Test
    fun steamNonHiddenGameIsAlwaysShown() {
        assertTrue(
            HiddenGameFilter.passesSteam(999, hiddenSteamIds, showHiddenByDefault = false, hiddenCollectionSelected = false),
        )
    }

    @Test
    fun gogHiddenGameIsHiddenByDefault() {
        assertFalse(HiddenGameFilter.passesGog(isHidden = true, showHiddenByDefault = false))
    }

    @Test
    fun gogHiddenGameIsShownWhenSettingOn() {
        assertTrue(HiddenGameFilter.passesGog(isHidden = true, showHiddenByDefault = true))
    }

    @Test
    fun gogNonHiddenGameIsAlwaysShown() {
        assertTrue(HiddenGameFilter.passesGog(isHidden = false, showHiddenByDefault = false))
    }

    @Test
    fun gogUnflaggedRowsFailOpen() {
        // Rows default to not hidden before the first hidden-metadata refresh.
        assertTrue(HiddenGameFilter.passesGog(isHidden = false, showHiddenByDefault = false))
    }
}
