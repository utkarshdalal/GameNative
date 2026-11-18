package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VcRedistYearsTest {

    @Test
    fun `years map to vcrun with 2015 and later collapsed to 2022`() {
        assertEquals("vcrun2005", VcRedistYears.component(2005))
        assertEquals("vcrun2013", VcRedistYears.component(2013))
        assertEquals("vcrun2022", VcRedistYears.component(2015))
        assertEquals("vcrun2022", VcRedistYears.component(2019))
        assertNull(VcRedistYears.component(2003))
    }

    @Test
    fun `finds year toolset or version in text`() {
        assertEquals("vcrun2008", VcRedistYears.findIn("redist/2008/vcredist_x86.exe"))
        assertEquals("vcrun2010", VcRedistYears.findIn("redist/vc100/vcredist_x86.exe"))
        assertEquals("vcrun2022", VcRedistYears.findIn("redist/vc141/vc_redist.x64.exe"))
        assertEquals("vcrun2012", VcRedistYears.findIn("vcredist_x64_11.0.61030.exe"))
        assertNull(VcRedistYears.findIn("redist/vc_redist.x64.exe"))
    }
}
