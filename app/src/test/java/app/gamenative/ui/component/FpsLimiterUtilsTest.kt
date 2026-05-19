package app.gamenative.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FpsLimiterUtilsTest {

    // ── fpsLimiterSteps ──────────────────────────────────────────────────────

    @Test
    fun `steps for 60Hz are multiples of 5 from 5 to 60`() {
        assertEquals((5..60 step 5).toList(), fpsLimiterSteps(60))
    }

    @Test
    fun `steps for 90Hz are multiples of 5 from 5 to 90`() {
        assertEquals((5..90 step 5).toList(), fpsLimiterSteps(90))
    }

    @Test
    fun `steps for 120Hz are multiples of 5 from 5 to 120`() {
        assertEquals((5..120 step 5).toList(), fpsLimiterSteps(120))
    }

    @Test
    fun `steps for 144Hz have clean multiples then raw max appended`() {
        val steps = fpsLimiterSteps(144)
        assertEquals(5, steps.first())
        assertEquals(140, steps[steps.size - 2])
        assertEquals(144, steps.last())
        // no duplicates
        assertEquals(steps.size, steps.distinct().size)
    }

    @Test
    fun `steps for exact multiple of 5 do not duplicate the max`() {
        val steps = fpsLimiterSteps(60)
        assertEquals(60, steps.last())
        assertEquals(steps.size, steps.distinct().size)
    }

    @Test
    fun `sanitizes max below 5 to give a single step of 5`() {
        assertEquals(listOf(5), fpsLimiterSteps(3))
    }

    @Test
    fun `all steps are in ascending order`() {
        listOf(60, 90, 120, 144).forEach { max ->
            val steps = fpsLimiterSteps(max)
            for (i in 1 until steps.size) {
                assertTrue("steps not ascending at index $i for max=$max", steps[i] > steps[i - 1])
            }
        }
    }

    // ── fpsLimiterCurrentIndex ────────────────────────────────────────────────

    @Test
    fun `currentIndex for exact step match returns that index`() {
        val steps = fpsLimiterSteps(60)
        assertEquals(steps.indexOf(30), fpsLimiterCurrentIndex(steps, 30))
    }

    @Test
    fun `currentIndex floors to nearest step below for non-step value`() {
        val steps = fpsLimiterSteps(60) // …40, 45, 50…
        // 47 is between 45 and 50 — floor to 45
        assertEquals(steps.indexOf(45), fpsLimiterCurrentIndex(steps, 47))
    }

    @Test
    fun `currentIndex clamps to 0 for value below minimum step`() {
        val steps = fpsLimiterSteps(60)
        assertEquals(0, fpsLimiterCurrentIndex(steps, 1))
    }

    @Test
    fun `currentIndex returns lastIndex for value at max`() {
        val steps = fpsLimiterSteps(60)
        assertEquals(steps.lastIndex, fpsLimiterCurrentIndex(steps, 60))
    }

    @Test
    fun `currentIndex floors correctly for 144Hz non-multiple value`() {
        val steps = fpsLimiterSteps(144) // …135, 140, 144
        // 143 should floor to 140, not 144
        assertEquals(steps.indexOf(140), fpsLimiterCurrentIndex(steps, 143))
    }

    // ── nextFpsLimiterValue ───────────────────────────────────────────────────

    @Test
    fun `next from 30 is 35 with 60Hz max`() {
        assertEquals(35, nextFpsLimiterValue(30, 60))
    }

    @Test
    fun `next clamps at display max`() {
        assertEquals(60, nextFpsLimiterValue(60, 60))
    }

    @Test
    fun `next from non-step value floors then steps up`() {
        // 47 floors to 45, next is 50
        assertEquals(50, nextFpsLimiterValue(47, 60))
    }

    @Test
    fun `next from 140 on 144Hz display reaches 144`() {
        assertEquals(144, nextFpsLimiterValue(140, 144))
    }

    @Test
    fun `next clamps at 144 when already at 144Hz max`() {
        assertEquals(144, nextFpsLimiterValue(144, 144))
    }

    // ── previousFpsLimiterValue ───────────────────────────────────────────────

    @Test
    fun `prev from 30 is 25 with 60Hz max`() {
        assertEquals(25, previousFpsLimiterValue(30, 60))
    }

    @Test
    fun `prev clamps at 5`() {
        assertEquals(5, previousFpsLimiterValue(5, 60))
    }

    @Test
    fun `prev from non-step value floors to that step`() {
        // 47 floors to 45, prev is 45 (the floor itself, not one below)
        // fpsLimiterCurrentIndex(steps, 47) = indexOf(45)
        // prev = steps[(indexOf(45) - 1)] = 40
        assertEquals(40, previousFpsLimiterValue(47, 60))
    }

    @Test
    fun `prev from 144 on 144Hz display is 140`() {
        assertEquals(140, previousFpsLimiterValue(144, 144))
    }

    // ── fpsLimiterProgress ────────────────────────────────────────────────────

    @Test
    fun `progress at minimum step is 0`() {
        assertEquals(0f, fpsLimiterProgress(5, 60), 0.001f)
    }

    @Test
    fun `progress at display max is 1`() {
        assertEquals(1f, fpsLimiterProgress(60, 60), 0.001f)
    }

    @Test
    fun `progress at midpoint is approximately 0·5`() {
        // steps 5..60 step 5 → 12 steps, index 0..11; 30fps = index 5 → 5/11 ≈ 0.454
        val steps = fpsLimiterSteps(60)
        val idx = steps.indexOf(30).toFloat()
        val expected = idx / steps.lastIndex.toFloat()
        assertEquals(expected, fpsLimiterProgress(30, 60), 0.001f)
    }

    @Test
    fun `progress for 144Hz top step is 1`() {
        assertEquals(1f, fpsLimiterProgress(144, 144), 0.001f)
    }

    @Test
    fun `progress for non-step value uses floor index`() {
        // 47 → floor to 45, same progress as 45
        assertEquals(
            fpsLimiterProgress(45, 60),
            fpsLimiterProgress(47, 60),
            0.001f,
        )
    }
}
