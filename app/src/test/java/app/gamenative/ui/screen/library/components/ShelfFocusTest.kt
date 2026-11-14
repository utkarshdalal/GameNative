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

    private fun pose(index: Int, tiltAfter: Float? = null, turnAfter: Float? = null, flat: Boolean = false) =
        shelfCoverPose(index, focusIndex = 2, tilt = -5f, tiltAfter = tiltAfter, turn = 10f, turnAfter = turnAfter, focusedFlat = flat)

    @Test
    fun coversBeforeTheFocusedOneTakeTheBaseValues() {
        assertEquals(ShelfCoverPose(-5f, 10f), pose(0, tiltAfter = 5f, turnAfter = -10f))
    }

    @Test
    fun coversAfterTheFocusedOneTakeTheAfterValuesElseTheBaseOnes() {
        assertEquals(ShelfCoverPose(5f, -10f), pose(3, tiltAfter = 5f, turnAfter = -10f))
        assertEquals(ShelfCoverPose(-5f, 10f), pose(3))
    }

    @Test
    fun focusedCoverIsFlatOnlyWhenAsked() {
        assertEquals(ShelfCoverPose(0f, 0f), pose(2, tiltAfter = 5f, flat = true))
        assertEquals(ShelfCoverPose(-5f, 10f), pose(2, tiltAfter = 5f))
    }

    @Test
    fun centersACoverInTheRow() {
        // a 1000 px row with 24 px before its content: a 200 px cover starts 376 px into the content
        assertEquals(376, shelfCenterSnapPosition(layoutSize = 1000, itemSize = 200, beforeContentPadding = 24))
        assertEquals(-376, shelfCenterScrollOffset(layoutSize = 1000, itemSize = 200, beforeContentPadding = 24))
        assertEquals(176, shelfCenterSnapPosition(layoutSize = 1000, itemSize = 600, beforeContentPadding = 24))
    }
}
