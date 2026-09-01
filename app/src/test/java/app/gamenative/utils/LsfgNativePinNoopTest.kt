package app.gamenative.utils

import org.junit.Assert.assertNotNull
import org.junit.Test

class LsfgNativePinNoopTest {
    @Test
    fun lifecyclePinConstantIsAvailable() {
        assertNotNull(LsfgNativeGitlinkTargetTest.FINAL_LSFG_LIFECYCLE_SHA)
    }
}
