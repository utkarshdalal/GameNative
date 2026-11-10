package app.gamenative.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SteamControllerVdfUtilsTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private fun generate(vdf: String): Map<String, List<String>> {
        val outDir = tmpDir.newFolder("controller").toPath()
        SteamControllerVdfUtils.generateControllerConfig(vdf, outDir)
        return outDir.toFile().listFiles().orEmpty().associate { file ->
            file.nameWithoutExtension to file.readLines().filter { it.isNotBlank() }
        }
    }

    // Portal 2's PS5 layout: Move is a joystick_move stick, Camera a joystick_mouse stick whose click is "use".
    private val portal2Like = """
        "controller_mappings" { "version" "3" "controller_type" "controller_ps5"
          "actions" { "FPSControls" { "title" "#Set_FPSControls" } }
          "group" { "id" "1" "mode" "joystick_move" "inputs" {}
            "gameactions" { "FPSControls" "Move" } }
          "group" { "id" "35" "mode" "joystick_mouse"
            "inputs" { "click" { "activators" { "Full_Press" { "bindings" { "binding" "game_action FPSControls use" } } } } }
            "settings" { "output_joystick" "2" "sensitivity" "555" }
            "gameactions" { "FPSControls" "Camera" } }
          "preset" { "id" "0" "name" "FPSControls"
            "group_source_bindings" { "1" "joystick active" "35" "right_joystick active" } } }
    """.trimIndent()

    @Test
    fun `a joystick_mouse right stick reaches the emulator as a camera stick`() {
        val sets = generate(portal2Like)
        val fps = sets.getValue("FPSControls")
        assertTrue(fps.toString(), "Move=LJOY=joystick_move" in fps)
        assertTrue(fps.toString(), "Camera=RJOY=joystick_camera" in fps)
        assertTrue(fps.toString(), "use=RSTICK" in fps)
    }

    @Test
    fun `a joystick_camera left stick keeps its own axes`() {
        val vdf = portal2Like
            .replace("\"mode\" \"joystick_mouse\"", "\"mode\" \"joystick_camera\"")
            .replace("\"1\" \"joystick active\" \"35\" \"right_joystick active\"", "\"35\" \"joystick active\"")
        assertEquals(listOf("Camera=LJOY=joystick_camera", "use=LSTICK"), generate(vdf).getValue("FPSControls"))
    }

    @Test
    fun `a dpad source in a camera mode gets no stick binding`() {
        val sets = generate(portal2Like.replace("\"35\" \"right_joystick active\"", "\"35\" \"dpad active\""))
        assertEquals(listOf("Move=LJOY=joystick_move"), sets.getValue("FPSControls"))
    }

    @Test
    fun `an outer ring binding on a camera stick does not become the stick button`() {
        val vdf = portal2Like.replace(
            "\"inputs\" { \"click\" { \"activators\" { \"Full_Press\" { \"bindings\" { \"binding\" \"game_action FPSControls use\" } } } } }",
            "\"inputs\" { \"edge\" { \"activators\" { \"Full_Press\" { \"bindings\" { \"binding\" \"game_action FPSControls zoom\" } } } } }",
        )
        assertEquals(listOf("Move=LJOY=joystick_move", "Camera=RJOY=joystick_camera"), generate(vdf).getValue("FPSControls"))
    }
}
