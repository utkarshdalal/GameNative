package app.gamenative.steamcontroller

import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase-1 of the Steam-style editor: authoring analog surfaces (pad/stick behavior + settings). */
class ScEditableAnalogTest {

    @Test
    fun `joystick mode converts to JoystickMove on the chosen stick`() {
        val a = EditAnalog(AnalogMode.JOYSTICK, deadzonePct = 20, invertY = false, curve = EditCurve.AGGRESSIVE, outputStick = "LEFT")
        val m = a.toStickMode() as StickMode.JoystickMove
        assertEquals(Stick.LEFT, m.stick)
        assertEquals(0.20f, m.deadzone, 1e-4f)
        assertEquals(false, m.invertY)
        assertEquals(ResponseCurve.AGGRESSIVE, m.curve)
    }

    @Test
    fun `pad mouse mode scales sensitivity off the engine default`() {
        val m = EditAnalog(AnalogMode.MOUSE, sensitivityPct = 200, invertY = true).toPadMode() as PadMode.Mouse
        assertEquals(EditAnalog.DEFAULT_PAD_MOUSE_SENS * 2f, m.sensitivity, 1e-6f)
        assertEquals(true, m.invertY)
    }

    @Test
    fun `pad dpad mode maps the four direction outputs`() {
        val a = EditAnalog(
            AnalogMode.DPAD,
            up = EditBinding(OutputKind.KEY, keys = listOf("KEY_W")),
            down = EditBinding(OutputKind.KEY, keys = listOf("KEY_S")),
            left = EditBinding(OutputKind.KEY, keys = listOf("KEY_A")),
            right = EditBinding(OutputKind.KEY, keys = listOf("KEY_D")),
        )
        val m = a.toPadMode() as PadMode.DPad
        assertEquals(listOf(XKeycode.KEY_W), (m.up as ScOutput.Key).keys)
        assertEquals(listOf(XKeycode.KEY_D), (m.right as ScOutput.Key).keys)
    }

    @Test
    fun `cross-kind modes return null so the base mode is kept`() {
        // JOYSTICK is stick-only -> not a valid pad mode; SCROLL_WHEEL is pad-only -> not a valid stick mode.
        assertNull(EditAnalog(AnalogMode.JOYSTICK).toPadMode())
        assertNull(EditAnalog(AnalogMode.SCROLL_WHEEL).toStickMode())
    }

    @Test
    fun `toScProfile overrides set surfaces and inherits null ones`() {
        val base = ScProfile.default() // rightPad = Mouse, leftStick/rightStick = JoystickMove
        val edit = ScEditableProfile(
            rightStick = EditAnalog(AnalogMode.MOUSE, sensitivityPct = 100), // override stick -> mouse
            // leftStick / leftPad / rightPad left null -> inherit base
        )
        val p = edit.toScProfile(base)
        assertTrue("right stick overridden to mouse", p.rightStick is StickMode.Mouse)
        assertTrue("left stick inherited from base", p.leftStick is StickMode.JoystickMove)
        assertTrue("right pad inherited from base", p.rightPad is PadMode.Mouse)
    }

    @Test
    fun `round-trips the default profile's parametric analog modes`() {
        val p = ScEditableProfile.from(ScProfile.default()).toScProfile()
        val rp = p.rightPad as PadMode.Mouse
        assertEquals(1f / 70f, rp.sensitivity, 1e-6f)
        assertEquals(true, rp.invertY)
        val rs = p.rightStick as StickMode.JoystickMove
        assertEquals(Stick.RIGHT, rs.stick)
        assertEquals(0.12f, rs.deadzone, 1e-4f)
        assertTrue(p.leftPad is PadMode.None)
    }

