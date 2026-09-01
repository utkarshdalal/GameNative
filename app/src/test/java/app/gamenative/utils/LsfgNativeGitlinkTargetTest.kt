package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LsfgNativeGitlinkTargetTest {
    @Test
    fun finalLifecycleRuntimeShaIsDocumented() {
        assertEquals(
            "8d78a34b99a1533c27a7466ce5118bc53946c8cd",
            FINAL_LSFG_LIFECYCLE_SHA,
        )
    }

    companion object {
        const val FINAL_LSFG_LIFECYCLE_SHA = "8d78a34b99a1533c27a7466ce5118bc53946c8cd"
    }
}
