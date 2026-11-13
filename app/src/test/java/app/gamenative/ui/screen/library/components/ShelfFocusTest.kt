package app.gamenative.ui.screen.library.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfFocusTest {
    private fun leads(vararg recommended: Boolean) = shelfLeadsWithRecommendation(recommended.size) { recommended[it] }

    @Test
    fun leadsOnlyWithOneRecommendationBeforeAGame() {
        assertTrue(leads(true, false, false))
        assertFalse(leads(false, false))
        assertFalse(leads(true))
        assertFalse(leads(true, true, false))
        assertFalse(leads())
    }

    @Test
    fun opensOnTheGameAfterALeadingRecommendation() {
        assertEquals(1, shelfHomeIndex(true))
        assertEquals(0, shelfHomeIndex(false))
    }

    @Test
    fun keepsTheRowAtItsStartForTheGameAfterTheRecommendation() {
        assertEquals(0, shelfScrollAnchor(0, true))
        assertEquals(0, shelfScrollAnchor(1, true))
        assertEquals(2, shelfScrollAnchor(2, true))
        assertEquals(1, shelfScrollAnchor(1, false))
        assertEquals(5, shelfScrollAnchor(5, false))
    }

    @Test
    fun currentGameAtTheRowStartFollowsFocus() {
        assertEquals(1, shelfCurrentIndex(startIndex = 0, focusIndex = 1, leadsWithRecommendation = true))
        assertEquals(0, shelfCurrentIndex(startIndex = 0, focusIndex = 0, leadsWithRecommendation = true))
        assertEquals(1, shelfCurrentIndex(startIndex = 0, focusIndex = null, leadsWithRecommendation = true))
        assertEquals(1, shelfCurrentIndex(startIndex = 0, focusIndex = 6, leadsWithRecommendation = true))
        assertEquals(3, shelfCurrentIndex(startIndex = 3, focusIndex = 0, leadsWithRecommendation = true))
        assertEquals(0, shelfCurrentIndex(startIndex = 0, focusIndex = 1, leadsWithRecommendation = false))
    }
}
