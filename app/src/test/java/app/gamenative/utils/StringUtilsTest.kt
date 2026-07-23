package app.gamenative.utils

import junit.framework.TestCase.assertEquals
import org.junit.Test

class StringUtilsTest {

    @Test
    fun unaccentTest() {
        assertEquals("Bo: Path of the Teal Lotus", "Bō: Path of the Teal Lotus".unaccent())
        assertEquals("fiaAaAaAaAaAaAcCeEeEeEeEiIiIiIiInNoOoOoOoOoOuUuUuUuU", "ﬁáÁàÀâÂäÄãÃåÅçÇéÉèÈêÊëËíÍìÌîÎïÏñÑóÓòÒôÔöÖõÕúÚùÙûÛüÜ".unaccent())
    }

    @Test
    fun normalizeForComparisonTest() {
        assertEquals("thewitcher3", "The Witcher® 3".normalizeForComparison())
        assertEquals("thewitcher3", "THE WITCHER 3".normalizeForComparison())
        assertEquals("thewitcher3", "the-witcher-3".normalizeForComparison())
        assertEquals("bopathoftheteallotus", "Bō: Path of the Teal Lotus".normalizeForComparison())
        assertEquals("cyberpunk2077", "Cyberpunk 2077™".normalizeForComparison())
    }
}
