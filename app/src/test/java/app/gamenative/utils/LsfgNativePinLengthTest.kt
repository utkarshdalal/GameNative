package app.gamenative.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class LsfgNativePinLengthTest {
    @Test
    fun pinIsFortyHexCharacters() {
        assertEquals(40, LsfgNativeGitlinkTargetTest.FINAL_LSFG_LIFECYCLE_SHA.length)
    }
}
