package app.gamenative.utils

import junit.framework.TestCase.assertEquals
import org.junit.Test

class StringUtilsTest {

    @Test
    fun unaccentTest() {
        assertEquals("Bo: Path of the Teal Lotus", "Bō: Path of the Teal Lotus".unaccent())
        assertEquals("fiaAaAaAaAaAaAcCeEeEeEeEiIiIiIiInNoOoOoOoOoOuUuUuUuU", "ﬁáÁàÀâÂäÄãÃåÅçÇéÉèÈêÊëËíÍìÌîÎïÏñÑóÓòÒôÔöÖõÕúÚùÙûÛüÜ".unaccent())
    }
}
