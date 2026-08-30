package com.winlator.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerFrameRatingRetargetContractTest {
    private fun source(): String {
        val candidates = listOf(
            File("src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt"),
            File("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Unable to locate XServerScreen.kt from test working directory")
    }

    @Test
    fun trackedWindowChangeResetsOnlySamplingEpoch() {
        val source = source()
        assertTrue(
            "FrameRating retarget must clear inherited short-term timing/frame count",
            source.contains("rating.resetSamplingEpoch()"),
        )
        assertFalse(
            "Window retarget must not erase accumulated session statistics",
            source.contains("rating.reset()\n                                rating.visibility = View.VISIBLE"),
        )
    }
}
