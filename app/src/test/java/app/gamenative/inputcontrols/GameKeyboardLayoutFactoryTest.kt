package app.gamenative.inputcontrols

import app.gamenative.enums.ControllerSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameKeyboardLayoutFactoryTest {
    @Test
    fun resolveStylePrefersFpsAndRtsMarkers() {
        assertEquals(
            GameKeyboardLayoutFactory.Style.FPS,
            GameKeyboardLayoutFactory.resolveStyle(listOf("Action", "FPS", "Multiplayer")),
        )
        assertEquals(
            GameKeyboardLayoutFactory.Style.RTS,
            GameKeyboardLayoutFactory.resolveStyle(listOf("Strategy", "Real-Time Strategy")),
        )
    }

    @Test
    fun fullControllerSupportDefaultsToGamepadUnlessKeyboardFirstTags() {
        assertEquals(
            GameKeyboardLayoutFactory.Style.GAMEPAD,
            GameKeyboardLayoutFactory.resolveStyle(
                tags = listOf("Action", "Indie"),
                controllerSupport = ControllerSupport.full,
            ),
        )
        assertEquals(
            GameKeyboardLayoutFactory.Style.KEYBOARD_MOUSE,
            GameKeyboardLayoutFactory.resolveStyle(
                tags = listOf("Online Co-Op", "Adventure"),
                controllerSupport = ControllerSupport.full,
            ),
        )
    }

    @Test
    fun generatedKeyboardMouseProfileValidates() {
        val json = GameKeyboardLayoutFactory.buildWorkingProfileJson(
            profileId = 0,
            containerId = "steam-123",
            style = GameKeyboardLayoutFactory.Style.KEYBOARD_MOUSE,
            gameName = "How to Fish",
        )
        ControlProfileService.validate(json)
        assertTrue(json.getJSONArray("elements").length() >= 8)
        assertTrue(json.getString("name").contains("Auto KBM"))
    }

    @Test
    fun generatedFpsProfileIncludesShooterMode() {
        val json = GameKeyboardLayoutFactory.buildWorkingProfileJson(
            profileId = 0,
            containerId = "steam-456",
            style = GameKeyboardLayoutFactory.Style.FPS,
        )
        ControlProfileService.validate(json)
        val types = buildList {
            val elements = json.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                add(elements.getJSONObject(i).getString("type"))
            }
        }
        assertTrue(types.contains("SHOOTER_MODE"))
    }
}
