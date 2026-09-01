package app.gamenative.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgNativePinSmokeTest {
    @Test
    fun lifecyclePinLooksLikeCommitSha() {
        assertTrue(LsfgNativeGitlinkTargetTest.FINAL_LSFG_LIFECYCLE_SHA.matches(Regex("[0-9a-f]{40}")))
    }
}
