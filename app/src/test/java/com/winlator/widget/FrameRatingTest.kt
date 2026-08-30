package com.winlator.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRatingTest {
    @Test
    fun plausibleFps_rejectsEventStormSamplesBeforeTheyReachPowerTuning() {
        assertTrue(FrameRating.isPlausibleFps(0f))
        assertTrue(FrameRating.isPlausibleFps(60f))
        assertTrue(FrameRating.isPlausibleFps(240f))
        assertTrue(FrameRating.isPlausibleFps(1000f))

        assertFalse(FrameRating.isPlausibleFps(1000.1f))
        assertFalse(FrameRating.isPlausibleFps(Float.NaN))
        assertFalse(FrameRating.isPlausibleFps(Float.POSITIVE_INFINITY))
    }
}
