package app.gamenative.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgNativePinFormatTest {
    @Test
    fun pinUsesLowercaseHex() {
        assertTrue(LsfgNativeGitlinkTargetTest.FINAL_LSFG_LIFECYCLE_SHA.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
