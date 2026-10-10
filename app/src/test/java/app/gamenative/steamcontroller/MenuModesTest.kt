package app.gamenative.steamcontroller

import app.gamenative.utils.SteamControllerProfileImporter
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the radial/touch menu **selection logic** (build step 6, functional half — no overlay).
 * Driven by synthetic pad states. Radial = finger angle → slot; touch = grid cell → slot; commit pulses the
 * slot's binding. Also asserts the importer turns ToME4's menu groups into real modes (no longer dropped).
 */
@RunWith(RobolectricTestRunner::class)
class MenuModesTest {

    private fun leftPad(touch: Boolean, x: Int, y: Int, click: Boolean = false): TritonState {
        val s = TritonState()
        var b = 0
        if (touch) b = b or TritonProtocol.BTN_LPAD_TOUCH
        if (click) b = b or TritonProtocol.BTN_LPAD_CLICK
        s.buttons = b
        s.leftPadX = x; s.leftPadY = y
        return s
    }

    private fun slot(key: XKeycode) = MenuSlot(Binding(ScOutput.Key(key)))

    // Radial: slot 0 = up (12 o'clock), clockwise -> 1=right, 2=down, 3=left.
    private fun radial4() = ScProfile(
        leftPad = PadMode.RadialMenu(
            slots = listOf(slot(XKeycode.KEY_F1), slot(XKeycode.KEY_F2), slot(XKeycode.KEY_F3), slot(XKeycode.KEY_F4)),
        ),
    )

