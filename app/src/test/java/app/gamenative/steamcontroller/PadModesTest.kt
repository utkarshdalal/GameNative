package app.gamenative.steamcontroller

import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Unit tests for trackpad source modes, driven by synthetic states (no device needed). */
@RunWith(RobolectricTestRunner::class)
class PadModesTest {

    private fun leftPadState(touch: Boolean, x: Int, y: Int, click: Boolean = false): TritonState {
        val s = TritonState()
        var b = 0
        if (touch) b = b or TritonProtocol.BTN_LPAD_TOUCH
        if (click) b = b or TritonProtocol.BTN_LPAD_CLICK
        s.buttons = b
        s.leftPadX = x; s.leftPadY = y
        return s
    }

    @Test
    fun `absolute mouse warps the cursor to the finger position`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.AbsoluteMouse()), haptics = null)
        // Center of the pad -> center of the screen.
        interp.apply(leftPadState(touch = true, x = 0, y = 0))
        assertEquals(0.5f, sink.lastAbsX, 0.02f)
        assertEquals(0.5f, sink.lastAbsY, 0.02f)
        // Finger top-right (pad +X right, +Y up) -> screen top-right (nx→1, ny→0).
        interp.apply(leftPadState(touch = true, x = 32767, y = 32767))
        assertTrue("nx near right", sink.lastAbsX > 0.95f)
        assertTrue("ny near top", sink.lastAbsY < 0.05f)
        // Not touched -> no absolute move emitted for that report.
        val before = sink.mouseAbsMoves
        interp.apply(leftPadState(touch = false, x = 0, y = 0))
        assertEquals(before, sink.mouseAbsMoves)
    }

    @Test
    fun `absolute mouse maps into a screen region`() {
        val sink = RecordingSink()
        // Right half of the screen only: center 0.75, width 0.5.
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.AbsoluteMouse(centerX = 0.75f, sizeX = 0.5f)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0)) // pad center -> region center = 0.75 across
        assertEquals(0.75f, sink.lastAbsX, 0.02f)
    }

    @Test
    fun `relative mouse H-V scale halves the vertical axis`() {
        // vertScale 0.5: a vertical drag emits half the delta of an equal horizontal drag.
        fun move(mode: PadMode.Mouse, toX: Int, toY: Int): Pair<Long, Long> {
            val sink = RecordingSink()
            val interp = ProfileInterpreter(sink, ScProfile(leftPad = mode), haptics = null)
            interp.apply(leftPadState(touch = true, x = 0, y = 0))       // anchor
            interp.apply(leftPadState(touch = true, x = toX, y = toY))   // move
            return sink.mouseDx to sink.mouseDy
        }
        val m = PadMode.Mouse(sensitivity = 1f, vertScale = 0.5f)
        val (hx, _) = move(m, 3000, 0)
        val (_, vy) = move(m, 0, 3000)
        assertEquals("vertical delta is half the horizontal", hx / 2, kotlin.math.abs(vy))
    }

    @Test
    fun `absolute mouse rotate output rotates the position vector`() {
        val sink = RecordingSink()
        // Full screen, rotated 90°: a finger at the right edge maps to bottom-center (right offset -> down offset).
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.AbsoluteMouse(rotation = 90f)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 32767, y = 0)) // far right, vertical center
        assertEquals(0.5f, sink.lastAbsX, 0.02f)
        assertEquals(1.0f, sink.lastAbsY, 0.02f)
    }

    @Test
    fun `mouse position warps the cursor on button press`() {
        val sink = RecordingSink()
        val prof = ScProfile(buttons = mapOf(TritonProtocol.BTN_A to Binding(ScOutput.MousePosition(0.5f, 0.25f))))
        val interp = ProfileInterpreter(sink, prof, haptics = null)
        interp.apply(TritonState().apply { buttons = TritonProtocol.BTN_A }) // press -> warp
        assertEquals(1, sink.mouseAbsMoves)
        assertEquals(0.5f, sink.lastAbsX, 0.01f)
        assertEquals(0.25f, sink.lastAbsY, 0.01f)
    }

    private fun leftStickState(x: Int, y: Int): TritonState =
        TritonState().apply { leftStickX = x; leftStickY = y }

    @Test
    fun `stick d-pad presses a direction on deflect and releases on recenter`() {
        val sink = RecordingSink()
        val prof = ScProfile(leftStick = StickMode.DPad(
            up = ScOutput.Key(XKeycode.KEY_W), down = ScOutput.Key(XKeycode.KEY_S),
            left = ScOutput.Key(XKeycode.KEY_A), right = ScOutput.Key(XKeycode.KEY_D)))
        val interp = ProfileInterpreter(sink, prof, haptics = null)
        // Full up deflection (+Y is up) -> W pressed once.
        interp.apply(leftStickState(0, 32767))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_W))
        // Recenter -> W released, no other direction fired.
        interp.apply(leftStickState(0, 0))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_W && !it.pressed })
        assertEquals(0, sink.keyPresses(XKeycode.KEY_A))
    }

    private fun dpad(layout: DpadLayout) = StickMode.DPad(
        up = ScOutput.Key(XKeycode.KEY_W), down = ScOutput.Key(XKeycode.KEY_S),
        left = ScOutput.Key(XKeycode.KEY_A), right = ScOutput.Key(XKeycode.KEY_D), layout = layout)

    @Test
    fun `dpad layout modes decide diagonal behavior`() {
        // A NE diagonal (up + slightly-more right) exercises each layout differently.
        val ne = leftStickState(24000, 20000) // +X right (D), +Y up (W); |X| > |Y| -> right dominant
        // 8-way overlap: both cardinals press.
        RecordingSink().let { s ->
            ProfileInterpreter(s, ScProfile(leftStick = dpad(DpadLayout.EIGHT_WAY)), haptics = null).apply(ne)
            assertEquals(1, s.keyPresses(XKeycode.KEY_W)); assertEquals(1, s.keyPresses(XKeycode.KEY_D))
        }
        // 4-way: only the dominant axis (right) presses.
        RecordingSink().let { s ->
            ProfileInterpreter(s, ScProfile(leftStick = dpad(DpadLayout.FOUR_WAY)), haptics = null).apply(ne)
            assertEquals(0, s.keyPresses(XKeycode.KEY_W)); assertEquals(1, s.keyPresses(XKeycode.KEY_D))
        }
        // Cross gate: a near-perfect diagonal falls in the dead band -> nothing presses.
        RecordingSink().let { s ->
            ProfileInterpreter(s, ScProfile(leftStick = dpad(DpadLayout.CROSS_GATE)), haptics = null)
                .apply(leftStickState(23000, 23000))
            assertEquals(0, s.keys.count { it.pressed })
        }
    }

    @Test
    fun `pad-as-joystick deflects the chosen stick while touched and recenters on lift`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.Joystick(Stick.RIGHT)), haptics = null)
        // Finger full-right on the pad -> right stick deflects +X; the left stick is untouched.
        interp.apply(leftPadState(touch = true, x = 32767, y = 0))
        assertTrue("right stick pushed right", sink.lastThumbRX > 0.8f)
        assertEquals("left stick untouched", 0f, sink.lastThumbLX, 1e-4f)
        // Lift -> the stick recenters (no residual deflection, unlike a relative-mouse pad).
        interp.apply(leftPadState(touch = false, x = 32767, y = 0))
        assertEquals(0f, sink.lastThumbRX, 1e-4f)
    }

    @Test
    fun `single-button pad fires on touch and releases on lift`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.SingleButton(ScOutput.Key(XKeycode.KEY_SPACE))), haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SPACE && it.pressed })
        assertEquals(0, sink.keys.count { it.key == XKeycode.KEY_SPACE && !it.pressed })
        interp.apply(leftPadState(touch = false, x = 0, y = 0))
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_SPACE && !it.pressed })
    }

    @Test
    fun `directional swipe pulses the flicked direction`() {
        val sink = RecordingSink()
        val prof = ScProfile(leftPad = PadMode.DirectionalSwipe(
            up = ScOutput.Key(XKeycode.KEY_W), down = ScOutput.Key(XKeycode.KEY_S),
            left = ScOutput.Key(XKeycode.KEY_A), right = ScOutput.Key(XKeycode.KEY_D), threshold = 8000,
        ))
        val interp = ProfileInterpreter(sink, prof, haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0))       // anchor
        interp.apply(leftPadState(touch = true, x = 20000, y = 0))   // flick right past threshold
        assertEquals(1, sink.keyPresses(XKeycode.KEY_D))
        interp.apply(leftPadState(touch = true, x = 20000, y = 20000)) // flick up (pad +Y up)
        assertEquals(1, sink.keyPresses(XKeycode.KEY_W))
    }

    // 2x2 grid: cells row-major, row 0 = BOTTOM, col 0 = LEFT.
    //   cell0 = bottom-left  -> F1
    //   cell1 = bottom-right -> F2
    //   cell2 = top-left     -> F3
    //   cell3 = top-right    -> F4
    private fun grid2x2Profile() = ScProfile(
        leftPad = PadMode.ButtonPadGrid(
            cols = 2, rows = 2,
            cells = listOf(
                ScOutput.Key(XKeycode.KEY_F1), ScOutput.Key(XKeycode.KEY_F2),
                ScOutput.Key(XKeycode.KEY_F3), ScOutput.Key(XKeycode.KEY_F4),
            ),
        ),
    )

    private fun touchCellThenRelease(interp: ProfileInterpreter, x: Int, y: Int) {
        interp.apply(leftPadState(touch = true, x = x, y = y))
        interp.apply(leftPadState(touch = false, x = x, y = y))
    }

    @Test
    fun `button pad grid fires the cell under the finger`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, grid2x2Profile(), haptics = null)

        touchCellThenRelease(interp, x = -30000, y = -30000) // bottom-left -> F1
        touchCellThenRelease(interp, x = 30000, y = -30000)  // bottom-right -> F2
        touchCellThenRelease(interp, x = -30000, y = 30000)  // top-left -> F3
        touchCellThenRelease(interp, x = 30000, y = 30000)   // top-right -> F4

        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F3))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F4))
    }

    @Test
    fun `grid releases a cell on lift and only one cell active at a time`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, grid2x2Profile(), haptics = null)

        interp.apply(leftPadState(touch = true, x = -30000, y = -30000)) // F1 down
        interp.apply(leftPadState(touch = false, x = -30000, y = -30000)) // F1 up

        // Equal numbers of down and up for F1, and no other key fired.
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })
        assertTrue("no F2 expected", sink.keyPresses(XKeycode.KEY_F2) == 0)
    }

    @Test
    fun `sliding across cells switches the active cell`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, grid2x2Profile(), haptics = null)

        // Touch bottom-left, slide to bottom-right without lifting, then release.
        interp.apply(leftPadState(touch = true, x = -30000, y = -30000)) // F1 down
        interp.apply(leftPadState(touch = true, x = 30000, y = -30000))  // -> F1 up, F2 down
        interp.apply(leftPadState(touch = false, x = 30000, y = -30000)) // F2 up

        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))
        // F1 should have been released when sliding off it.
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })
    }

    // up=F1, down=F2, left=F3, right=F4
    private fun dpadProfile() = ScProfile(
        leftPad = PadMode.DPad(
            up = ScOutput.Key(XKeycode.KEY_F1), down = ScOutput.Key(XKeycode.KEY_F2),
            left = ScOutput.Key(XKeycode.KEY_F3), right = ScOutput.Key(XKeycode.KEY_F4),
        ),
    )

    @Test
    fun `pad d-pad presses the direction held`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, dpadProfile(), haptics = null)
        fun dir(x: Int, y: Int) {
            interp.apply(leftPadState(touch = true, x = x, y = y))
            interp.apply(leftPadState(touch = false, x = 0, y = 0))
        }
        dir(0, 30000)   // up -> F1
        dir(0, -30000)  // down -> F2
        dir(-30000, 0)  // left -> F3
        dir(30000, 0)   // right -> F4
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F3))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F4))
    }

    @Test
    fun `pad d-pad fires two outputs on a diagonal`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, dpadProfile(), haptics = null)
        interp.apply(leftPadState(touch = true, x = 30000, y = 30000)) // up-right
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1)) // up
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F4)) // right
        assertEquals(0, sink.keyPresses(XKeycode.KEY_F2)) // not down
    }

    @Test
    fun `pad mouse ignores resting-finger jitter below the floor`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.Mouse(sensitivity = 1f / 70f, invertY = false, jitterFloor = 12)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 1000, y = 1000)) // activate
        // small zero-mean wiggle, each delta < 12 raw units
        interp.apply(leftPadState(touch = true, x = 1006, y = 998))
        interp.apply(leftPadState(touch = true, x = 996, y = 1005))
        interp.apply(leftPadState(touch = true, x = 1004, y = 999))
        assertEquals("resting jitter should produce no cursor motion", 0, sink.mouseMoves)
    }

    @Test
    fun `pad mouse moves on a real drag above the floor`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.Mouse(sensitivity = 1f / 70f, invertY = false, jitterFloor = 12)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0))     // activate
        interp.apply(leftPadState(touch = true, x = 7000, y = 0))  // big drag right -> 100px
        assertTrue(sink.mouseMoves > 0)
        assertTrue("moved right", sink.mouseDx > 0)
    }

    @Test
    fun `pad mouse accumulates sub-pixel motion instead of truncating it away`() {
        val sink = RecordingSink()
        // low sensitivity: each step is <1px but above the jitter floor; old truncation would drop it forever.
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.Mouse(sensitivity = 0.02f, invertY = false, jitterFloor = 12)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0))
        repeat(5) { i -> interp.apply(leftPadState(touch = true, x = 30 * (i + 1), y = 0)) } // 30 units/step * 0.02 = 0.6px
        assertTrue("sub-pixel motion should accumulate into real movement", sink.mouseDx > 0)
    }

    @Test
    fun `scroll wheel emits one click per step of travel`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, ScProfile(leftPad = PadMode.ScrollWheel(step = 6000)), haptics = null)
        interp.apply(leftPadState(touch = true, x = 0, y = 0))      // start (no emit)
        interp.apply(leftPadState(touch = true, x = 0, y = 6000))   // +1 up
        interp.apply(leftPadState(touch = true, x = 0, y = 12000))  // +1 up
        interp.apply(leftPadState(touch = true, x = 0, y = 18000))  // +1 up
        interp.apply(leftPadState(touch = true, x = 0, y = 12000))  // -1 down
        assertEquals(3, sink.mouseButtonPresses(Pointer.Button.BUTTON_SCROLL_UP))
        assertEquals(1, sink.mouseButtonPresses(Pointer.Button.BUTTON_SCROLL_DOWN))
    }
}
