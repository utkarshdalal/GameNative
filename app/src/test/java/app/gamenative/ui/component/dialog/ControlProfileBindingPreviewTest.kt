package app.gamenative.ui.component.dialog

import android.app.Application
import android.view.KeyEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlProfileBindingPreviewTest {
    @Test
    fun previewUsesWildcardControllerAndFirstRadialMenu() {
        val profile = JSONObject(
            """{
                "controllers":[
                    {"id":"device","controllerBindings":[{"keyCode":96,"binding":"KEY_X"}]},
                    {"id":"*","controllerBindings":[
                        {"keyCode":${KeyEvent.KEYCODE_BUTTON_A},"binding":"KEY_SPACE"},
                        {"keyCode":-1,"bindings":["KEY_CTRL_L","KEY_S"],"mode":"sequence","sequenceDelayMs":220}
                    ]}
                ],
                "radialMenus":[
                    {"slots":[{"label":"Inventory","binding":"KEY_I"},{"label":"","binding":"NONE"}]},
                    {"slots":[{"label":"Ignored","binding":"KEY_X"}]}
                ]
            }""".trimIndent(),
        )

        val physical = physicalProfileBindingRows(profile)
        assertEquals(2, physical.size)
        assertTrue(physical[0].label.isNotBlank())
        assertEquals("AXIS X-", physical[1].label)
        assertEquals("SPACE", physical[0].binding.toString())
        assertTrue(physical[1].binding.isSequence)
        assertEquals(220, physical[1].binding.sequenceDelayMs)

        val radial = radialProfileBindingRows(profile)
        assertEquals(listOf("Inventory", ""), radial.map { it.label })
        assertEquals("I", radial[0].binding.toString())
        assertTrue(radial[1].binding.isEmpty)
    }

    @Test
    fun previewFallsBackToFirstControllerAndHandlesEmptySections() {
        val profile = JSONObject(
            """{"controllers":[{"id":"device","controllerBindings":[{"keyCode":96,"binding":"KEY_A"}]}]}""",
        )

        assertEquals("A", physicalProfileBindingRows(profile).single().binding.toString())
        assertTrue(radialProfileBindingRows(profile).isEmpty())
        assertTrue(physicalProfileBindingRows(JSONObject()).isEmpty())
    }
}