    @Test
    fun `radial pad menu round-trips slots, center, directional, activation, onClick`() {
        val menu = PadMode.RadialMenu(
            slots = listOf(
                MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_1)), "One"),
                MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_2), Activator.Turbo(280)), "Two"),
            ),
            onClick = true, activation = MenuActivation.HOLD,
            center = MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_KP_5)), "Wait"), directional = true,
        )
        val rt = EditAnalog.fromPad(menu)!!.toPadMode() as PadMode.RadialMenu
        assertEquals(2, rt.slots.size)
        assertEquals("Two", rt.slots[1].label)
        assertEquals(listOf(XKeycode.KEY_2), (rt.slots[1].binding.output as ScOutput.Key).keys)
        assertTrue("slot activator preserved", rt.slots[1].binding.activator is Activator.Turbo)
        assertEquals(true, rt.onClick)
        assertEquals(MenuActivation.HOLD, rt.activation)
        assertEquals(true, rt.directional)
        assertEquals("Wait", rt.center!!.label)
    }

    @Test
    fun `touch pad menu round-trips grid + slots`() {
        val menu = PadMode.TouchMenu(
            slots = listOf(MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_A)), "A"), MenuSlot(Binding(ScOutput.MouseButton(com.winlator.xserver.Pointer.Button.BUTTON_LEFT)))),
            cols = 2, rows = 1, onClick = false, activation = MenuActivation.COMMIT,
        )
        val rt = EditAnalog.fromPad(menu)!!.toPadMode() as PadMode.TouchMenu
        assertEquals(2, rt.cols); assertEquals(1, rt.rows)
        assertEquals(2, rt.slots.size)
        assertEquals(MenuActivation.COMMIT, rt.activation)
    }

    @Test
    fun `button pad grid round-trips cells + grid`() {
        val menu = PadMode.ButtonPadGrid(
            cols = 2, rows = 2,
            cells = listOf(ScOutput.Key(XKeycode.KEY_1), ScOutput.Key(XKeycode.KEY_2), ScOutput.Key(XKeycode.KEY_3), ScOutput.None),
            onClick = true,
        )
        val rt = EditAnalog.fromPad(menu)!!.toPadMode() as PadMode.ButtonPadGrid
        assertEquals(2, rt.cols); assertEquals(2, rt.rows); assertEquals(true, rt.onClick)
        assertEquals(listOf(XKeycode.KEY_1), (rt.cells[0] as ScOutput.Key).keys)
        assertTrue("empty cell stays unbound", rt.cells[3] is ScOutput.None)
    }

    @Test
    fun `stick radial menu round-trips slots + deadzone + directional`() {
        val menu = StickMode.RadialMenu(
            slots = listOf(MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_KP_8)), "↑")),
            activation = MenuActivation.HOLD, deadzone = 0.4f, directional = true,
        )
        val rt = EditAnalog.fromStick(menu)!!.toStickMode() as StickMode.RadialMenu
        assertEquals(0.4f, rt.deadzone, 1e-2f)
        assertEquals(true, rt.directional)
        assertEquals(MenuActivation.HOLD, rt.activation)
    }

    @Test
    fun `a radial with a no-op mouse_delta center is representable (does not bail to inherit)`() {
        // Steam's common idiom: touch_menu_button_0 bound to `mouse_delta 0 0` as a no-op center. This must NOT
        // make the whole menu inherit (the bug that hid ToME4's left-stick radial from the editor).
        val menu = StickMode.RadialMenu(
            slots = listOf(MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_KP_8)), "↑")),
            center = MenuSlot(Binding(ScOutput.MouseNudge(0, 0)), "Wait"), directional = true,
        )
        val e = EditAnalog.fromStick(menu)
        assertEquals(AnalogMode.RADIAL, e!!.mode)
        val rt = e.toStickMode() as StickMode.RadialMenu
        assertTrue("center preserved as a mouse nudge", rt.center!!.binding.output is ScOutput.MouseNudge)
        assertEquals(0, (rt.center!!.binding.output as ScOutput.MouseNudge).dx)
        assertEquals(1, rt.slots.size)
    }

    @Test
    fun `a menu with an advanced slot output stays inherit (null) so the overlay preserves it`() {
        // A slot bound to an output the editor still can't author (mode-shift) -> fromPad bails to null = inherit base.
        val menu = PadMode.RadialMenu(slots = listOf(MenuSlot(Binding(ScOutput.ModeShift("left_trackpad", "5"))), MenuSlot(Binding(ScOutput.Key(XKeycode.KEY_1)))))
        assertNull("an unrepresentable menu slot -> whole surface inherits", EditAnalog.fromPad(menu))
        // And via the profile round-trip, the base menu survives untouched.
        val base = ScProfile.default().let {
            ScProfile(name = it.name, buttons = it.buttons, leftStick = it.leftStick, rightStick = it.rightStick,
                leftPad = it.leftPad, rightPad = menu, leftTrigger = it.leftTrigger, rightTrigger = it.rightTrigger,
                gyro = it.gyro, haptics = it.haptics)
        }
        val edit = ScEditableProfile.from(base)
        assertNull(edit.rightPad)
        assertTrue(edit.toScProfile(base).rightPad is PadMode.RadialMenu)
    }
}
