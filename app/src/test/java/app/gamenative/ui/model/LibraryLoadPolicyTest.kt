package app.gamenative.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLoadPolicyTest {

    @Test
    fun loadingOnlyWhileNothingIsShown() {
        assertTrue(LibraryLoadPolicy.showLoading(shownItemCount = 0))
        assertFalse(LibraryLoadPolicy.showLoading(shownItemCount = 1))
        assertFalse(LibraryLoadPolicy.showLoading(shownItemCount = 240))
    }

    private fun slot(
        resolved: Boolean,
        freshFeatured: String? = null,
        freshRec: String? = null,
        cachedFeatured: String? = null,
        cachedRec: String? = null,
    ) = LibraryLoadPolicy.heroSlot(resolved, freshFeatured, freshRec, cachedFeatured, cachedRec)

    @Test
    fun cachedSlotStandsInUntilTheFetchResolves() {
        assertEquals(null to "rec-yesterday", slot(resolved = false, cachedRec = "rec-yesterday"))
        assertEquals("ad" to null, slot(resolved = false, cachedFeatured = "ad", cachedRec = "rec"))
        assertEquals(null to null, slot(resolved = false))
    }

    @Test
    fun freshPickReplacesTheCachedSlot() {
        assertEquals(null to "rec-today", slot(resolved = true, freshRec = "rec-today", cachedRec = "rec-yesterday"))
        assertEquals("ad" to null, slot(resolved = true, freshFeatured = "ad", freshRec = "rec", cachedRec = "rec-yesterday"))
        // A resolved fetch with nothing to show clears the slot rather than keeping a stale card.
        assertEquals(null to null, slot(resolved = true, cachedFeatured = "old-ad", cachedRec = "rec"))
    }

    @Test
    fun teaserDayRule() {
        fun teaser(featured: Boolean = false, show: Boolean = true, consent: Boolean = false, dismissed: Long = 0L, day: Long = 100L) =
            LibraryLoadPolicy.isTeaserDay(featured, show, consent, dismissed, day)
        assertTrue(teaser())
        assertFalse(teaser(featured = true))
        assertFalse(teaser(show = false))
        assertFalse(teaser(consent = true))
        assertTrue(teaser(dismissed = 100L, day = 100L))
        assertFalse(teaser(dismissed = 99L, day = 100L))
        assertTrue(teaser(dismissed = 99L, day = 102L))
    }
}
