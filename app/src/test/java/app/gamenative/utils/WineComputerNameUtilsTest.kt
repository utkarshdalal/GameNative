package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineComputerNameUtilsTest {
    @Test
    fun androidIdGivesTheSha256BasedName() {
        // First 12 hex characters of SHA-256("0123456789abcdef") / SHA-256("abc").
        assertEquals("GN_9f9f5111f7b2", WineComputerNameUtils.fromAndroidId("0123456789abcdef"))
        assertEquals("GN_ba7816bf8f01", WineComputerNameUtils.fromAndroidId("abc"))
    }

    @Test
    fun nameIsExactly15Characters() {
        assertEquals(15, WineComputerNameUtils.fromAndroidId("0123456789abcdef").length)
        assertEquals(15, WineComputerNameUtils.fromAndroidId("x".repeat(100)).length)
    }

    @Test
    fun rawAndroidIdCharactersAreNotInTheName() {
        val id = "0123456789abcdef"
        val name = WineComputerNameUtils.fromAndroidId(id)

        assertFalse(name.contains(id.take(8)))
        assertTrue(name.startsWith("GN_"))
    }

    @Test
    fun nullOrEmptyAndroidIdFallsBackToTheSameFixedName() {
        // First 12 hex characters of SHA-256("UNKNOWN0000").
        assertEquals("GN_2166a28f06a6", WineComputerNameUtils.fromAndroidId(null))
        assertEquals("GN_2166a28f06a6", WineComputerNameUtils.fromAndroidId(""))
    }

    @Test
    fun sameAndroidIdAlwaysGivesTheSameNameAndDifferentIdsDiffer() {
        assertEquals(
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1234"),
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1234"),
        )
        assertNotEquals(
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1234"),
            WineComputerNameUtils.fromAndroidId("deadbeefcafe1235"),
        )
    }
}
