package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgNativePinConsistencyTest {
    @Test
    fun lifecyclePinConstantIsStable() {
        assertEquals(40, LsfgNativeGitlinkTargetTest.FINAL_LSFG_LIFECYCLE_SHA.length)
    }
}
