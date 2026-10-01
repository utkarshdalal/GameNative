package app.gamenative.ui.screen.xserver

import android.os.Looper
import com.winlator.inputcontrols.Binding
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class MouseLookStepperTest {
    @Test
    fun `a low throttling rate steps less often but keeps the speed`() {
        var movedX = 0
        var steps = 0
        val throttling = InputThrottling().apply {
            enabled = true
            setRateHz(InputThrottling.MIN_RATE_HZ)
        }
        val stepper = MouseLookStepper(throttling) { dx, _ ->
            movedX += dx
            steps++
        }

        stepper.hold(Any(), Binding.MOUSE_MOVE_RIGHT, 1f, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(1000, MILLISECONDS)
        stepper.removeIf { true }

        // 5 Hz: a step right away, then one per interval, instead of one per display frame.
        assertTrue("stepped $steps times", steps in 4..7)
        // 600 px/s at full deflection; up to one 0.2 s step may still be pending. Capping each step instead of
        // each frame gap moved about half of this.
        assertTrue("moved $movedX px", movedX in 450..620)
    }
}
