package app.gamenative

import app.gamenative.utils.FakeDataStore
import app.gamenative.utils.awaitUntil
import app.gamenative.utils.installFakePrefManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrefManagerParentalCacheTest {

    private val child = 76561198000000001L
    private val parent = 76561198000000002L

    // setters write async; the cache setter reads the current steamid64 synchronously
    private fun loginAs(steamId64: Long) {
        PrefManager.steamUserSteamId64 = steamId64
        awaitUntil { PrefManager.steamUserSteamId64 == steamId64 }
    }

    private fun cache(ids: Set<Int>?) {
        PrefManager.parentalAllowedAppIds = ids
        awaitUntil { PrefManager.parentalAllowedAppIds == ids }
    }

    @Test
    fun unsetCacheReadsNull() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            assertNull(PrefManager.parentalAllowedAppIds)
        }
    }

    @Test
    fun cacheRoundTripsForOwningAccount() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            cache(setOf(10, 20, 30))
            assertEquals(setOf(10, 20, 30), PrefManager.parentalAllowedAppIds)
        }
    }

    @Test
    fun emptyAllowlistStaysDistinctFromUnset() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            cache(emptySet())
            assertEquals(emptySet<Int>(), PrefManager.parentalAllowedAppIds)
        }
    }

    @Test
    fun differentAccountDoesNotInheritCache() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            cache(setOf(10, 20))
            loginAs(parent)
            assertNull(PrefManager.parentalAllowedAppIds)
        }
    }

    @Test
    fun loggedOutAccountDoesNotInheritCache() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            cache(setOf(10, 20))
            loginAs(0L)
            assertNull(PrefManager.parentalAllowedAppIds)
        }
    }

    @Test
    fun clearingCacheReadsNull() {
        installFakePrefManager(FakeDataStore()).use {
            loginAs(child)
            cache(setOf(10))
            cache(null)
            assertNull(PrefManager.parentalAllowedAppIds)
        }
    }
}