    @Test
    fun `radial menu fires the pointed slot on release`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, radial4(), haptics = null)
        fun point(x: Int, y: Int) {
            interp.apply(leftPad(touch = true, x = x, y = y)) // highlight
            interp.apply(leftPad(touch = false, x = 0, y = 0)) // release -> commit
        }
        point(0, 30000)    // up -> F1
        point(30000, 0)    // right -> F2
        point(0, -30000)   // down -> F3
        point(-30000, 0)   // left -> F4
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F3))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F4))
    }

    @Test
    fun `radial menu pulses (equal down and up) and centered touch fires nothing`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, radial4(), haptics = null)
        interp.apply(leftPad(touch = true, x = 0, y = 30000)) // point up
        interp.apply(leftPad(touch = false, x = 0, y = 0))    // commit F1
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_F1 && !it.pressed })

        // A touch that never leaves the center dead-zone selects nothing -> no fire on release.
        interp.apply(leftPad(touch = true, x = 0, y = 0))
        interp.apply(leftPad(touch = false, x = 0, y = 0))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1)) // unchanged
    }

    // Touch menu 2x2: slots row-major, row 0 = TOP. 0=TL 1=TR 2=BL 3=BR.
    private fun touch4() = ScProfile(
        leftPad = PadMode.TouchMenu(
            slots = listOf(slot(XKeycode.KEY_F1), slot(XKeycode.KEY_F2), slot(XKeycode.KEY_F3), slot(XKeycode.KEY_F4)),
            cols = 2, rows = 2,
        ),
    )

    @Test
    fun `touch menu fires the grid cell under the finger on release`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, touch4(), haptics = null)
        fun cell(x: Int, y: Int) {
            interp.apply(leftPad(touch = true, x = x, y = y))
            interp.apply(leftPad(touch = false, x = x, y = y))
        }
        cell(-30000, 30000)  // top-left -> F1
        cell(30000, 30000)   // top-right -> F2
        cell(-30000, -30000) // bottom-left -> F3
        cell(30000, -30000)  // bottom-right -> F4
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F2))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F3))
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F4))
    }

    @Test
    fun `touch menu with onClick commits on the click edge`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(
            sink,
            ScProfile(leftPad = PadMode.TouchMenu(listOf(slot(XKeycode.KEY_F1), slot(XKeycode.KEY_F2)), cols = 2, rows = 1, onClick = true)),
            haptics = null,
        )
        interp.apply(leftPad(touch = true, x = -30000, y = 0))             // highlight left (F1), no commit
        assertEquals(0, sink.keyPresses(XKeycode.KEY_F1))
        interp.apply(leftPad(touch = true, x = -30000, y = 0, click = true)) // click -> commit F1
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
    }

    @Test
    fun `release-style touch menu also commits on a click, without double-firing on release`() {
        // onClick=false (requires_click=0, the common case): clicking is still a valid commit (matches Steam) and
        // must fire exactly once even though the finger also lifts afterwards.
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, touch4(), haptics = null) // touch4() defaults onClick=false
        interp.apply(leftPad(touch = true, x = -30000, y = 30000))             // highlight TL (F1)
        interp.apply(leftPad(touch = true, x = -30000, y = 30000, click = true)) // click -> commit F1 immediately
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
        interp.apply(leftPad(touch = false, x = 0, y = 0))                     // release -> must NOT re-fire
        assertEquals(1, sink.keyPresses(XKeycode.KEY_F1))
    }

    // (Removed: the global menuCommit override tests — that override was folded away; commit style is now purely
    // per-menu via each menu's own onClick setting.)

    private fun stickState(lx: Int, ly: Int, l3: Boolean = false, touch: Boolean = false) = TritonState().apply {
        leftStickX = lx; leftStickY = ly
        var b = 0
        if (l3) b = b or TritonProtocol.BTN_L3
        if (touch) b = b or TritonProtocol.BTN_LSTICK_TOUCH
        buttons = b
    }

    @Test
    fun `stick radial HUD appears on thumb-touch before deflecting`() {
        val overlay = CapturingMenuOverlay()
        val interp = ProfileInterpreter(RecordingSink(), stickRadialHold(), haptics = null, menuOverlay = overlay)
        // Thumb resting on the (centered) stick: HUD shows with nothing highlighted, cursor at the center hub.
        interp.apply(stickState(0, 0, touch = true))
        assertEquals(ScMenuSpec.Kind.RADIAL, overlay.last?.kind)
        assertEquals("LEFT_STICK", overlay.last?.menuId) // the source id is threaded through for per-menu placement
        assertEquals(-1, overlay.last?.highlighted)
        assertEquals(0f, overlay.last!!.cursorX, 0.02f)
        assertEquals(0f, overlay.last!!.cursorY, 0.02f)
        // Then deflect up while still touching -> slot 0 highlights and the cursor moves up (cursorY -> +1).
        interp.apply(stickState(0, 32767, touch = true))
        assertEquals(0, overlay.last?.highlighted)
        assertTrue("cursor tracks the deflection", overlay.last!!.cursorY > 0.9f)
    }

    // Stick radial, HOLD activation (movement-radial style): hold the pointed slot's key while deflected.
    private fun stickRadialHold() = ScProfile(
        leftStick = StickMode.RadialMenu(
            slots = listOf(slot(XKeycode.KEY_W), slot(XKeycode.KEY_D), slot(XKeycode.KEY_S), slot(XKeycode.KEY_A)),
            activation = MenuActivation.HOLD,
            deadzone = 0.35f,
        ),
    )

    @Test
    fun `stick radial HOLD holds the pointed direction and releases on center`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, stickRadialHold(), haptics = null)
        interp.apply(stickState(0, 32767))  // up -> hold W
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_W && it.pressed })
        assertEquals(0, sink.keys.count { it.key == XKeycode.KEY_W && !it.pressed })
        interp.apply(stickState(0, 0))      // center -> release W
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_W && !it.pressed })
    }

    @Test
    fun `stick radial HOLD switches held key when the direction changes`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, stickRadialHold(), haptics = null)
        interp.apply(stickState(0, 32767))   // up -> W down
        interp.apply(stickState(32767, 0))   // right -> W up, D down
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_W && !it.pressed })
        assertEquals(1, sink.keys.count { it.key == XKeycode.KEY_D && it.pressed })
    }

    @Test
    fun `movement radial with hold_repeats pulses the direction while held (not a continuous hold)`() {
        val sink = RecordingSink()
        var now = 1000L
        val up = MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_UP), Activator.Turbo(280)))
        val mode = StickMode.RadialMenu(
            slots = listOf(up, slot(XKeycode.KEY_RIGHT), slot(XKeycode.KEY_DOWN), slot(XKeycode.KEY_LEFT)),
            activation = MenuActivation.HOLD, directional = true,
        )
        val interp = ProfileInterpreter(sink, ScProfile(leftStick = mode), haptics = null, clock = { now })
        interp.apply(stickState(0, 32767))                  // up -> first pulse
        assertEquals(1, sink.keyPresses(XKeycode.KEY_UP))
        now = 1100; interp.apply(stickState(0, 32767))      // before 280ms -> no new pulse
        assertEquals(1, sink.keyPresses(XKeycode.KEY_UP))
        now = 1300; interp.apply(stickState(0, 32767))      // past 280ms -> 2nd pulse
        assertEquals(2, sink.keyPresses(XKeycode.KEY_UP))
        // each repeat is a press+release pair (a pulse), never a held-down key.
        assertEquals(2, sink.keys.count { it.key == XKeycode.KEY_UP && !it.pressed })
        now = 1400; interp.apply(stickState(0, 0))          // center -> disengage, no spurious extra release
        assertEquals(2, sink.keys.count { it.key == XKeycode.KEY_UP && !it.pressed })
    }

    @Test
    fun `ToME4 movement radial ring is Turbo (repeats) and stick-click waits a turn`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("delf_tome4_neptune.vdf"))
        // The movement set's left-stick radial: every ring slot carries hold_repeats -> Turbo (so it repeats).
        val set = cfg.sets.values.first { (it.leftStick as? StickMode.RadialMenu)?.directional == true }
        val movement = set.leftStick as StickMode.RadialMenu
        assertTrue("all ring slots repeat (Turbo)", movement.slots.all { it.binding.activator is Activator.Turbo })
        // Pushing the stick down (L3 click) = the radial's `click` input = "Wait a turn" (KEYPAD_5).
        assertEquals(XKeycode.KEY_KP_5, (set.buttons[TritonProtocol.BTN_L3]?.output as? ScOutput.Key)?.keys?.first())
    }

    @Test
    fun `stick radial inside deadzone fires nothing`() {
        val sink = RecordingSink()
        val interp = ProfileInterpreter(sink, stickRadialHold(), haptics = null)
        interp.apply(stickState(3000, 3000)) // ~0.13 magnitude, inside 0.35 deadzone
        assertEquals(0, sink.keys.count { it.pressed })
    }

    /** Capturing overlay so tests can assert the HUD labels/center the interpreter pushes. */
    private class CapturingMenuOverlay : ScMenuOverlay {
        var last: ScMenuSpec? = null
        override fun showMenu(spec: ScMenuSpec) { last = spec }
        override fun hideMenu() {}
    }

    @Test
    fun `directional radial labels the ring with 8-way arrows and shows the center`() {
        val overlay = CapturingMenuOverlay()
        // 8 ring slots bound to arrow/keypad keys (order N,NE,E,SE,S,SW,W,NW) + a no-op center.
        val ring = listOf(
            slot(XKeycode.KEY_UP), slot(XKeycode.KEY_KP_9), slot(XKeycode.KEY_RIGHT), slot(XKeycode.KEY_KP_3),
            slot(XKeycode.KEY_DOWN), slot(XKeycode.KEY_KP_1), slot(XKeycode.KEY_LEFT), slot(XKeycode.KEY_KP_7),
        )
        val mode = StickMode.RadialMenu(ring, directional = true, center = MenuSlot(Binding(ScOutput.MouseNudge(0, 0)), ""))
        val interp = ProfileInterpreter(RecordingSink(), ScProfile(leftStick = mode), haptics = null, menuOverlay = overlay)
        interp.apply(stickState(0, 32767)) // deflect up -> HUD pushed, top slot highlighted
        val spec = overlay.last!!
        assertEquals(listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖"), spec.labels)
        assertEquals(0, spec.highlighted)           // up = ring index 0
        assertEquals("", spec.centerLabel)           // center present (no-op label)
    }

    @Test
    fun `importer splits ToME4 movement radial into 8-way directional ring with a center`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("delf_tome4_neptune.vdf"))
        val radials = cfg.sets.values.flatMap { listOf(it.leftStick, it.rightStick) }.filterIsInstance<StickMode.RadialMenu>()
        val movement = radials.firstOrNull { it.directional }
            ?: error("expected a directional (movement) radial; got ${radials.map { it.slots.size }}")
        assertEquals("ring is the 8 directions (center button_0 pulled out)", 8, movement.slots.size)
        assertTrue("center button present", movement.center != null)
        assertEquals("center labelled from the stick-click action", "Wait", movement.center?.label)
    }

    @Test
    fun `importer turns ToME4 pad touch_menu into a real TouchMenu (no longer dropped)`() {
        // ToME4's left trackpad is a reference->touch_menu hotbar grid; it used to import as None.
        val cfg = SteamControllerProfileImporter.importConfig(load("delf_tome4_neptune.vdf"))
        val touchMenus = cfg.sets.values.flatMap { listOf(it.leftPad, it.rightPad) }.filterIsInstance<PadMode.TouchMenu>()
        assertTrue("expected at least one TouchMenu from ToME4", touchMenus.isNotEmpty())
        val m = touchMenus.first()
        assertTrue("touch menu has resolved slots", m.slots.isNotEmpty())
        assertEquals(m.cols * m.rows >= m.slots.size, true) // grid fits all slots
    }
}
