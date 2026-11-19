package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SteamUtilsVdfEscapeTest {

    @Test
    fun escapesBackslashesInCheckGuidPaths() {
        // Steam writes this path as "Binaries\\Win32\\XComGame.exe" in appmanifest_200510.acf.
        assertEquals("Binaries\\\\Win32\\\\XComGame.exe", SteamUtils.escapeString("Binaries\\Win32\\XComGame.exe"))
        assertEquals("XEW\\\\Binaries\\\\Win32\\\\XComEW.exe", SteamUtils.escapeString("XEW\\Binaries\\Win32\\XComEW.exe"))
    }

    @Test
    fun leavesRootLevelExecutablesUnchanged() {
        assertEquals("CivilizationV.exe", SteamUtils.escapeString("CivilizationV.exe"))
    }

    @Test
    fun escapesBackslashBeforeQuote() {
        // a\"b must become a\\\"b: the backslash is escaped once and the quote once, not twice.
        assertEquals("a\\\\\\\"b", SteamUtils.escapeString("a\\\"b"))
    }

    @Test
    fun escapesQuotesAndLineBreaks() {
        assertEquals("say \\\"hi\\\"", SteamUtils.escapeString("say \"hi\""))
        assertEquals("a\\nb\\rc", SteamUtils.escapeString("a\nb\rc"))
    }

    @Test
    fun nullBecomesEmpty() {
        assertEquals("", SteamUtils.escapeString(null))
    }
}
