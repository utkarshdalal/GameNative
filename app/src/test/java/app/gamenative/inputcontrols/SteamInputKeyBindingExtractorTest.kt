package app.gamenative.inputcontrols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SteamInputKeyBindingExtractorTest {
    private fun load(name: String): String =
        (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
            .getResourceAsStream("sc/$name")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("missing test resource sc/$name")

    @Test
    fun wasdTemplateYieldsMoveInteractAndMouseLook() {
        val keys = SteamInputKeyBindingExtractor.extractFromVdf(load("controller_xboxone_wasd.vdf"))
        assertNotNull(keys)
        requireNotNull(keys)
        assertEquals(listOf("KEY_W", "KEY_D", "KEY_S", "KEY_A"), keys.move)
        assertTrue(keys.actions.any { it.binding == "KEY_E" })
        assertTrue(keys.actions.any { it.binding == "KEY_SPACE" })
        assertTrue(keys.actions.any { it.binding == "KEY_SHIFT_L" })
        assertTrue(keys.hasMouseLook)
        assertTrue(keys.hasKeyboardKeys)
    }

    @Test
    fun gamepadOnlyStickDpadStillCountsAsKeyboard() {
        val vdf = """
            "controller_mappings" {
              "version" "3"
              "controller_type" "controller_trng"
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
        val keys = SteamInputKeyBindingExtractor.extractFromVdf(vdf)
        assertNotNull(keys)
        assertEquals(listOf("KEY_W", "KEY_D", "KEY_S", "KEY_A"), keys!!.move)
    }

    @Test
    fun factoryMergesSteamMoveIntoGeneratedProfile() {
        val discovered = SteamInputKeyBindingExtractor.DiscoveredKeys(
            move = listOf("KEY_W", "KEY_D", "KEY_S", "KEY_A"),
            actions = listOf(
                SteamInputKeyBindingExtractor.LabeledBinding("E", "KEY_E"),
                SteamInputKeyBindingExtractor.LabeledBinding("SPC", "KEY_SPACE"),
            ),
            hasMouseLook = true,
        )
        val json = GameKeyboardLayoutFactory.buildWorkingProfileJson(
            profileId = 0,
            containerId = "steam-1",
            style = GameKeyboardLayoutFactory.Style.KEYBOARD_MOUSE,
            gameName = "Test",
            discovered = discovered,
        )
        ControlProfileService.validate(json)
        assertTrue(json.getString("name").contains("Steam"))
        val dpad = (0 until json.getJSONArray("elements").length())
            .map { json.getJSONArray("elements").getJSONObject(it) }
            .first { it.getString("type") == "D_PAD" }
        assertEquals("KEY_W", dpad.getJSONArray("bindings").getString(0))
    }
}
