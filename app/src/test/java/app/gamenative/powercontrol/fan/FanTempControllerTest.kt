package app.gamenative.powercontrol.fan

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FanTempControllerTest {

    private val dt = 0.5

    private fun settle(controller: FanTempController, tempC: Double, seconds: Double): Int {
        var applied = 0
        repeat((seconds / dt).toInt()) { applied = controller.update(tempC, dt) }
        return applied
    }

    @Test
    fun `idles at the floor below the ramp start`() {
        val controller = FanTempController()
        assertEquals(20, settle(controller, 55.0, 60.0))
    }

    @Test
    fun `ramps before the target instead of waiting for it`() {
        val controller = FanTempController()
        val applied = settle(controller, 69.0, 60.0)
        assertTrue("expected the fan to be moving at 69 C, was $applied%", applied in 30..50)
    }

    @Test
    fun `duty rises monotonically with temperature`() {
        var previous = 0
        listOf(58.0, 64.0, 70.0, 76.0, 82.0).forEach { temp ->
            val applied = settle(FanTempController(), temp, 60.0)
            assertTrue("$temp C gave $applied%, not above the previous $previous%", applied >= previous)
            previous = applied
        }
    }

    @Test
    fun `holds one speed while the sensor jitters around a value`() {
        val controller = FanTempController()
        settle(controller, 77.0, 120.0)

        val jitter = listOf(76.0, 78.0, 77.0, 79.0, 76.0, 78.0, 77.0, 77.0, 79.0, 76.0)
        val speeds = mutableSetOf<Int>()
        repeat(6) { jitter.forEach { speeds.add(controller.update(it, dt)) } }

        assertTrue("fan hunted across $speeds while the sensor jittered by 3 C", speeds.size <= 2)
    }

    @Test
    fun `rises gradually rather than jumping to full speed`() {
        val controller = FanTempController()
        settle(controller, 60.0, 30.0)

        var previous = controller.appliedIntegerPercent
        var worstJump = 0
        repeat(40) {
            val applied = controller.update(88.0, dt)
            worstJump = maxOf(worstJump, abs(applied - previous))
            previous = applied
        }
        assertTrue("fan moved $worstJump%% in one 500 ms tick", worstJump <= 5)
    }

    @Test
    fun `snaps to full speed at the override temperature`() {
        val controller = FanTempController()
        settle(controller, 70.0, 30.0)
        assertEquals(100, controller.update(95.0, dt))
        assertTrue(controller.overrideEngaged)
    }

    @Test
    fun `reset returns the controller to the floor`() {
        val controller = FanTempController()
        settle(controller, 85.0, 60.0)
        controller.reset()
        assertEquals(20, controller.appliedIntegerPercent)
        assertEquals(null, controller.smoothedTempC)
    }
}
