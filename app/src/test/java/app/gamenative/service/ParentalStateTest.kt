package app.gamenative.service

import app.gamenative.service.SteamService.ParentalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ParentalStateTest {

    @Test
    fun restrictedUsesLiveAllowlistOverCache() {
        val allowed = ParentalState.Restricted(setOf(1, 2)).resolveAllowedAppIds { setOf(99) }
        assertEquals(setOf(1, 2), allowed)
    }

    @Test
    fun restrictedEmptyAllowlistHidesEverything() {
        val allowed = ParentalState.Restricted(emptySet()).resolveAllowedAppIds { null }
        assertEquals(emptySet<Int>(), allowed)
    }

    @Test
    fun unrestrictedIgnoresStaleCache() {
        assertNull(ParentalState.Unrestricted.resolveAllowedAppIds { setOf(1, 2) })
    }

    @Test
    fun loadingFallsBackToCache() {
        assertEquals(setOf(5), ParentalState.Loading.resolveAllowedAppIds { setOf(5) })
    }

    @Test
    fun loadingWithoutCacheShowsEverything() {
        assertNull(ParentalState.Loading.resolveAllowedAppIds { null })
    }

    @Test
    fun cacheOnlyReadWhileLoading() {
        var read = false
        val cached = {
            read = true
            null
        }
        ParentalState.Restricted(setOf(1)).resolveAllowedAppIds(cached)
        ParentalState.Unrestricted.resolveAllowedAppIds(cached)
        assertFalse(read)
    }
}
