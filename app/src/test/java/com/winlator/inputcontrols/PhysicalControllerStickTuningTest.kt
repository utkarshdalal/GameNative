package com.winlator.inputcontrols

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `axial deadzone retains existing behavior and sensitivity saturates output`() {
        assertEquals(0f, ControlsProfile.applyStickDeadzone(0.2f, 0.2f), 0f)
        assertEquals(0.5f, ControlsProfile.applyStickDeadzone(0.6f, 0.2f), 0.0001f)
        assertEquals(-0.5f, ControlsProfile.applyStickDeadzone(-0.6f, 0.2f), 0.0001f)
        val tuned = StickVectorProcessor.tune(
            0.6f,
            -0.6f,
            0.2f,
            1.5f,
            ControlsProfile.StickDeadzoneMode.AXIAL,
        )
        assertEquals(0.75f, tuned.x, 0.0001f)
        assertEquals(-0.75f, tuned.y, 0.0001f)
        assertEquals(
            1f,
            StickVectorProcessor.tune(
                0.6f,
                0f,
                0.2f,
                3f,
                ControlsProfile.StickDeadzoneMode.AXIAL,
            ).x,
            0f,
        )
        assertEquals(
            0f,
            StickVectorProcessor.tune(
                Float.NaN,
                0f,
                0.2f,
                1f,
                ControlsProfile.StickDeadzoneMode.AXIAL,
            ).x,
            0f,
        )
    }

    @Test
    fun `circular deadzone rescales magnitude and preserves direction`() {
        val tuned = StickVectorProcessor.tune(
            0.3f,
            0.4f,
            0.2f,
            1f,
            ControlsProfile.StickDeadzoneMode.CIRCULAR,
        )
        assertEquals(0.225f, tuned.x, 0.0001f)
        assertEquals(0.3f, tuned.y, 0.0001f)

        val atBoundary = StickVectorProcessor.tune(
            0.3f,
            0.4f,
            0.5f,
            1f,
            ControlsProfile.StickDeadzoneMode.CIRCULAR,
        )
        assertEquals(0f, atBoundary.x, 0f)
        assertEquals(0f, atBoundary.y, 0f)
    }

    @Test
    fun `circular sensitivity clamps to the unit circle`() {
        val tuned = StickVectorProcessor.tune(
            0.6f,
            0.8f,
            0f,
            2f,
            ControlsProfile.StickDeadzoneMode.CIRCULAR,
        )
        assertEquals(0.6f, tuned.x, 0.0001f)
        assertEquals(0.8f, tuned.y, 0.0001f)
        assertEquals(1.0, Math.hypot(tuned.x.toDouble(), tuned.y.toDouble()), 0.0001)
    }

    @Test
    fun `sensitivity above one amplifies partial travel in serialized gamepad output`() {
        val normal = StickVectorProcessor.tune(
            0.4f,
            0f,
            0f,
            1f,
            ControlsProfile.StickDeadzoneMode.CIRCULAR,
        )
        val amplified = StickVectorProcessor.tune(
            0.4f,
            0f,
            0f,
            2f,
            ControlsProfile.StickDeadzoneMode.CIRCULAR,
        )

        assertEquals(0.4f, normal.x, 0.0001f)
        assertEquals(0.8f, amplified.x, 0.0001f)
        assertTrue(GamepadState.encodeThumbAxis(amplified.x) > GamepadState.encodeThumbAxis(normal.x))
    }

    @Test
    fun `hybrid deadzone creates axial corridors while retaining diagonals`() {
        val nearHorizontal = StickVectorProcessor.tune(
            0.8f,
            0.05f,
            0.2f,
            1f,
            ControlsProfile.StickDeadzoneMode.HYBRID,
        )
        assertTrue(nearHorizontal.x > 0f)
        assertEquals(0f, nearHorizontal.y, 0f)

        val diagonal = StickVectorProcessor.tune(
            0.6f,
            0.6f,
            0.2f,
            1f,
            ControlsProfile.StickDeadzoneMode.HYBRID,
        )
        assertTrue(diagonal.x > 0f)
        assertTrue(diagonal.y > 0f)
        assertEquals(diagonal.x, diagonal.y, 0.0001f)
    }

    @Test
    fun `four way snapping uses cardinal sectors with angular hysteresis`() {
        val mode = ControlsProfile.StickDigitalMode.FOUR_WAY
        val right = StickVectorProcessor.snapDirection(
            vectorX(44.0),
            vectorY(44.0),
            mode,
            StickVectorProcessor.DIRECTION_NONE,
        )
        assertEquals(0, right)
        val snapped = StickVectorProcessor.snapToDirection(0.48f, 0.36f, right)
        assertEquals(0.6f, snapped.x, 0.0001f)
        assertEquals(0f, snapped.y, 0f)
        assertEquals(
            right,
            StickVectorProcessor.snapDirection(vectorX(48.0), vectorY(48.0), mode, right),
        )
        assertEquals(
            2,
            StickVectorProcessor.snapDirection(vectorX(52.0), vectorY(52.0), mode, right),
        )
    }

    @Test
    fun `eight way snapping emits diagonals and unrestricted mode does not snap`() {
        val diagonal = StickVectorProcessor.snapDirection(
            vectorX(30.0),
            vectorY(30.0),
            ControlsProfile.StickDigitalMode.EIGHT_WAY,
            StickVectorProcessor.DIRECTION_NONE,
        )
        assertEquals(1, diagonal)
        val snapped = StickVectorProcessor.snapToDirection(0.48f, 0.36f, diagonal)
        assertEquals(0.4243f, snapped.x, 0.0001f)
        assertEquals(0.4243f, snapped.y, 0.0001f)
        assertEquals(
            StickVectorProcessor.DIRECTION_NONE,
            StickVectorProcessor.snapDirection(
                1f,
                1f,
                ControlsProfile.StickDigitalMode.UNRESTRICTED,
                diagonal,
            ),
        )
    }

    @Test
    fun `external controller snaps complete stick output and preserves strength`() {
        val controller = ExternalController()
        val input = StickVectorProcessor.Vector(0.48f, 0.36f)

        val fourWay = controller.applyDirectionSnapping(
            input,
            ControlsProfile.StickDigitalMode.FOUR_WAY,
            false,
        )
        assertEquals(0.6f, fourWay.x, 0.0001f)
        assertEquals(0f, fourWay.y, 0f)

        val eightWay = controller.applyDirectionSnapping(
            input,
            ControlsProfile.StickDigitalMode.EIGHT_WAY,
            false,
        )
        assertEquals(0.4243f, eightWay.x, 0.0001f)
        assertEquals(0.4243f, eightWay.y, 0.0001f)

        val unrestricted = controller.applyDirectionSnapping(
            input,
            ControlsProfile.StickDigitalMode.UNRESTRICTED,
            false,
        )
        assertEquals(input.x, unrestricted.x, 0f)
        assertEquals(input.y, unrestricted.y, 0f)
    }

    @Test
    fun `an unreported Joy-Con axis retains its raw value`() {
        assertEquals(0.65f, ExternalController.resolveRawAxis(false, 0.65f, -0.6f), 0f)
        assertEquals(-0.6f, ExternalController.resolveRawAxis(true, 0.65f, -0.6f), 0f)
    }

    @Test
    fun `legacy profiles keep defaults and fields are read independent of JSON order`() {
        val legacy = load("""{"id":7,"name":"Legacy","elements":[]}""")
        assertEquals(1f, legacy.cursorSpeed, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE, legacy.leftStickDeadzone, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE, legacy.rightStickDeadzone, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_SENSITIVITY, legacy.leftStickSensitivity, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_SENSITIVITY, legacy.rightStickSensitivity, 0f)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE_MODE, legacy.leftStickDeadzoneMode)
        assertEquals(ControlsProfile.DEFAULT_STICK_DEADZONE_MODE, legacy.rightStickDeadzoneMode)
        assertEquals(ControlsProfile.DEFAULT_STICK_DIGITAL_MODE, legacy.leftStickDigitalMode)
        assertEquals(ControlsProfile.DEFAULT_STICK_DIGITAL_MODE, legacy.rightStickDigitalMode)

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
                "rightStickDeadzoneMode":"hybrid",
                "leftStickDeadzoneMode":"circular",
                "rightStickDigitalMode":"eight_way",
                "leftStickDigitalMode":"four_way",
                "cursorSpeed":1.4
            }""".trimIndent(),
        )
        assertEquals(1.4f, reordered.cursorSpeed, 0.0001f)
        assertEquals(0.05f, reordered.leftStickDeadzone, 0.0001f)
        assertEquals(0.3f, reordered.rightStickDeadzone, 0.0001f)
        assertEquals(1.2f, reordered.leftStickSensitivity, 0.0001f)
        assertEquals(1.8f, reordered.rightStickSensitivity, 0.0001f)
        assertEquals(ControlsProfile.StickDeadzoneMode.CIRCULAR, reordered.leftStickDeadzoneMode)
        assertEquals(ControlsProfile.StickDeadzoneMode.HYBRID, reordered.rightStickDeadzoneMode)
        assertEquals(ControlsProfile.StickDigitalMode.FOUR_WAY, reordered.leftStickDigitalMode)
        assertEquals(ControlsProfile.StickDigitalMode.EIGHT_WAY, reordered.rightStickDigitalMode)
    }

    @Test
    fun `stick tuning persists through profile save and load`() {
        val profile = ControlsProfile(context, 9).apply {
            name = "Tuned"
            leftStickDeadzone = 0f
            rightStickDeadzone = 0.35f
            leftStickSensitivity = 0.7f
            rightStickSensitivity = 2.4f
            leftStickDeadzoneMode = ControlsProfile.StickDeadzoneMode.CIRCULAR
            rightStickDeadzoneMode = ControlsProfile.StickDeadzoneMode.HYBRID
            leftStickDigitalMode = ControlsProfile.StickDigitalMode.FOUR_WAY
            rightStickDigitalMode = ControlsProfile.StickDigitalMode.EIGHT_WAY
            save()
        }

        val loaded = checkNotNull(
            InputControlsManager.loadProfile(context, ControlsProfile.getProfileFile(context, profile.id)),
        )
        assertEquals(0f, loaded.leftStickDeadzone, 0f)
        assertEquals(0.35f, loaded.rightStickDeadzone, 0.0001f)
        assertEquals(0.7f, loaded.leftStickSensitivity, 0.0001f)
        assertEquals(2.4f, loaded.rightStickSensitivity, 0.0001f)
        assertEquals(ControlsProfile.StickDeadzoneMode.CIRCULAR, loaded.leftStickDeadzoneMode)
        assertEquals(ControlsProfile.StickDeadzoneMode.HYBRID, loaded.rightStickDeadzoneMode)
        assertEquals(ControlsProfile.StickDigitalMode.FOUR_WAY, loaded.leftStickDigitalMode)
        assertEquals(ControlsProfile.StickDigitalMode.EIGHT_WAY, loaded.rightStickDigitalMode)
    }

    private fun load(json: String): ControlsProfile {
        return checkNotNull(
            InputControlsManager.loadProfile(
                context,
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            ),
        )
    }

    private fun vectorX(degrees: Double): Float = Math.cos(Math.toRadians(degrees)).toFloat()

    private fun vectorY(degrees: Double): Float = Math.sin(Math.toRadians(degrees)).toFloat()
}
