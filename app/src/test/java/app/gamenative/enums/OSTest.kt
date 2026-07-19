package app.gamenative.enums

import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OSTest {

    @Test
    fun `from string parses android`() {
        assertEquals(EnumSet.of(OS.android), OS.from("android"))
    }

    @Test
    fun `from string parses a comma separated list including android`() {
        assertEquals(EnumSet.of(OS.windows, OS.android), OS.from("windows,android"))
    }

    @Test
    fun `from string falls back to none for an unknown value`() {
        assertEquals(EnumSet.of(OS.none), OS.from("some_unknown_os"))
    }

    @Test
    fun `from string returns none for a null or empty value`() {
        assertEquals(EnumSet.of(OS.none), OS.from(null))
        assertEquals(EnumSet.of(OS.none), OS.from(""))
    }

    // OS.from(Int) always contains `none` too, since none.code == 0 and any bitmask ANDed with 0
    // is 0 — a pre-existing quirk unrelated to the `android` bit — so these check membership
    // rather than exact set equality.
    @Test
    fun `code and from int round trip for android alone`() {
        val roundTripped = OS.from(OS.code(EnumSet.of(OS.android)))
        assertTrue(roundTripped.contains(OS.android))
        assertFalse(roundTripped.contains(OS.windows))
        assertFalse(roundTripped.contains(OS.macos))
        assertFalse(roundTripped.contains(OS.linux))
    }

    @Test
    fun `code and from int round trip for a combination including android`() {
        val osses = EnumSet.of(OS.windows, OS.linux, OS.android)
        val roundTripped = OS.from(OS.code(osses))
        assertTrue(roundTripped.containsAll(osses))
        assertFalse(roundTripped.contains(OS.macos))
    }

    @Test
    fun `android has a distinct bit from the other OS values`() {
        assertEquals(0x08, OS.android.code)
        val others = listOf(OS.none, OS.windows, OS.macos, OS.linux)
        others.forEach { assertEquals(0, it.code and OS.android.code) }
    }
}
