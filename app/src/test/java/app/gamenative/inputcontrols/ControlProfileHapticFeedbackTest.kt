package app.gamenative.inputcontrols

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileHapticFeedbackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `controls have haptic feedback off by default and leave it out of the profile`() {
        val view = InputControlsView(context).apply { layout(0, 0, 600, 300) }
        val element = ControlElement(view)

        assertFalse(element.isHapticFeedback)
        assertEquals(ControlElement.DEFAULT_HAPTIC_STRENGTH, element.hapticStrength)
        val json = element.toJSONObject()
        assertFalse(json.has("hapticFeedback"))
        assertFalse(json.has("hapticStrength"))
    }

    @Test
    fun `haptic settings survive saving and loading the profile`() {
        val json = profile(JSONObject().put("hapticFeedback", true).put("hapticStrength", 70))
        ControlProfileService.validate(json)
        val imported = ControlProfileService.installImported(InputControlsManager(context), ControlProfileService.preview(json))
        try {
            val view = InputControlsView(context)
            view.layout(0, 0, 600, 300)
            imported.loadElements(view)
            val element = imported.elements.single()
            assertTrue(element.isHapticFeedback)
            assertEquals(70, element.hapticStrength)

            element.hapticStrength = 30
            assertTrue(imported.save())
            val saved = ControlProfileService.readProfileJson(context, imported)
            val savedElement = saved.getJSONArray("elements").getJSONObject(0)
            assertTrue(savedElement.getBoolean("hapticFeedback"))
            assertEquals(30, savedElement.getInt("hapticStrength"))
            ControlProfileService.validate(saved)
        } finally {
            ControlsProfile.getProfileFile(context, imported.id).delete()
        }
    }

    @Test
    fun `import rejects a strength the slider can't set`() {
        listOf(0, ControlElement.MIN_HAPTIC_STRENGTH - 1, ControlElement.MAX_HAPTIC_STRENGTH + 1).forEach { strength ->
            assertThrows(IllegalArgumentException::class.java) {
                ControlProfileService.validate(profile(JSONObject().put("hapticStrength", strength)))
            }
        }
    }

    @Test
    fun `strength set in code stays within the slider range`() {
        val element = ControlElement(null)

        element.hapticStrength = 0
        assertEquals(ControlElement.MIN_HAPTIC_STRENGTH, element.hapticStrength)
        element.hapticStrength = 500
        assertEquals(ControlElement.MAX_HAPTIC_STRENGTH, element.hapticStrength)
    }

    private fun profile(haptics: JSONObject): JSONObject {
        val button = JSONObject()
            .put("type", "BUTTON")
            .put("shape", "CIRCLE")
            .put("bindings", JSONArray().put("KEY_A"))
            .put("x", 0.5)
            .put("y", 0.5)
            .put("scale", 1.0)
            .put("toggleSwitch", false)
            .put("text", "A")
            .put("iconId", 0)
        haptics.keys().forEach { button.put(it, haptics.get(it)) }
        return JSONObject()
            .put("name", "Haptic profile")
            .put("schemaVersion", 1)
            .put("includedSections", JSONArray().put("onScreen"))
            .put("elements", JSONArray().put(button))
    }
}
