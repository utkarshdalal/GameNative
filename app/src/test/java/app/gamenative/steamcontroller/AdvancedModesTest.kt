package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for the step-7 stick modes (mouse / flickstick / response curve) and the OnRelease activator. */
@RunWith(RobolectricTestRunner::class)
class AdvancedModesTest {

    private fun stickState(lx: Int = 0, ly: Int = 0) = TritonState().apply { leftStickX = lx; leftStickY = ly }

    @Test
    fun `gyro pad-touch gate aims only while the pad is touched`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.RIGHT_PAD_TOUCH)), haptics = null)
        // Gyro rotating but pad NOT touched -> gated off, no aim.
        interp.apply(TritonState().apply { gyroZ = 500; gyroX = 200 })
        assertEquals(0, sink.mouseMoves)
        // Same rotation while the right pad is touched -> aim fires.
        interp.apply(TritonState().apply { gyroZ = 500; gyroX = 200; buttons = TritonProtocol.BTN_RPAD_TOUCH })
        assertTrue("gyro aims while pad touched", sink.mouseMoves > 0)
    }

    @Test
    fun `gyro joystick mode deflects the output stick`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Joystick(Stick.RIGHT, sensitivity = 0.001f, gate = GyroGate.ALWAYS)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 }) // yaw -> right stick X; natural default negates -> -0.5
        assertTrue("right stick X deflected by gyro (natural sign)", sink.minThumbRX < -0.4f)
    }

    @Test
    fun `gyro joystick power curve and output range shape the deflection`() {
        // Aggressive curve (0.5) raises a mid-range input; capped output max reduces the peak.
        fun peak(mode: GyroMode.Joystick): Float {
            val sink = RecordingSink()
            ProfileInterpreter(sink, ScProfile(gyro = mode), haptics = null).apply(TritonState().apply { gyroZ = 4000 })
            return kotlin.math.abs(sink.minThumbRX)
        }
        val linear = GyroMode.Joystick(Stick.RIGHT, sensitivity = 0.0001f, gate = GyroGate.ALWAYS) // small -> mid deflection
        val relaxed = peak(linear.copy(powerCurve = 4f))   // 4 = relaxed -> smaller at mid input
        val lin = peak(linear)
        assertTrue("relaxed curve reduces mid-range deflection", relaxed < lin)
        // Output max caps the magnitude.
        val capped = peak(GyroMode.Joystick(Stick.RIGHT, sensitivity = 1f, gate = GyroGate.ALWAYS, outputMax = 0.5f))
        assertTrue("output max caps deflection at ~0.5", capped <= 0.51f)
    }

    @Test
    fun `gyro camera style returns to center when rotation stops`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Joystick(Stick.RIGHT, sensitivity = 0.001f, gate = GyroGate.ALWAYS, deflection = false)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 })
        assertTrue("camera deflects while rotating", sink.lastThumbRX < -0.4f)
        interp.apply(TritonState().apply { gyroZ = 0 }) // rate-based: no rotation -> center
        assertEquals(0f, sink.lastThumbRX, 0.001f)
    }

    @Test
    fun `gyro deflection style holds position at rest and resets when the gate closes`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Joystick(Stick.RIGHT, sensitivity = 0.001f, gate = GyroGate.RIGHT_GRIP, deflection = true)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_RGRIP }) // integrate -> -0.5
        val held = sink.lastThumbRX
        assertTrue("deflection accumulates an angle", held < -0.4f)
        interp.apply(TritonState().apply { gyroZ = 0; buttons = TritonProtocol.BTN_RGRIP }) // no rotation -> HOLD
        assertEquals("holds position while gated open", held, sink.lastThumbRX, 0.001f)
        interp.apply(TritonState().apply { gyroZ = 0 }) // gate released -> ratchet reset to center
        assertEquals(0f, sink.lastThumbRX, 0.001f)
    }

    @Test
    fun `gyro toggle activation flips aim on each grip press edge`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.RIGHT_GRIP, activation = GyroActivation.TOGGLE)), haptics = null)
        val grip = TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_RGRIP }
        val noGrip = TritonState().apply { gyroZ = 500 }
        interp.apply(grip)   // press edge -> toggle ON -> aims
        val a = sink.mouseMoves; assertTrue("toggle on aims", a > 0)
        interp.apply(noGrip) // released but still ON -> keeps aiming (hands-free)
        val b = sink.mouseMoves; assertTrue("stays on after release", b > a)
        interp.apply(grip)   // press edge -> toggle OFF
        interp.apply(noGrip)
        assertEquals("no aim after toggle off", b, sink.mouseMoves)
    }

    private fun gyroDx(mode: GyroMode.Mouse, gz: Int, gx: Int = 0): Long {
        val sink = RecordingSink()
        ProfileInterpreter(sink, ScProfile(gyro = mode), haptics = null).apply(TritonState().apply { gyroZ = gz; gyroX = gx })
        return sink.mouseDx
    }

    @Test
    fun `gyro mouse H-V mixer rebalances the horizontal axis`() {
        val base = GyroMode.Mouse(sensitivity = 1f, gate = GyroGate.ALWAYS)
        val full = kotlin.math.abs(gyroDx(base, 1000))
        val mixed = kotlin.math.abs(gyroDx(base.copy(hvMixer = 0.5f), 1000)) // >0 reduces horizontal
        assertEquals("horizontal halved by +0.5 mixer", full / 2, mixed)
    }

    @Test
    fun `gyro speed deadzone and precision scale slow rotation`() {
        val base = GyroMode.Mouse(sensitivity = 1f, gate = GyroGate.ALWAYS)
        // Speed deadzone: below the threshold speed -> no output.
        assertEquals(0L, gyroDx(base.copy(speedDeadzone = 2000f), 1000))
        assertTrue("above deadzone aims", kotlin.math.abs(gyroDx(base.copy(speedDeadzone = 2000f), 3000)) > 0)
        // Precision: below precisionSpeed, sensitivity scales down proportionally (1000/2000 = half).
        assertEquals(kotlin.math.abs(gyroDx(base, 1000)) / 2, kotlin.math.abs(gyroDx(base.copy(precisionSpeed = 2000f), 1000)))
    }

    @Test
    fun `gyro acceleration increases sensitivity at high speed`() {
        val base = GyroMode.Mouse(sensitivity = 1f, gate = GyroGate.ALWAYS)
        val off = kotlin.math.abs(gyroDx(base, 8000))
        val agg = kotlin.math.abs(gyroDx(base.copy(accel = GyroAccel.AGGRESSIVE), 8000))
        assertTrue("aggressive accel turns further at speed", agg > off)
    }

    @Test
    fun `gyro gate can be any button (not just grips)`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.R4)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 })                              // R4 not held -> no aim
        assertEquals(0, sink.mouseMoves)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_R4 }) // R4 held -> aims
        assertTrue("gyro aims while R4 paddle held", sink.mouseMoves > 0)
    }

    @Test
    fun `gyro suppress activation aims only while the grip is NOT held`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.RIGHT_GRIP, activation = GyroActivation.SUPPRESS)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 })            // grip not held -> active
        val a = sink.mouseMoves; assertTrue("suppress: aims when released", a > 0)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_RGRIP }) // held -> suppressed
        assertEquals("suppress: no aim while held", a, sink.mouseMoves)
    }

    @Test
    fun `gyro mouse uses the natural direction and per-axis invert`() {
        val nat = RecordingSink()
        ProfileInterpreter(nat, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.ALWAYS)), haptics = null)
            .apply(TritonState().apply { gyroZ = 500 })  // yaw-right -> aim-right = NEGATIVE raw sign (natural)
        assertTrue("natural yaw -> dx < 0", nat.mouseDx < 0)
        val inv = RecordingSink()
        ProfileInterpreter(inv, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.ALWAYS, invertX = true)), haptics = null)
            .apply(TritonState().apply { gyroZ = 500 })
        assertTrue("invertX flips it back to dx > 0", inv.mouseDx > 0)
    }

    @Test
    fun `gyro stick-touch gate aims only while the stick is touched`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.LEFT_STICK_TOUCH)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 })
        assertEquals(0, sink.mouseMoves)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_LSTICK_TOUCH })
        assertTrue("gyro aims while stick touched", sink.mouseMoves > 0)
    }

    @Test
    fun `gyro any-touch gate aims when any surface is touched`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.ANY_TOUCH)), haptics = null)
        interp.apply(TritonState().apply { gyroZ = 500 })
        assertEquals("no aim when nothing touched", 0, sink.mouseMoves)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_RSTICK_TOUCH })
        assertTrue("aims on stick touch", sink.mouseMoves > 0)
        val n = sink.mouseMoves
        interp.apply(TritonState().apply { gyroZ = 500; buttons = TritonProtocol.BTN_LPAD_TOUCH })
        assertTrue("aims on pad touch too", sink.mouseMoves > n)
    }

    @Test
    fun `gyro all-touch gate aims only when every surface is touched`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(gyro = GyroMode.Mouse(sensitivity = 5f, gate = GyroGate.ALL_TOUCH)), haptics = null)
        val three = TritonProtocol.BTN_LPAD_TOUCH or TritonProtocol.BTN_RPAD_TOUCH or TritonProtocol.BTN_LSTICK_TOUCH
        interp.apply(TritonState().apply { gyroZ = 500; buttons = three })
        assertEquals("3 of 4 touched -> still gated off", 0, sink.mouseMoves)
        interp.apply(TritonState().apply { gyroZ = 500; buttons = three or TritonProtocol.BTN_RSTICK_TOUCH })
        assertTrue("all 4 touched -> aims", sink.mouseMoves > 0)
    }

    @Test
    fun `pad mouse-joystick moves the cursor by displacement from center`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.MouseJoystick(sensitivity = 20f, deadzone = 0.1f)), haptics = null)
        // finger at center while touched -> no motion (self-centering)
        interp.apply(TritonState().apply { buttons = TritonProtocol.BTN_LPAD_TOUCH; leftPadX = 0; leftPadY = 0 })
        assertEquals(0, sink.mouseMoves)
        // finger held far right -> cursor glides right, keeps moving each frame while held
        interp.apply(TritonState().apply { buttons = TritonProtocol.BTN_LPAD_TOUCH; leftPadX = 32000; leftPadY = 0 })
        assertTrue("cursor moved right", sink.mouseDx > 0)
        // lift -> stops
        val before = sink.mouseMoves
        interp.apply(TritonState())
        assertEquals("no motion after lift", before, sink.mouseMoves)
    }

    @Test
    fun `joystick_mouse drives the pointer from stick deflection`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.Mouse(sensitivity = 10f, deadzone = 0.1f)), haptics = null)
        interp.apply(stickState(lx = 32767, ly = 0)) // full right
        assertTrue("cursor moved right", sink.mouseDx > 0)
        assertEquals("no vertical for pure-X", 0L, sink.mouseDy)
    }

    @Test
    fun `joystick_mouse respects the deadzone`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.Mouse(sensitivity = 10f, deadzone = 0.5f)), haptics = null)
        interp.apply(stickState(lx = 3000, ly = 0)) // ~0.09, inside the 0.5 deadzone
        assertEquals(0, sink.mouseMoves)
    }

    @Test
    fun `flickstick maps horizontal deflection to yaw only`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = StickMode.FlickStick(sensitivity = 20f, deadzone = 0.2f)), haptics = null)
        interp.apply(stickState(lx = 32767, ly = 32767)) // pushed up-right; flickstick uses X only
        assertTrue(sink.mouseDx > 0)
        assertEquals(0L, sink.mouseDy)
    }

    @Test
    fun `aggressive response curve attenuates mid deflection vs linear`() {
        val lin = RecordingSink()
        ProfileInterpreter(lin, ScProfile(leftStick = StickMode.JoystickMove(Stick.LEFT, invertY = false, curve = ResponseCurve.LINEAR)), haptics = null)
            .apply(stickState(lx = 16000)) // ~half deflection
        val agg = RecordingSink()
        ProfileInterpreter(agg, ScProfile(leftStick = StickMode.JoystickMove(Stick.LEFT, invertY = false, curve = ResponseCurve.AGGRESSIVE)), haptics = null)
            .apply(stickState(lx = 16000))
        assertTrue("aggressive curve < linear at mid", agg.maxThumbLX in 0f..lin.maxThumbLX && agg.maxThumbLX < lin.maxThumbLX)
    }

    private fun buttonA(down: Boolean) = TritonState().apply { buttons = if (down) TritonProtocol.BTN_A else 0 }

    @Test
    fun `OnRelease activator fires on the release edge, not on press`() {
        val sink = RecordingSink()
        val profile = ScProfile(buttons = mapOf(TritonProtocol.BTN_A to Binding(ScOutput.Key(XKeycode.KEY_F1), Activator.OnRelease)))
        val interp = ProfileInterpreter(sink, profile, haptics = null)
        interp.apply(buttonA(true))  // press -> nothing yet
        assertEquals(0, sink.keyPresses(XKeycode.KEY_F1))
        interp.apply(buttonA(false)) // release -> pulse
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })
    }
}
