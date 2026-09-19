package app.gamenative.data

import com.winlator.container.Container
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GyroSettingsTest {
    @Test
    fun missingTiltSettings_useCurrentDefaultsIndependentFromRateSensitivity() {
        val container = containerWithExtras(
            mutableMapOf(
                "gyroMode" to GyroSettings.MODE_LEFT_STICK.toString(),
                "gyroLastTarget" to GyroSettings.MODE_LEFT_STICK.toString(),
                "gyroSensitivity" to "2.0",
                "gyroTiltSteering" to "true",
            ),
        )

        val settings = GyroSettings.fromContainer(container)

        assertEquals(2f, settings.sensitivity, 0f)
        assertEquals(GyroSettings.DEFAULT_TILT_FULL_SCALE_DEGREES, settings.tiltFullScaleDegrees, 0f)
        assertEquals(GyroSettings.DEFAULT_TILT_DEADZONE_DEGREES, settings.tiltDeadzoneDegrees, 0f)
    }

    @Test
    fun newTiltDefaults_doNotReuseRateSensitivity() {
        val container = containerWithExtras(mutableMapOf("gyroSensitivity" to "2.0"))

        val settings = GyroSettings.fromContainer(container)

        assertEquals(2f, settings.sensitivity, 0f)
        assertEquals(GyroSettings.DEFAULT_TILT_FULL_SCALE_DEGREES, settings.tiltFullScaleDegrees, 0f)
        assertEquals(GyroSettings.DEFAULT_TILT_DEADZONE_DEGREES, settings.tiltDeadzoneDegrees, 0f)
    }

    @Test
    fun tiltSettings_roundTripIndependentlyFromRateSensitivity() {
        val extras = mutableMapOf<String, String>()
        val container = containerWithExtras(extras)
        val original = GyroSettings(
            mode = GyroSettings.MODE_LEFT_STICK,
            lastTarget = GyroSettings.MODE_LEFT_STICK,
            sensitivity = 3f,
            tiltSteeringEnabled = true,
            tiltFullScaleDegrees = 42f,
            tiltDeadzoneDegrees = 4f,
        )

        original.saveTo(container)
        val restored = GyroSettings.fromContainer(container)

        assertEquals(3f, restored.sensitivity, 0f)
        assertTrue(restored.tiltSteeringEnabled)
        assertEquals(42f, restored.tiltFullScaleDegrees, 0f)
        assertEquals(4f, restored.tiltDeadzoneDegrees, 0f)
    }

    @Test
    fun enabledMode_isTheCanonicalLastTarget() {
        val normalized = GyroSettings(
            mode = GyroSettings.MODE_LEFT_STICK,
            lastTarget = GyroSettings.MODE_MOUSE,
        ).normalized()

        assertEquals(GyroSettings.MODE_LEFT_STICK, normalized.mode)
        assertEquals(GyroSettings.MODE_LEFT_STICK, normalized.lastTarget)
    }

    @Test
    fun invalidEnums_fallBackToSafeDisabledDefaults() {
        val normalized = GyroSettings(
            mode = Int.MAX_VALUE,
            lastTarget = Int.MIN_VALUE,
            activationMode = Int.MAX_VALUE,
        ).normalized()

        assertEquals(GyroSettings.MODE_DISABLED, normalized.mode)
        assertEquals(GyroSettings.MODE_RIGHT_STICK, normalized.lastTarget)
        assertEquals(GyroSettings.ACTIVATION_ALWAYS, normalized.activationMode)
    }

    @Test
    fun jsonRoundTrip_preservesProfileSettings() {
        val original = GyroSettings(
            mode = GyroSettings.MODE_MOUSE,
            lastTarget = GyroSettings.MODE_MOUSE,
            activationMode = GyroSettings.ACTIVATION_TOGGLE,
            sensitivity = 2.5f,
            verticalScale = 0.75f,
            steadyingDegreesPerSecond = 3f,
            smoothingMilliseconds = 14f,
            stickAntiDeadzone = 0.2f,
            invertX = true,
            invertY = true,
        ).normalized()

        val restored = GyroSettings.fromJsonObject(JSONObject(original.toJsonObject().toString()))

        assertEquals(original, restored)
    }

    private fun containerWithExtras(extras: MutableMap<String, String>): Container {
        val container = mock<Container>()
        whenever(container.getExtra(any(), any())).thenAnswer { invocation ->
            extras[invocation.getArgument(0)] ?: invocation.getArgument(1)
        }
        doAnswer { invocation ->
            extras[invocation.getArgument<String>(0)] = invocation.getArgument<Any>(1).toString()
            null
        }.whenever(container).putExtra(any(), any())
        return container
    }
}
