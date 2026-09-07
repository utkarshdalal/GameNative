package com.winlator.inputcontrols

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class PhysicalControllerStickTuningTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mock()
        whenever(context.filesDir).thenReturn(temporaryFolder.root)
    }

    @Test
    fun `deadzone supports the full zero to one range and clamps profile values`() {
        val profile = ControlsProfile(context, 1)

        profile.leftStickDeadzone = -0.5f
        profile.rightStickDeadzone = 1.5f

        assertEquals(0f, profile.leftStickDeadzone, 0f)
        assertEquals(1f, profile.rightStickDeadzone, 0f)
        assertEquals(0.42f, ControlsProfile.applyStickDeadzone(0.42f, 0f), 0.0001f)
        assertEquals(0f, ControlsProfile.applyStickDeadzone(1f, 1f), 0f)
    }

    @Test
    fun `deadzone rescales both directions and sensitivity saturates output`() {
        assertEquals(0f, ControlsProfile.applyStickDeadzone(0.2f, 0.2f), 0f)
        assertEquals(0.5f, ControlsProfile.applyStickDeadzone(0.6f, 0.2f), 0.0001f)
        assertEquals(-0.5f, ControlsProfile.applyStickDeadzone(-0.6f, 0.2f), 0.0001f)
        assertEquals(0.75f, ExternalController.tuneStickAxis(0.6f, 0.2f, 1.5f), 0.0001f)
        assertEquals(1f, ExternalController.tuneStickAxis(0.6f, 0.2f, 3f), 0f)
        assertEquals(0f, ExternalController.tuneStickAxis(Float.NaN, 0.2f, 1f), 0f)
    }

    @Test
    fun `an unreported Joy-Con axis retains its already tuned value`() {
        assertEquals(
            0.65f,
            ExternalController.resolveStickAxis(false, 0.65f, -0.6f, 0.2f, 1f),
            0f,
        )
        assertEquals(
            -0.5f,
            ExternalController.resolveStickAxis(true, 0.65f, -0.6f, 0.2f, 1f),
            0.0001f,
        )
    }

    @Test
    fun `legacy profiles keep defaults and fields are read independent of JSON order`() {
        val legacy = load("""{"id":7,"name":"Legacy","elements":[]}""")
        assertEquals(1f, legacy.cursorSpeed, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE, legacy.leftStickDeadzone, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE, legacy.rightStickDeadzone, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_SENSITIVITY, legacy.leftStickSensitivity, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_SENSITIVITY, legacy.rightStickSensitivity, 0f)

        val reordered = load(
            """{
                "elements":[],
                "controllers":[],
                "id":8,
                "name":"Reordered",
                "rightStickSensitivity":1.8,
                "leftStickDeadzone":0.05,
                "rightStickDeadzone":0.3,
                "leftStickSensitivity":1.2,
                "cursorSpeed":1.4
            }""".trimIndent(),
        )
        assertEquals(1.4f, reordered.cursorSpeed, 0.0001f)
        assertEquals(0.05f, reordered.leftStickDeadzone, 0.0001f)
        assertEquals(0.3f, reordered.rightStickDeadzone, 0.0001f)
        assertEquals(1.2f, reordered.leftStickSensitivity, 0.0001f)
        assertEquals(1.8f, reordered.rightStickSensitivity, 0.0001f)
    }

    @Test
    fun `stick tuning persists through profile save and load`() {
        val profile = ControlsProfile(context, 9).apply {
            name = "Tuned"
            leftStickDeadzone = 0f
            rightStickDeadzone = 0.35f
            leftStickSensitivity = 0.7f
            rightStickSensitivity = 2.4f
            save()
        }

        val loaded = checkNotNull(
            InputControlsManager.loadProfile(context, ControlsProfile.getProfileFile(context, profile.id)),
        )
        assertEquals(0f, loaded.leftStickDeadzone, 0f)
        assertEquals(0.35f, loaded.rightStickDeadzone, 0.0001f)
        assertEquals(0.7f, loaded.leftStickSensitivity, 0.0001f)
        assertEquals(2.4f, loaded.rightStickSensitivity, 0.0001f)
    }

    private fun load(json: String): ControlsProfile {
        return checkNotNull(
            InputControlsManager.loadProfile(
                context,
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            ),
        )
    }
}
