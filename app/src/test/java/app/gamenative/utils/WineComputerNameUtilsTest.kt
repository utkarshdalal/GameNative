package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WineComputerNameUtilsTest {
    @Test
    fun androidIdGivesTheSha256BasedName() {
        // First 15 hex characters of SHA-256("0123456789abcdef") / SHA-256("abc").
        assertEquals("9f9f5111f7b27a7", WineComputerNameUtils.fromAndroidId("0123456789abcdef"))
        assertEquals("ba7816bf8f01cfe", WineComputerNameUtils.fromAndroidId("abc"))
    }

    @Test
    fun nameIsExactly15Characters() {
        assertEquals(15, WineComputerNameUtils.fromAndroidId("0123456789abcdef").length)
        assertEquals(15, WineComputerNameUtils.fromAndroidId("x".repeat(100)).length)
    }

    @Test
    fun nameIsPlainHexWithoutAGameNativePrefix() {
        val name = WineComputerNameUtils.fromAndroidId("0123456789abcdef")

        assertTrue(name.matches(Regex("[0-9a-f]{15}")))
        assertFalse(name.contains("GN", ignoreCase = true))
    }

    @Test
    fun rawAndroidIdCharactersAreNotInTheName() {
        val id = "0123456789abcdef"
        val name = WineComputerNameUtils.fromAndroidId(id)

        assertFalse(name.contains(id.take(8)))
    }

    @Test
    fun nullOrEmptyAndroidIdFallsBackToTheSameFixedName() {
        // First 15 hex characters of SHA-256("UNKNOWN0000").
        assertEquals("2166a28f06a6b79", WineComputerNameUtils.fromAndroidId(null))
        assertEquals("2166a28f06a6b79", WineComputerNameUtils.fromAndroidId(""))
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
