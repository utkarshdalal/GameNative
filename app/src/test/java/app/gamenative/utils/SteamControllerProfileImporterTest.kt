package app.gamenative.utils

import app.gamenative.steamcontroller.Activator
import app.gamenative.steamcontroller.DpadLayout
import app.gamenative.steamcontroller.GyroActivation
import app.gamenative.steamcontroller.GyroGate
import app.gamenative.steamcontroller.GyroMode
import app.gamenative.steamcontroller.LayerOpType
import app.gamenative.steamcontroller.PadMode
import app.gamenative.steamcontroller.ResponseCurve
import app.gamenative.steamcontroller.ScOutput
import app.gamenative.steamcontroller.Stick
import app.gamenative.steamcontroller.StickMode
import app.gamenative.steamcontroller.TriggerAxis
import app.gamenative.steamcontroller.TriggerMode
import app.gamenative.steamcontroller.TritonProtocol
import com.winlator.inputcontrols.ExternalController
import com.winlator.xserver.Pointer
import com.winlator.xserver.XKeycode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the `.vdf` -> [app.gamenative.steamcontroller.ScProfile] importer against *real* Steam Input
 * configs (the "coverage guarantee" of docs/STEAM-INPUT-COVERAGE.md): a v2-schema gamepad template, a
 * v3-schema keyboard+mouse template, and the real 2026-controller `chord_triton.vdf`.
 */
@RunWith(RobolectricTestRunner::class)
class SteamControllerProfileImporterTest {

    private fun load(name: String): String =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream("sc/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing test resource sc/$name")

    private fun gamepadBtn(idx: Byte) = ScOutput.GamepadButton(idx.toInt())

