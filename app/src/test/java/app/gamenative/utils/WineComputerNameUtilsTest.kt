package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineComputerNameUtilsTest {
    @Test
    fun androidIdOf16HexCharactersGivesExactly15Characters() {
        val name = WineComputerNameUtils.fromAndroidId("0123456789abcdef")

        assertEquals("GN_0123456789ab", name)
        assertEquals(15, name.length)
    }

    @Test
    fun shortAndroidIdIsKeptAsIs() {
        assertEquals("GN_abc", WineComputerNameUtils.fromAndroidId("abc"))
    }

    @Test
    fun nullOrEmptyAndroidIdFallsBackToFixedName() {
        assertEquals("GN_UNKNOWN0000", WineComputerNameUtils.fromAndroidId(null))
        assertEquals("GN_UNKNOWN0000", WineComputerNameUtils.fromAndroidId(""))
    }

    @Test
    fun sameAndroidIdAlwaysGivesTheSameName() {
        assertEquals(
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1234"),
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1234"),
        )
    }

    @Test
    fun nameNeverExceedsTheNetBiosLimit() {
        assertTrue(WineComputerNameUtils.fromAndroidId("x".repeat(100)).length <= 15)
    }
}
