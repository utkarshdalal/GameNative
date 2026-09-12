package app.gamenative.steamcontroller

import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the binding-editor data layer ([ScEditableProfile] ⇄ [ScProfile]) + its JSON serialization.
 * Pure logic, no device — the live store IO ([ScConfigStore]) is exercised on-hardware via the debug receiver.
 */
@RunWith(RobolectricTestRunner::class)
class ScProfileEditorTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `from(default) round-trips the default digital bindings`() {
        val edit = ScEditableProfile.from(ScProfile.default())
        val rebuilt = edit.toScProfile()
        val default = ScProfile.default()
        // Every editor-exposed source present in the default profile survives the round trip.
        for (src in ScSource.entries) {
            assertEquals("source ${src.name}", default.buttons[src.bit], rebuilt.buttons[src.bit])
        }
    }

    @Test
    fun `key binding overrides a source and carries its activator`() {
        val edit = ScEditableProfile(
            name = "Custom",
            buttons = mapOf(
                ScSource.A.name to EditBinding(
                    kind = OutputKind.KEY,
                    keys = listOf(XKeycode.KEY_SPACE.name),
                    activator = EditActivator.DOUBLE_PRESS,
                ),
            ),
        )
        val p = edit.toScProfile()
        val b = p.buttons[ScSource.A.bit]!!
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_SPACE)), b.output)
        assertTrue(b.activator is Activator.DoublePress)
    }

    @Test
    fun `NONE explicitly unbinds a source inherited from base`() {
        // B is bound in the default; an explicit NONE removes it.
        assertTrue(ScProfile.default().buttons.containsKey(ScSource.B.bit))
        val edit = ScEditableProfile(buttons = mapOf(ScSource.B.name to EditBinding(OutputKind.NONE)))
        val p = edit.toScProfile()
        assertFalse(p.buttons.containsKey(ScSource.B.bit))
    }

    @Test
    fun `gamepad and mouse outputs convert`() {
        val edit = ScEditableProfile(
            buttons = mapOf(
                ScSource.REAR_LEFT_TOP.name to EditBinding(
                    kind = OutputKind.GAMEPAD_BUTTON,
                    gamepadIdx = ExternalController.IDX_BUTTON_A.toInt(),
                ),
                ScSource.RIGHT_PAD_CLICK.name to EditBinding(
                    kind = OutputKind.MOUSE_BUTTON,
                    mouseButton = Pointer.Button.BUTTON_MIDDLE.name,
                ),
            ),
        )
        val p = edit.toScProfile()
        assertEquals(ScOutput.GamepadButton(ExternalController.IDX_BUTTON_A.toInt()), p.buttons[ScSource.REAR_LEFT_TOP.bit]!!.output)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_MIDDLE), p.buttons[ScSource.RIGHT_PAD_CLICK.bit]!!.output)
    }

    @Test
    fun `toScConfig wraps a single default-active set`() {
        val cfg = ScEditableProfile(name = "Solo").toScConfig()
        assertEquals(setOf("0"), cfg.sets.keys)
        assertEquals("0", cfg.defaultSetId)
        assertEquals("Solo", cfg.defaultProfile().name)
    }

    @Test
    fun `json serialization round-trips`() {
        val edit = ScEditableProfile.from(ScProfile.default())
        val text = json.encodeToString(ScEditableProfile.serializer(), edit)
        val back = json.decodeFromString(ScEditableProfile.serializer(), text)
        assertEquals(edit, back)
    }

    @Test
    fun `invalid keycode name degrades to None, not a crash`() {
        val b = EditBinding(kind = OutputKind.KEY, keys = listOf("NOT_A_REAL_KEY"))
        assertEquals(ScOutput.None, b.toOutput())
        // and a None output unbinds rather than throwing
        val p = ScEditableProfile(buttons = mapOf(ScSource.X.name to b)).toScProfile()
        assertNull(p.buttons[ScSource.X.bit])
    }

    // ---- Phase 4: activator timings ----

    @Test
    fun `activator timing round-trips through ms field`() {
        val edit = EditBinding(kind = OutputKind.KEY, keys = listOf(XKeycode.KEY_E.name), activator = EditActivator.LONG_PRESS, activatorMs = 750)
        val act = edit.toActivator()
        assertEquals(Activator.LongPress(750), act)
        // and turbo / double-press carry their ms too
        assertEquals(Activator.Turbo(120), EditBinding(activator = EditActivator.TURBO, activatorMs = 120).toActivator())
        assertEquals(Activator.DoublePress(250), EditBinding(activator = EditActivator.DOUBLE_PRESS, activatorMs = 250).toActivator())
    }

    @Test
    fun `release activator maps to OnRelease`() {
        assertEquals(Activator.OnRelease, EditBinding(activator = EditActivator.RELEASE).toActivator())
    }

    @Test
    fun `action-set switch carries onRelease from the activator (hold-to-shift authoring)`() {
        // Press-edge switch (default): set A binding "press LB -> set 1".
        val press = EditBinding(OutputKind.SWITCH_ACTION_SET, targetSetId = "1", activator = EditActivator.REGULAR).toOutput()
        assertTrue(press is ScOutput.SwitchActionSet && !(press as ScOutput.SwitchActionSet).onRelease)
        // Release-edge switch: set B binding "release LB -> set 0" — the second half of momentary hold-to-shift.
        val release = EditBinding(OutputKind.SWITCH_ACTION_SET, targetSetId = "0", activator = EditActivator.RELEASE).toOutput()
        assertTrue(release is ScOutput.SwitchActionSet && (release as ScOutput.SwitchActionSet).onRelease)
        // Round-trips back through from(profile): the release switch keeps its RELEASE activator.
        val base = ScProfile(buttons = mapOf(ScSource.LEFT_BUMPER.bit to Binding(ScOutput.SwitchActionSet("0", onRelease = true))))
        val rebuilt = ScEditableProfile.from(base)
        assertEquals(EditActivator.RELEASE, rebuilt.buttons[ScSource.LEFT_BUMPER.name]!!.activator)
    }

    @Test
    fun `non-default activator timing survives from(profile) round trip`() {
        val base = ScProfile(buttons = mapOf(ScSource.A.bit to Binding(ScOutput.Key(XKeycode.KEY_R), Activator.Turbo(200))))
        val rebuilt = ScEditableProfile.from(base).toScProfile()
        assertEquals(Activator.Turbo(200), rebuilt.buttons[ScSource.A.bit]!!.activator)
    }

    @Test
    fun `pad-mouse smoothing and jitter floor round-trip`() {
        // Folded from the old global "Touchpad & menus" tuning → per-pad. Non-default values catch a dropped mapping.
        val pad = EditAnalog(mode = AnalogMode.MOUSE, smoothingPct = 40, jitterFloor = 55).toPadMode() as PadMode.Mouse
        assertEquals(40, pad.smoothing)
        assertEquals(55, pad.jitterFloor)
        val back = EditAnalog.fromPad(pad)!!
        assertEquals(40, back.smoothingPct)
        assertEquals(55, back.jitterFloor)
    }

    // ---- Phase 5: trigger / gyro / haptics ----

    @Test
    fun `trigger axis and staged modes convert`() {
        val axis = EditTrigger(TriggerEditMode.AXIS, axis = "GAMEPAD_L2").toRuntime(TriggerAxis.GAMEPAD_R2)
        assertEquals(TriggerMode.Axis(TriggerAxis.GAMEPAD_L2), axis)

        val staged = EditTrigger(
            TriggerEditMode.STAGED, axis = "NONE",
            soft = EditBinding(OutputKind.KEY, keys = listOf(XKeycode.KEY_SHIFT_L.name)),
            full = EditBinding(OutputKind.MOUSE_BUTTON, mouseButton = Pointer.Button.BUTTON_LEFT.name),
            softThresholdPct = 30, fullThresholdPct = 95,
        ).toRuntime(TriggerAxis.GAMEPAD_R2)
        assertTrue(staged is TriggerMode.Staged)
        staged as TriggerMode.Staged
        assertEquals(0.30f, staged.softThreshold, 0.001f)
        assertEquals(0.95f, staged.fullThreshold, 0.001f)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), staged.full)
    }

    @Test
    fun `trigger round-trips from default profile`() {
        val edit = ScEditableProfile.from(ScProfile.default())
        val p = edit.toScProfile()
        assertEquals(ScProfile.default().leftTrigger, p.leftTrigger)
        assertEquals(ScProfile.default().rightTrigger, p.rightTrigger)
    }

    @Test
    fun `gyro mode and gate convert and round-trip`() {
        val off = EditGyro(GyroEditMode.OFF).toRuntime()
        assertEquals(GyroMode.None, off)
        val m = EditGyro(GyroEditMode.MOUSE, sensitivityPct = 200, gate = "LEFT_GRIP").toRuntime()
        assertTrue(m is GyroMode.Mouse)
        m as GyroMode.Mouse
        assertEquals(GyroGate.LEFT_GRIP, m.gate)
        // from(default).gyro survives the round trip
        val rebuilt = ScEditableProfile.from(ScProfile.default()).toScProfile()
        assertEquals(ScProfile.default().gyro, rebuilt.gyro)
    }

    @Test
    fun `haptics enable and detent override base, keep gains`() {
        val base = HapticSettings()
        val out = EditHaptics(enabled = false, leftPadEnabled = false, rightPadEnabled = true, detentStep = 5000).toRuntime(base)
        assertFalse(out.enabled)
        assertFalse(out.leftPadEnabled)
        assertEquals(5000, out.detentStep)
        // gains untouched
        assertEquals(base.clickGain, out.clickGain)
        assertEquals(base.tickGain, out.tickGain)
    }

    @Test
    fun `full editable profile json round-trips with trigger gyro haptics`() {
        val edit = ScEditableProfile.from(ScProfile.default())
        val text = json.encodeToString(ScEditableProfile.serializer(), edit)
        val back = json.decodeFromString(ScEditableProfile.serializer(), text)
        assertEquals(edit, back)
        // and the rebuilt runtime profile matches the default's analog/trigger/gyro
        val p = back.toScProfile()
        assertEquals(ScProfile.default().gyro, p.gyro)
        assertEquals(ScProfile.default().rightTrigger, p.rightTrigger)
    }
}