    @Test
    fun `hand-authored DOOM test config imports to the intended modes`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("doom_sc_test.vdf"))
        val p = cfg.defaultProfile()
        assertTrue("left stick = joystick", p.leftStick is StickMode.JoystickMove)
        assertTrue("right stick = joystick", p.rightStick is StickMode.JoystickMove)
        assertTrue("left pad = directional swipe", p.leftPad is PadMode.DirectionalSwipe)
        assertTrue("right pad = relative mouse", p.rightPad is PadMode.Mouse)
        assertTrue("gyro = joystick (camera)", p.gyro is GyroMode.Joystick)
        assertEquals(TriggerAxis.GAMEPAD_R2, (p.rightTrigger as TriggerMode.Axis).axis)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_A), p.buttons[TritonProtocol.BTN_A]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_L1), p.buttons[TritonProtocol.BTN_LBUMPER]?.output)
        // swipe up = mouse-wheel weapon cycle
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_UP), (p.leftPad as PadMode.DirectionalSwipe).up)
    }
    @Test
    fun `region + single_button test config decodes the B1 B2 modes`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_region_single_test.vdf")).defaultProfile()
        // B2: left pad = single_button bound to key F (fires the whole surface as one key)
        val lp = p.leftPad as PadMode.SingleButton
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_F)), lp.output)
        // B1: right pad = mouse_region, left-quarter (center 0.25) x 30% wide, both axes inverted
        val rp = p.rightPad as PadMode.AbsoluteMouse
        assertEquals(0.25f, rp.centerX, 1e-4f)
        assertEquals(0.30f, rp.sizeX, 1e-4f)
        assertTrue(rp.invertX && rp.invertY)
    }

    @Test
    fun `stick dpad mode decodes to StickMode DPad`() {
        val vdf = """
            "controller_mappings" {
              "version" "3"
              "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "dpad"
                "inputs" {
                  "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "key_press W" } } } }
                  "dpad_south" { "activators" { "Full_Press" { "bindings" { "binding" "key_press S" } } } }
                  "dpad_west" { "activators" { "Full_Press" { "bindings" { "binding" "key_press A" } } } }
                  "dpad_east" { "activators" { "Full_Press" { "bindings" { "binding" "key_press D" } } } }
                }
              }
              "preset" { "id" "0" "name" "Default"
                "group_source_bindings" { "0" "joystick active" }
              }
            }
        """.trimIndent()
        val d = SteamControllerProfileImporter.importConfig(vdf).defaultProfile().leftStick as StickMode.DPad
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_W)), d.up)
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_A)), d.left)
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_D)), d.right)
        assertEquals(DpadLayout.EIGHT_WAY, d.layout) // no `layout` setting -> 8-way default
    }

    @Test
    fun `relative mouse pad decodes rotation and H-V scale`() {
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "mouse" "inputs" {}
                "settings" { "rotation" "8" "sensitivity_vert_scale" "89" "sensitivity_horiz_scale" "150" } }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "left_trackpad active" } } }
        """.trimIndent()
        val m = SteamControllerProfileImporter.importConfig(vdf).defaultProfile().leftPad as PadMode.Mouse
        assertEquals(8f, m.rotation, 1e-4f)
        assertEquals(0.89f, m.vertScale, 1e-4f)
        assertEquals(1.5f, m.horizScale, 1e-4f)
    }

    @Test
    fun `stick response curve decodes from curve_exponent`() {
        fun stickWith(settings: String): StickMode {
            val vdf = """
                "controller_mappings" { "version" "3" "controller_type" "controller_triton"
                  "group" { "id" "0" "mode" "joystick_move" "inputs" {} "settings" { $settings } }
                  "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "joystick active" } } }
            """.trimIndent()
            return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().leftStick
        }
        // curve_exponent is the literal power exponent: 1=LINEAR (m¹), 2=AGGRESSIVE (m²), 3=WIDE (m³).
        assertEquals(ResponseCurve.LINEAR, (stickWith(""" "curve_exponent" "1" """) as StickMode.JoystickMove).curve)
        assertEquals(ResponseCurve.AGGRESSIVE, (stickWith(""" "curve_exponent" "2" """) as StickMode.JoystickMove).curve)
        assertEquals(ResponseCurve.WIDE, (stickWith(""" "curve_exponent" "3" """) as StickMode.JoystickMove).curve)
        assertEquals(ResponseCurve.LINEAR, (stickWith("") as StickMode.JoystickMove).curve) // absent -> linear
        // custom slider: 200 ~ linear, low ~ aggressive.
        assertEquals(ResponseCurve.AGGRESSIVE, (stickWith(""" "custom_curve_exponent" "100" """) as StickMode.JoystickMove).curve)
    }

    @Test
    fun `dpad layout and overlap settings decode`() {
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "dpad"
                "inputs" { "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "key_press W" } } } } }
                "settings" { "layout" "3" "overlap_region" "8000" } }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "joystick active" } } }
        """.trimIndent()
        val d = SteamControllerProfileImporter.importConfig(vdf).defaultProfile().leftStick as StickMode.DPad
        assertEquals(DpadLayout.CROSS_GATE, d.layout)
        assertEquals(8000f / 32768f, d.overlap, 1e-4f)
    }

    @Test
    fun `new-modes test config decodes stick-dpad and pad-joystick`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_newmodes_test.vdf")).defaultProfile()
        val d = p.leftStick as StickMode.DPad          // stick-as-dpad -> WASD
        assertEquals(key(XKeycode.KEY_W), d.up); assertEquals(key(XKeycode.KEY_D), d.right)
        assertEquals(PadMode.Joystick(Stick.RIGHT), p.leftPad)   // pad-as-joystick, output_joystick "2" -> RIGHT
    }

    @Test
    fun `macros and per-binding delay-toggle decode`() {
        val vdf = """
            "controller_mappings" {
              "version" "3"
              "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "four_buttons"
                "inputs" {
                  "button_a" { "activators" {
                    "Full_Press" { "bindings" { "binding" "key_press 1" } }
                    "Full_Press" { "bindings" { "binding" "key_press 2" } "settings" { "delay_start" "50" } }
                  } }
                  "button_b" { "activators" {
                    "Full_Press" { "bindings" { "binding" "key_press F" } "settings" { "delay_start" "76" "delay_end" "308" "toggle" "1" } }
                  } }
                  "button_x" { "activators" {
                    "Full_Press" { "bindings" { "binding" "key_press LEFT_SHIFT"  "binding" "key_press TAB" } }
                  } }
                }
              }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "button_diamond active" } }
            }
        """.trimIndent()
        val p = SteamControllerProfileImporter.importConfig(vdf).defaultProfile()
        // Repeated Full_Press blocks -> a 2-command macro; per-command delay is carried
        val macro = p.buttons[TritonProtocol.BTN_A]!!.output as ScOutput.Macro
        assertEquals(2, macro.commands.size)
        assertEquals(key(XKeycode.KEY_1), macro.commands[0].outputs.single())
        assertEquals(key(XKeycode.KEY_2), macro.commands[1].outputs.single())
        assertEquals(50L, macro.commands[1].delayStartMs)
        // Per-binding delay + toggle on a single binding
        val bb = p.buttons[TritonProtocol.BTN_B]!!
        assertEquals(76L, bb.delayStartMs); assertEquals(308L, bb.delayEndMs); assertTrue(bb.toggle)
        // A single command with two keys stays a HELD COMBO (not a macro) -> preserves Alt+Tab-style combos
        val combo = p.buttons[TritonProtocol.BTN_X]!!.output as ScOutput.Key
        assertEquals(2, combo.keys.size)
    }

    @Test
    fun `bind-settings test config decodes macro delay and toggle`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_bindsettings_test.vdf")).defaultProfile()
        assertEquals(2, (p.buttons[TritonProtocol.BTN_A]!!.output as ScOutput.Macro).commands.size)
        assertTrue(p.buttons[TritonProtocol.BTN_B]!!.toggle)
        assertEquals(500L, p.buttons[TritonProtocol.BTN_X]!!.delayStartMs)
        assertEquals(500L, p.buttons[TritonProtocol.BTN_Y]!!.delayEndMs)
    }

    @Test
    fun `gyro + mouse-joystick test config decodes the intended modes`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_gyro_mousejoy_test.vdf")).defaultProfile()
        assertTrue("gyro = mouse aim", p.gyro is GyroMode.Mouse)
        assertTrue("right pad = mouse joystick", p.rightPad is PadMode.MouseJoystick)
        assertTrue("left stick = joystick", p.leftStick is StickMode.JoystickMove)
    }

    @Test
    fun `surface touch input binds an output to the touch bit`() {
        // Steam writes a bound surface-touch as a "touch" input in the group (alongside "click"), per a real export:
        // left stick (joystick) click -> L3, touch -> right bumper.
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "joystick_move" "inputs" {
                  "click" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button JOYSTICK_LEFT" } } } }
                  "touch" { "activators" { "Full_Press" { "bindings" { "binding" "xinput_button SHOULDER_RIGHT" } } } } } }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "joystick active" } } }
        """.trimIndent()
        val p = SteamControllerProfileImporter.importConfig(vdf).defaultProfile()
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_L3), p.buttons[TritonProtocol.BTN_L3]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_R1), p.buttons[TritonProtocol.BTN_LSTICK_TOUCH]?.output)
    }

    @Test
    fun `touch-bind device config binds each surface touch to its key`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_touch_bind_test.vdf")).defaultProfile()
        assertEquals(key(XKeycode.KEY_1), p.buttons[TritonProtocol.BTN_LPAD_TOUCH]?.output)
        assertEquals(key(XKeycode.KEY_2), p.buttons[TritonProtocol.BTN_RPAD_TOUCH]?.output)
        assertEquals(key(XKeycode.KEY_3), p.buttons[TritonProtocol.BTN_LSTICK_TOUCH]?.output)
        assertEquals(key(XKeycode.KEY_4), p.buttons[TritonProtocol.BTN_RSTICK_TOUCH]?.output)
    }

    @Test
    fun `gyro ratchet touch mask maps to the any-touch gate`() {
        fun gyroWithMask(settings: String): GyroMode {
            val vdf = """
                "controller_mappings" { "version" "3" "controller_type" "controller_triton"
                  "group" { "id" "0" "mode" "gyro_to_mouse" "inputs" {} "settings" { $settings } }
                  "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "gyro active" } } }
            """.trimIndent()
            return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().gyro
        }
        // 211106234105856 = bits {19,20,46,47} = the 4 touch surfaces (real 2026-07-06 export) -> touch-to-aim
        assertEquals(GyroGate.ANY_TOUCH, (gyroWithMask(""" "gyro_ratchet_button_mask" "211106234105856" """) as GyroMode.Mouse).gate)
        assertEquals(GyroGate.EITHER_GRIP, (gyroWithMask("") as GyroMode.Mouse).gate) // no mask -> grip default
    }

    @Test
    fun `gyro touch-to-aim device config decodes to any-touch`() {
        val p = SteamControllerProfileImporter.importConfig(load("sc_gyro_touch_test.vdf")).defaultProfile()
        assertEquals(GyroGate.ANY_TOUCH, (p.gyro as GyroMode.Mouse).gate)
    }

    @Test
    fun `real gyro-gate capture imports without choking`() {
        // A messy experimental export with macros (testMacro1), single_button, mouse_joystick, flickstick, mouse_region,
        // and gyro ratchet masks — a broad stress test that nothing leaks None / throws.
        val cfg = SteamControllerProfileImporter.importConfig(load("testgyro_gate_capture.vdf"))
        assertTrue("expected action sets", cfg.sets.isNotEmpty())
        assertFalse("no None leaked into buttons", cfg.defaultProfile().buttons.values.any { it.output is ScOutput.None })
    }

    @Test
    fun `gyro_to_joystick honors output_joystick for the target stick`() {
        fun gyroWith(oj: String): GyroMode {
            val vdf = """
                "controller_mappings" { "version" "3" "controller_type" "controller_triton"
                  "group" { "id" "0" "mode" "gyro_to_joystick" "inputs" {} "settings" { "output_joystick" "$oj" } }
                  "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "gyro active" } } }
            """.trimIndent()
            return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().gyro
        }
        assertEquals(Stick.LEFT, (gyroWith("1") as GyroMode.Joystick).stick)
        assertEquals(Stick.RIGHT, (gyroWith("2") as GyroMode.Joystick).stick)
    }

    @Test
    fun `gyro_button_invert decodes the enable-suppress-toggle activation`() {
        // Values confirmed from labeled Steam exports (testGyroEnable/Suppress/Toggle): absent=Enable, "0"=Suppress,
        // "2"=Toggle. `gyro_button` reads "1" for all three, so it is NOT the mode (regression: don't read it).
        fun gyroFor(settings: String): GyroMode {
            val vdf = """
                "controller_mappings" { "version" "3" "controller_type" "controller_triton"
                  "group" { "id" "0" "mode" "gyro_to_mouse" "inputs" {} "settings" { $settings } }
                  "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "gyro active" } } }
            """.trimIndent()
            return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().gyro
        }
        assertEquals(GyroActivation.ENABLE, (gyroFor(""" "gyro_button" "1" """) as GyroMode.Mouse).activation) // absent invert
        assertEquals(GyroActivation.SUPPRESS, (gyroFor(""" "gyro_button_invert" "0" """) as GyroMode.Mouse).activation)
        assertEquals(GyroActivation.TOGGLE, (gyroFor(""" "gyro_button_invert" "2" """) as GyroMode.Mouse).activation)
    }

    @Test
    fun `gyro joystick camera vs deflection sets the deflection flag`() {
        fun gyroFor(mode: String): GyroMode {
            val vdf = """
                "controller_mappings" { "version" "3" "controller_type" "controller_triton"
                  "group" { "id" "0" "mode" "$mode" "inputs" {} "settings" {} }
                  "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "gyro active" } } }
            """.trimIndent()
            return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().gyro
        }
        assertEquals(false, (gyroFor("gyro_to_joystick_camera") as GyroMode.Joystick).deflection)
        assertEquals(true, (gyroFor("gyro_to_joystick_deflection") as GyroMode.Joystick).deflection)
    }

    @Test
    fun `gyro joystick power curve decodes from the exponent x100`() {
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "gyro_to_joystick_camera" "inputs" {} "settings" { "gyro_to_joystick_power_curve" "400" } }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "gyro active" } } }
        """.trimIndent()
        assertEquals(4f, (SteamControllerProfileImporter.importConfig(vdf).defaultProfile().gyro as GyroMode.Joystick).powerCurve, 1e-4f)
    }

    private fun padWithMode(mode: String): PadMode {
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "$mode" "inputs" {} }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "right_trackpad active" } } }
        """.trimIndent()
        return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().rightPad
    }
    private fun stickWithMode(mode: String): StickMode {
        val vdf = """
            "controller_mappings" { "version" "3" "controller_type" "controller_triton"
              "group" { "id" "0" "mode" "$mode" "inputs" {} }
              "preset" { "id" "0" "name" "Default" "group_source_bindings" { "0" "joystick active" } } }
        """.trimIndent()
        return SteamControllerProfileImporter.importConfig(vdf).defaultProfile().leftStick
    }

    /** Regression guard for the mode-name→type map (the class of bug that made `absolute_mouse` an absolute region).
     *  ONLY `mouse_region` is absolute; every other mouse name is relative. Stick→mouse accepts both name variants. */
    @Test
    fun `mouse-family modes map to the correct type`() {
        assertTrue("mouse_region = absolute region", padWithMode("mouse_region") is PadMode.AbsoluteMouse)
        assertTrue("absolute_mouse = RELATIVE (legacy name, not a region)", padWithMode("absolute_mouse") is PadMode.Mouse)
        assertTrue("mouse = relative", padWithMode("mouse") is PadMode.Mouse)
        assertTrue("relative_mouse = relative", padWithMode("relative_mouse") is PadMode.Mouse)
        assertTrue("mouse_joystick pad = self-centering mouse joystick", padWithMode("mouse_joystick") is PadMode.MouseJoystick)
        assertTrue("joystick_move pad = pad-as-joystick", padWithMode("joystick_move") is PadMode.Joystick)
        assertTrue("joystick_mouse stick = stick→mouse", stickWithMode("joystick_mouse") is StickMode.Mouse)
        assertTrue("mouse_joystick stick (name variant) = stick→mouse, not dropped", stickWithMode("mouse_joystick") is StickMode.Mouse)
        assertTrue("joystick_move stick = joystick", stickWithMode("joystick_move") is StickMode.JoystickMove)
    }

    private fun key(k: XKeycode) = ScOutput.Key(listOf(k))

    // ---- v2 schema: gamepad_joystick.vdf (flat bindings + switch_bindings, xinput outputs) ----------

    @Test
    fun `v2 gamepad config maps buttons sticks triggers`() {
        val p = SteamControllerProfileImporter.import(load("gamepad_joystick.vdf"))
        val b = p.buttons

        // button_diamond -> face buttons
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_A), b[TritonProtocol.BTN_A]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_B), b[TritonProtocol.BTN_B]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_X), b[TritonProtocol.BTN_X]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_Y), b[TritonProtocol.BTN_Y]?.output)

        // left_trackpad is bound to a dpad-mode group -> pad d-pad, dirs emit virtual d-pad
        // (our GamepadDpad index order is 0=up,1=right,2=down,3=left)
        val pad = p.leftPad as PadMode.DPad
        assertEquals(ScOutput.GamepadDpad(0), pad.up)
        assertEquals(ScOutput.GamepadDpad(2), pad.down)
        assertEquals(ScOutput.GamepadDpad(3), pad.left)
        assertEquals(ScOutput.GamepadDpad(1), pad.right)

        // joystick source -> left stick JoystickMove; its click -> L3
        assertEquals(StickMode.JoystickMove(Stick.LEFT), p.leftStick)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_L3), b[TritonProtocol.BTN_L3]?.output)

        // right_trackpad bound to a joystick_move group -> pad-as-joystick (drives a virtual stick); its click -> R3.
        // No output_joystick set -> defaults to the RIGHT (camera) stick.
        assertEquals(PadMode.Joystick(Stick.RIGHT), p.rightPad)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_R3), b[TritonProtocol.BTN_RPAD_CLICK]?.output)

        // analog triggers (click -> xinput TRIGGER_*) -> Axis, no digital click button
        assertEquals(TriggerMode.Axis(TriggerAxis.GAMEPAD_L2), p.leftTrigger)
        assertEquals(TriggerMode.Axis(TriggerAxis.GAMEPAD_R2), p.rightTrigger)
        assertFalse(b.containsKey(TritonProtocol.BTN_LTRIG_CLICK))

        // switch_bindings (v2 top-level): start/select/bumpers/paddles.
        // (button_escape -> BTN_MENU and button_menu -> BTN_VIEW per SWITCH_MAP; here button_escape carries
        // the 'start' output and button_menu the 'select' output — physical Start/Back pairing is a TODO.)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_START), b[TritonProtocol.BTN_MENU]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_SELECT), b[TritonProtocol.BTN_VIEW]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_R1), b[TritonProtocol.BTN_RBUMPER]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_L1), b[TritonProtocol.BTN_LBUMPER]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_A), b[TritonProtocol.BTN_L4]?.output)
        assertEquals(gamepadBtn(ExternalController.IDX_BUTTON_X), b[TritonProtocol.BTN_R4]?.output)
    }

    // ---- v3 schema: controller_xboxone_wasd.vdf (key_press / mouse / staged triggers) ---------------

    @Test
    fun `v3 wasd config maps keys mouse and staged triggers`() {
        val p = SteamControllerProfileImporter.import(load("controller_xboxone_wasd.vdf"))
        val b = p.buttons

        // face buttons -> keys
        assertEquals(key(XKeycode.KEY_SPACE), b[TritonProtocol.BTN_A]?.output)
        assertEquals(key(XKeycode.KEY_E), b[TritonProtocol.BTN_B]?.output)
        assertEquals(key(XKeycode.KEY_R), b[TritonProtocol.BTN_X]?.output)
        assertEquals(key(XKeycode.KEY_F), b[TritonProtocol.BTN_Y]?.output)

        // switch group: escape/menu + bumpers as scroll wheel + rear paddles
        assertEquals(key(XKeycode.KEY_ESC), b[TritonProtocol.BTN_MENU]?.output)   // button_escape
        assertEquals(key(XKeycode.KEY_TAB), b[TritonProtocol.BTN_VIEW]?.output)   // button_menu
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_DOWN), b[TritonProtocol.BTN_LBUMPER]?.output)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_UP), b[TritonProtocol.BTN_RBUMPER]?.output)
        assertEquals(key(XKeycode.KEY_R), b[TritonProtocol.BTN_L5]?.output)       // back_left_upper
        assertEquals(key(XKeycode.KEY_F), b[TritonProtocol.BTN_L4]?.output)       // back_left
        assertEquals(key(XKeycode.KEY_E), b[TritonProtocol.BTN_R5]?.output)       // back_right_upper
        assertEquals(key(XKeycode.KEY_SPACE), b[TritonProtocol.BTN_R4]?.output)   // back_right

        // dpad source -> weapon number keys
        assertEquals(key(XKeycode.KEY_1), b[TritonProtocol.BTN_DPAD_UP]?.output)
        assertEquals(key(XKeycode.KEY_3), b[TritonProtocol.BTN_DPAD_DOWN]?.output)
        assertEquals(key(XKeycode.KEY_2), b[TritonProtocol.BTN_DPAD_RIGHT]?.output)
        assertEquals(key(XKeycode.KEY_4), b[TritonProtocol.BTN_DPAD_LEFT]?.output)

        // joystick source is a WASD d-pad group -> stick-as-dpad (was dropped to None before); its click is kept
        val ld = p.leftStick as StickMode.DPad
        assertEquals(key(XKeycode.KEY_W), ld.up); assertEquals(key(XKeycode.KEY_S), ld.down)
        assertEquals(key(XKeycode.KEY_A), ld.left); assertEquals(key(XKeycode.KEY_D), ld.right)
        assertEquals(key(XKeycode.KEY_SHIFT_L), b[TritonProtocol.BTN_L3]?.output)

        // right_joystick = joystick_mouse -> StickMode.Mouse (stick drives the pointer), click -> left mouse
        assertTrue(p.rightStick is StickMode.Mouse)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), b[TritonProtocol.BTN_R3]?.output)

        // triggers: edge digital binding -> Staged full-pull
        val lt = p.leftTrigger as TriggerMode.Staged
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_RIGHT), lt.full)
        val rt = p.rightTrigger as TriggerMode.Staged
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), rt.full)

        // controller_action (screenshot) is unsupported -> left unbound, not mis-imported
        assertFalse(b.values.any { it.output is ScOutput.None })
    }

    // ---- real Triton: chord_triton.vdf (activators, combos, controller_actions skipped) -------------

    @Test
    fun `triton chord config handles combos activators and skips system actions`() {
        val p = SteamControllerProfileImporter.import(load("chord_triton.vdf"))
        val b = p.buttons

        // button_escape Full_Press has two key_press bindings -> a held Alt+Tab combo (Regular activator)
        val altTab = b[TritonProtocol.BTN_MENU]
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_ALT_L, XKeycode.KEY_TAB)), altTab?.output)
        assertEquals(Activator.Regular, altTab?.activator)

        // face buttons are controller_actions: button_b=quit, button_y=poweroff -> unbound (unsupported);
        // button_x=SHOW_KEYBOARD -> now bound to ScOutput.ShowKeyboard (toggles the on-screen keyboard).
        assertFalse(b.containsKey(TritonProtocol.BTN_B))
        assertEquals(ScOutput.ShowKeyboard, b[TritonProtocol.BTN_X]?.output)
        assertFalse(b.containsKey(TritonProtocol.BTN_Y))
        // button_menu -> sr_enable (controller_action) -> Start-side button unbound
        assertFalse(b.containsKey(TritonProtocol.BTN_VIEW))

        // right_trackpad in absolute_mouse mode -> relative Mouse (Steam's legacy name for the trackpad mouse, NOT a
        // region map — that's mouse_region); its Soft_Press click -> LMB
        assertTrue(p.rightPad is PadMode.Mouse)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), b[TritonProtocol.BTN_RPAD_CLICK]?.output)

        // dpad source: gr_clip (action, skipped) + TAB / RETURN / ESCAPE on the other three directions
        assertFalse(b.containsKey(TritonProtocol.BTN_DPAD_UP)) // gr_clip controller_action
        assertEquals(key(XKeycode.KEY_TAB), b[TritonProtocol.BTN_DPAD_DOWN]?.output)
        assertEquals(key(XKeycode.KEY_ENTER), b[TritonProtocol.BTN_DPAD_RIGHT]?.output)
        assertEquals(key(XKeycode.KEY_ESC), b[TritonProtocol.BTN_DPAD_LEFT]?.output)

        // triggers: edge -> mouse buttons (Staged)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_RIGHT), (p.leftTrigger as TriggerMode.Staged).full)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), (p.rightTrigger as TriggerMode.Staged).full)

        // joystick = dpad-on-stick but all directions are skipped controller_actions -> empty dpad collapses to None;
        // right_joystick = joystick_mouse -> StickMode.Mouse
        assertEquals(StickMode.None, p.leftStick)
        assertTrue(p.rightStick is StickMode.Mouse)
    }

    // ---- the real ToME4 community config: "Delf's Sensible Steam Deck Layout" (controller_neptune) ---------

    @Test
    fun `decodes Delf's real ToME4 Steam Deck community config`() {
        val p = SteamControllerProfileImporter.import(load("delf_tome4_neptune.vdf"))
        val b = p.buttons
        assertTrue("title carried over", p.name.contains("Delf"))

        // base-layer supported parts decode correctly:
        // face buttons (note: Start_Press activator collapses to Regular)
        assertEquals(key(XKeycode.KEY_ENTER), b[TritonProtocol.BTN_A]?.output) // RETURN = Confirm
        assertEquals(Activator.Regular, b[TritonProtocol.BTN_A]?.activator)
        assertEquals(key(XKeycode.KEY_ESC), b[TritonProtocol.BTN_B]?.output)   // ESCAPE = Back/Cancel

        // d-pad -> arrow keys; dpad_north has hold_repeats -> Turbo, dpad_south is plain Regular
        assertEquals(key(XKeycode.KEY_UP), b[TritonProtocol.BTN_DPAD_UP]?.output)
        assertTrue("hold_repeats -> Turbo", b[TritonProtocol.BTN_DPAD_UP]?.activator is Activator.Turbo)
        assertEquals(key(XKeycode.KEY_DOWN), b[TritonProtocol.BTN_DPAD_DOWN]?.output)
        assertEquals(Activator.Regular, b[TritonProtocol.BTN_DPAD_DOWN]?.activator)

        // triggers: edge -> mouse buttons (Staged); left trigger = right-click (targeting per the guide)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_RIGHT), (p.leftTrigger as TriggerMode.Staged).full)
        assertTrue(p.rightTrigger is TriggerMode.Staged)

        // right trackpad = absolute_mouse -> relative Mouse (legacy name for the trackpad mouse; region = mouse_region)
        assertTrue(p.rightPad is PadMode.Mouse)

        //  - left trackpad -> reference -> touch_menu: now imported as a real grid (step-6 selection logic;
        //    overlay HUD still pending). 12 hotkeys 1..= in a 4x3 grid.
        val leftMenu = p.leftPad as PadMode.TouchMenu
        assertEquals(12, leftMenu.slots.size)
        assertEquals(4, leftMenu.cols)
        assertEquals(3, leftMenu.rows)
        assertEquals(key(XKeycode.KEY_1), leftMenu.slots.first().binding.output)
        //  - left stick -> reference -> radial_menu ("Movement (Radial)") -> StickMode.RadialMenu (HOLD default).
        assertTrue(p.leftStick is StickMode.RadialMenu)
        //  - right stick -> dpad-mode group -> stick-as-dpad (was dropped to None before); up/down = mouse-wheel scroll
        val rd = p.rightStick as StickMode.DPad
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_UP), rd.up)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_DOWN), rd.down)
    }

    // ---- importing ALL action sets (foundation for runtime set-switching, step 3) -------------------------

    @Test
    fun `importActionSets decodes every action set in a config`() {
        // KSP: 6 game-context action sets (titles are localisation tokens -> keyed by set name)
        val ksp = SteamControllerProfileImporter.importActionSets(load("ksp_worstcase_xboxone.vdf"))
        assertEquals(6, ksp.size)
        assertTrue(ksp.containsKey("FlightControls"))
        assertTrue(ksp.containsKey("EVAControls"))
        ksp.values.forEach { assertTrue("each set binds several buttons", it.buttons.size >= 4) }

        // Delf's ToME4: 2 action sets with friendly titles "Main" / "Extra Touch Menus"
        val delf = SteamControllerProfileImporter.importActionSets(load("delf_tome4_neptune.vdf"))
        assertEquals(setOf("Main", "Extra Touch Menus"), delf.keys)
        // the "Main" set matches the default base import (A = RETURN/Confirm)
        assertEquals(key(XKeycode.KEY_ENTER), delf["Main"]?.buttons?.get(TritonProtocol.BTN_A)?.output)

        // a config with no `actions` block -> a single "Default" entry
        val sink = SteamControllerProfileImporter.importActionSets(load("kitchensink_v3.vdf"))
        assertEquals(setOf("Default"), sink.keys)
    }

    @Test
    fun `importConfig decodes sets keyed by preset id with CHANGE_PRESET switches`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("actionsets_v3.vdf"))
        assertEquals(setOf("0", "1"), cfg.sets.keys)
        assertEquals("0", cfg.defaultSetId)
        // Default set (id 0): A -> Q; right_bumper presses-edge switch to set 1
        assertEquals(key(XKeycode.KEY_Q), cfg.sets["0"]?.buttons?.get(TritonProtocol.BTN_A)?.output)
        assertEquals(
            ScOutput.SwitchActionSet("1", onRelease = false),
            cfg.sets["0"]?.buttons?.get(TritonProtocol.BTN_RBUMPER)?.output,
        )
        // Menus set (id 1): A -> M; right_bumper release-edge switch back to set 0
        assertEquals(key(XKeycode.KEY_M), cfg.sets["1"]?.buttons?.get(TritonProtocol.BTN_A)?.output)
        assertEquals(
            ScOutput.SwitchActionSet("0", onRelease = true),
            cfg.sets["1"]?.buttons?.get(TritonProtocol.BTN_RBUMPER)?.output,
        )
    }

    @Test
    fun `hold_repeats imports as Turbo using repeat_rate as ms not a machine-gun interval`() {
        // Regression for the d-pad "up jumps straight to fast-repeat" bug: ToME4 binds d-pad up with
        // hold_repeats (-> Turbo) and a repeat_rate of hundreds of ms. The interval must be that many ms, not
        // collapsed to the ~10ms floor by mis-reading repeat_rate as a frequency.
        val cfg = SteamControllerProfileImporter.importConfig(load("delf_tome4_neptune.vdf"))
        val up = cfg.sets.getValue("0").buttons[TritonProtocol.BTN_DPAD_UP]
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_UP)), up?.output)
        val act = up?.activator
        assertTrue("d-pad up should import as Turbo (it has hold_repeats)", act is Activator.Turbo)
        val interval = (act as Activator.Turbo).intervalMs
        assertTrue("turbo interval ($interval ms) must be a sane repeat, not a ~10ms machine-gun", interval >= 100)
    }

    @Test
    fun `importConfig handles the real ToME4 config with no dangling switch targets`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("delf_tome4_neptune.vdf"))
        assertEquals(setOf("0", "1"), cfg.sets.keys) // Default + "Extra Touch Menus"
        assertEquals("0", cfg.defaultSetId)
        // every CHANGE_PRESET switch we imported resolves to a real set (1-based -> id N-1 mapping is correct)
        val switches = cfg.sets.values
            .flatMap { it.buttons.values }
            .mapNotNull { it.output as? ScOutput.SwitchActionSet }
        assertTrue("CHANGE_PRESET targets must all resolve", switches.all { cfg.sets.containsKey(it.targetSetId) })
    }

    @Test
    fun `importConfig parses layer ops and per-set sources`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("actionlayers_v3.vdf"))
        val base = cfg.sets.getValue("0").buttons
        // add_layer/hold_layer/remove_layer 2 -> layer preset id 1 (1-based)
        assertEquals(ScOutput.LayerOp("1", LayerOpType.HOLD), base[TritonProtocol.BTN_RBUMPER]?.output)
        assertEquals(ScOutput.LayerOp("1", LayerOpType.ADD), base[TritonProtocol.BTN_LBUMPER]?.output)
        assertEquals(ScOutput.LayerOp("1", LayerOpType.REMOVE), base[TritonProtocol.BTN_VIEW]?.output)
        // the layer (id 1) overrides ONLY the button_diamond source -> partial overlay
        assertEquals(setOf("button_diamond"), cfg.setSources["1"])
    }

    @Test
    fun `importConfig parses mode_shift and decodes its single-source overlay`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("modeshift_v3.vdf"))
        // the right_bumper binding is a mode_shift of the button_diamond source to group 1
        assertEquals(
            ScOutput.ModeShift("button_diamond", "1"),
            cfg.sets.getValue("0").buttons[TritonProtocol.BTN_RBUMPER]?.output,
        )
        // group 1 decoded as button_diamond -> A becomes M in the overlay
        assertEquals(key(XKeycode.KEY_M), cfg.shiftOverlays["1"]?.buttons?.get(TritonProtocol.BTN_A)?.output)
    }

    @Test
    fun `on-phone engine smoke config decodes all three step-3 mechanisms`() {
        val cfg = SteamControllerProfileImporter.importConfig(app.gamenative.steamcontroller.SMOKE_CONFIG)
        assertEquals(setOf("0", "1", "2"), cfg.sets.keys) // Default, Combat, Overlay(layer)
        assertEquals("0", cfg.defaultSetId)
        val base = cfg.sets.getValue("0").buttons
        assertEquals(key(XKeycode.KEY_1), base[TritonProtocol.BTN_A]?.output)
        assertEquals(ScOutput.LayerOp("2", LayerOpType.HOLD), base[TritonProtocol.BTN_LBUMPER]?.output)   // hold_layer 3 -> id 2
        assertEquals(ScOutput.SwitchActionSet("1"), base[TritonProtocol.BTN_RBUMPER]?.output)              // CHANGE_PRESET 2 -> id 1
        assertEquals(ScOutput.ModeShift("button_diamond", "1"), base[TritonProtocol.BTN_L4]?.output)       // back_left
        assertEquals(key(XKeycode.KEY_3), cfg.sets.getValue("1").buttons[TritonProtocol.BTN_A]?.output)    // Combat
        assertEquals(key(XKeycode.KEY_8), cfg.sets.getValue("2").buttons[TritonProtocol.BTN_A]?.output)    // Overlay layer
        assertEquals(key(XKeycode.KEY_9), cfg.shiftOverlays["1"]?.buttons?.get(TritonProtocol.BTN_A)?.output) // shift target
    }

    @Test
    fun `importConfig resolves real KSP layer ops to existing presets`() {
        val cfg = SteamControllerProfileImporter.importConfig(load("ksp_worstcase_xboxone.vdf"))
        val layerOps = cfg.sets.values.flatMap { it.buttons.values }.mapNotNull { it.output as? ScOutput.LayerOp }
        assertTrue("KSP uses layer ops", layerOps.isNotEmpty())
        // add_layer 7..10 -> layer preset ids 6..9 (1-based); all must resolve to real presets
        assertTrue("every layer op targets an existing preset", layerOps.all { cfg.sets.containsKey(it.layerId) })
    }

    // ---- synthetic kitchen-sink: one of EVERY activator + EVERY binding command, with known outputs --------

    @Test
    fun `kitchen-sink config exercises every activator and binding command`() {
        val p = SteamControllerProfileImporter.import(load("kitchensink_v3.vdf"))
        val b = p.buttons

        // ---- every activator type maps correctly ----
        assertEquals(Activator.Regular, b[TritonProtocol.BTN_A]?.activator)               // Full_Press
        assertTrue(b[TritonProtocol.BTN_B]?.activator is Activator.DoublePress)           // Double_Press
        assertEquals(250L, (b[TritonProtocol.BTN_B]?.activator as Activator.DoublePress).windowMs)
        assertTrue(b[TritonProtocol.BTN_X]?.activator is Activator.LongPress)             // Long_Press
        assertEquals(600L, (b[TritonProtocol.BTN_X]?.activator as Activator.LongPress).holdMs)
        assertTrue(b[TritonProtocol.BTN_Y]?.activator is Activator.Turbo)                 // hold_repeats -> Turbo
        assertEquals(Activator.Regular, b[TritonProtocol.BTN_MENU]?.activator)            // Start_Press -> Regular
        assertEquals(Activator.OnRelease, b[TritonProtocol.BTN_VIEW]?.activator)          // release -> OnRelease (fire on let-go)
        assertEquals(Activator.Regular, b[TritonProtocol.BTN_LBUMPER]?.activator)         // Soft_Press -> Regular

        // ---- every binding command maps correctly ----
        // key_press combo (two bindings merged) + single key
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_CTRL_L, XKeycode.KEY_C)), b[TritonProtocol.BTN_A]?.output)
        assertEquals(key(XKeycode.KEY_X), b[TritonProtocol.BTN_X]?.output)
        // mouse_button + mouse_wheel
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_MIDDLE), b[TritonProtocol.BTN_LBUMPER]?.output)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_SCROLL_UP), b[TritonProtocol.BTN_DPAD_RIGHT]?.output)
        // xinput_button: face / dpad / stick-click
        assertEquals(ScOutput.GamepadButton(ExternalController.IDX_BUTTON_A.toInt()), b[TritonProtocol.BTN_L5]?.output)
        assertEquals(ScOutput.GamepadDpad(0), b[TritonProtocol.BTN_DPAD_UP]?.output)
        assertEquals(ScOutput.GamepadButton(ExternalController.IDX_BUTTON_L3.toInt()), b[TritonProtocol.BTN_L3]?.output)
        // key-combo on a d-pad direction
        assertEquals(ScOutput.Key(listOf(XKeycode.KEY_SHIFT_L, XKeycode.KEY_TAB)), b[TritonProtocol.BTN_DPAD_LEFT]?.output)

        // ---- controller_action (system) + game_action are unsupported -> SKIPPED (not mis-bound) ----
        assertFalse("controller_action skipped", b.containsKey(TritonProtocol.BTN_RBUMPER))
        assertFalse("game_action skipped", b.containsKey(TritonProtocol.BTN_L4))
        // mode_shift is handled (step 3) -> a ModeShift output carrying the raw target group id
        assertEquals(ScOutput.ModeShift("dpad", "99"), b[TritonProtocol.BTN_R4]?.output)
        assertFalse("no None leaked", b.values.any { it.output is ScOutput.None })

        // ---- analog sources (incl. per-group settings: deadzone / invert_y / sensitivity) ----
        // left stick: deadzone 5000 (raw/32768) + invert_y 0
        assertEquals(StickMode.JoystickMove(Stick.LEFT, invertY = false, deadzone = 5000f / 32768f), p.leftStick)
        assertEquals(StickMode.JoystickMove(Stick.RIGHT), p.rightStick)            // via reference -> group 10 (defaults)
        assertEquals(ScOutput.GamepadButton(ExternalController.IDX_BUTTON_R3.toInt()), b[TritonProtocol.BTN_R3]?.output)
        assertTrue(p.leftPad is PadMode.ScrollWheel)
        // right pad: base absolute_mouse (-> relative Mouse) wins over modeshift dpad; invert_y 0 read from settings
        val rp = p.rightPad as PadMode.Mouse
        assertEquals(false, rp.invertY)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_LEFT), b[TritonProtocol.BTN_RPAD_CLICK]?.output)
        assertEquals(ScOutput.MouseButton(Pointer.Button.BUTTON_RIGHT), (p.leftTrigger as TriggerMode.Staged).full)
        assertEquals(TriggerMode.Axis(TriggerAxis.GAMEPAD_R2), p.rightTrigger) // analog xinput TRIGGER_RIGHT
    }

    // ---- worst case: a 226-group, 10-preset, 13-mode, mode-shift-heavy real config (KSP) -------------------

    @Test
    fun `worst-case KSP config imports every action set without choking`() {
        val vdf = load("ksp_worstcase_xboxone.vdf")
        // 6 game-context action sets, each a separate preset/group_source_bindings over 226 groups.
        val actionSets = listOf(
            "MenuControls", "FlightControls", "DockingControls", "EditorControls", "MapControls", "EVAControls",
        )
        var sawPadMode = false
        for (set in actionSets) {
            val p = SteamControllerProfileImporter.import(vdf, presetName = set)
            assertTrue("$set: title", p.name.contains("KSP"))
            // each context binds a real chunk of buttons
            assertTrue("$set: expected several bound buttons, got ${p.buttons.size}", p.buttons.size >= 4)
            // robustness: unsupported bindings (mode_shift / controller_action) are skipped, never leaked as None
            assertFalse("$set: ScOutput.None leaked into buttons", p.buttons.values.any { it.output is ScOutput.None })
            if (p.leftPad != PadMode.None || p.rightPad != PadMode.None) sawPadMode = true
        }
        // the rich analog mode set (absolute_mouse / mouse_region / …) decodes to at least one pad mode somewhere
        assertTrue("expected at least one pad mode across the action sets", sawPadMode)
    }

    // ---- mode-shift / action-layer base-layer-wins regression (real community configs use this heavily) ----

    @Test
    fun `mode-shift layer does not clobber the base binding`() {
        // right_trackpad has a base group (absolute_mouse) AND a "active modeshift" group (dpad). The base must
        // win; the mode-shift variant is deferred (action-set/mode-shift feature). Mirrors ToME4 / Dying Light.
        val vdf = """
            "controller_mappings"
            {
              "group" { "id" "0" "mode" "four_buttons"
                "inputs" { "button_a" { "activators" { "Full_Press" { "bindings" { "binding" "key_press Q" } } } } } }
              "group" { "id" "1" "mode" "absolute_mouse"
                "inputs" { "click" { "activators" { "Full_Press" { "bindings" { "binding" "mouse_button LEFT" } } } } } }
              "group" { "id" "2" "mode" "dpad"
                "inputs" { "dpad_north" { "activators" { "Full_Press" { "bindings" { "binding" "key_press W" } } } } } }
              "preset" { "id" "0" "name" "Default"
                "group_source_bindings"
                {
                  "0" "button_diamond active"
                  "1" "right_trackpad active"
                  "2" "right_trackpad active modeshift"
                } }
            }
        """.trimIndent()

        val p = SteamControllerProfileImporter.import(vdf)
        // base right_trackpad group (absolute_mouse -> relative Mouse) wins, not the mode-shift dpad group
        assertTrue("base absolute_mouse should win over the modeshift dpad group", p.rightPad is PadMode.Mouse)
        assertEquals(key(XKeycode.KEY_Q), p.buttons[TritonProtocol.BTN_A]?.output)
    }
}
