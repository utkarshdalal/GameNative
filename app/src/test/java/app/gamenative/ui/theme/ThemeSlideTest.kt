package app.gamenative.ui.theme

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSlideTest {

    private val second = 1_000_000_000L

    /** Where the element's left edge sits along the stretched gradient, 0 = its start, 1 = its end. */
    private fun windowStart(size: Size, phase: Float): Float = -slideOrigin(size, phase).x / (size.width * 3f)

    private fun phases(seconds: Float, from: Long, steps: Int): List<Float> {
        val pass = slideNanos(seconds)
        return (0..steps).map { slidePhase(from + 2 * pass * it / steps, pass) }
    }

    @Test
    fun aSmallSwitchPansAcrossMostOfItsGradientEachPeriod() {
        val track = Size(156f, 96f)
        val seen = phases(4f, 123_456_789_000L, 80).map { windowStart(track, it) }
        val travel = seen.max() - seen.min()
        assertTrue("gradient travel $travel of its length", travel > 0.6f)
        assertTrue("offset ${travel * track.width * 3f}px on a ${track.width}px track", travel * 3f > 1.5f)
    }

    @Test
    fun theOffsetMovesVisiblyWithinAQuarterSecond() {
        val track = Size(156f, 96f)
        val pass = slideNanos(4f)
        val t = 9_000_000_000_000L + pass / 2
        val a = slideOrigin(track, slidePhase(t, pass)).x
        val b = slideOrigin(track, slidePhase(t + second / 4, pass)).x
        assertTrue("moved ${kotlin.math.abs(a - b)}px", kotlin.math.abs(a - b) > 10f)
    }

    @Test
    fun aWideElementStillPansAtTwentySeconds() {
        val bar = Size(1800f, 120f)
        val seen = phases(20f, 0L, 200).map { slideOrigin(bar, it).x }
        assertTrue(seen.max() - seen.min() > bar.width)
        val pass = slideNanos(20f)
        val t = pass / 2
        val step = kotlin.math.abs(slideOrigin(bar, slidePhase(t, pass)).x - slideOrigin(bar, slidePhase(t + second, pass)).x)
        assertTrue("moved ${step}px in one second", step > bar.width * 0.05f)
    }

    @Test
    fun phaseGoesThereAndBackAndStaysInRange() {
        val pass = slideNanos(4f)
        assertEquals(0f, slidePhase(0L, pass), 1e-6f)
        assertEquals(1f, slidePhase(pass, pass), 1e-6f)
        assertEquals(0f, slidePhase(2 * pass, pass), 1e-6f)
        for (p in phases(4f, -7_000_000_000L, 97)) assertTrue(p in 0f..1f)
    }

    @Test
    fun theStretchedAreaAlwaysCoversTheElement() {
        val size = Size(200f, 50f)
        for (p in listOf(0f, 0.3f, 1f)) {
            val o = slideOrigin(size, p)
            assertTrue(o.x <= 0f && o.y <= 0f)
            assertTrue(o.x + size.width * 3f >= size.width - 0.01f)
            assertTrue(o.y + size.height * 3f >= size.height - 0.01f)
        }
    }
}
