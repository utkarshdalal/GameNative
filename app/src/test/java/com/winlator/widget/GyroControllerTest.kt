package com.winlator.widget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import app.gamenative.data.GyroSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock

class GyroControllerTest {
    private class RecordingListener : GyroController.Listener {
        val mouseEvents = mutableListOf<Pair<Int, Int>>()
        val stickEvents = mutableListOf<Triple<Float, Float, Boolean>>()
        val activeEvents = mutableListOf<Boolean>()

        override fun onGyroMouseDelta(x: Int, y: Int) {
            mouseEvents += x to y
        }

        override fun onGyroStick(x: Float, y: Float, rightStick: Boolean) {
            stickEvents += Triple(x, y, rightStick)
        }

        override fun onGyroActiveChanged(active: Boolean) {
            activeEvents += active
        }
    }

    private fun controller(listener: RecordingListener = RecordingListener()): GyroController {
        return GyroController(mock<Context>(), listener)
    }

    private fun controllerWithSensor(
        listener: RecordingListener = RecordingListener(),
    ): Triple<GyroController, SensorManager, Sensor> {
        val sensorManager = mock<SensorManager>()
        val sensor = mock<Sensor>()
        `when`(sensor.type).thenReturn(Sensor.TYPE_GYROSCOPE)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(sensor)
        `when`(
            sensorManager.registerListener(
                any(SensorEventListener::class.java),
                eq(sensor),
                eq(GyroController.SENSOR_PERIOD_US),
            ),
        ).thenReturn(true)
        val context = mock<Context>()
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        `when`(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(null)
        return Triple(GyroController(context, listener), sensorManager, sensor)
    }

    private fun controllerWithTiltSensors(): Triple<GyroController, SensorManager, Pair<Sensor, Sensor>> {
        val sensorManager = mock<SensorManager>()
        val gyro = mock<Sensor>()
        val orientation = mock<Sensor>()
        `when`(gyro.type).thenReturn(Sensor.TYPE_GYROSCOPE)
        `when`(orientation.type).thenReturn(Sensor.TYPE_GAME_ROTATION_VECTOR)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)).thenReturn(orientation)
        `when`(
            sensorManager.registerListener(
                any(SensorEventListener::class.java),
                any(Sensor::class.java),
                eq(GyroController.SENSOR_PERIOD_US),
            ),
        ).thenReturn(true)
        val context = mock<Context>()
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        `when`(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(null)
        return Triple(GyroController(context, RecordingListener()), sensorManager, gyro to orientation)
    }

    @Test
    fun mapAndFilterRates_appliesDisplayRotationAndInversion() {
        val controller = controller()
        controller.setSettings(
            GyroSettings(
                steadyingDegreesPerSecond = 0f,
                invertX = true,
                invertY = true,
            ),
        )

        val mapped = controller.mapAndFilterRates(0.4f, 0.2f, Surface.ROTATION_90)

        assertEquals(-0.2f, mapped[0], 0.0001f)
        assertEquals(0.4f, mapped[1], 0.0001f)
    }

    @Test
    fun mapAndFilterRates_steadyingSubtractsThresholdWithoutChangingDirection() {
        val controller = controller()
        controller.setSettings(GyroSettings(steadyingDegreesPerSecond = 5f))

        val belowThreshold = controller.mapAndFilterRates(
            Math.toRadians(4.0).toFloat(),
            0f,
            Surface.ROTATION_0,
        )
        val aboveThreshold = controller.mapAndFilterRates(
            Math.toRadians(7.0).toFloat(),
            0f,
            Surface.ROTATION_0,
        )

        assertArrayEquals(floatArrayOf(0f, 0f), belowThreshold, 0.0001f)
        assertEquals(Math.toRadians(2.0).toFloat(), aboveThreshold[0], 0.0001f)
        assertEquals(0f, aboveThreshold[1], 0.0001f)
    }

    @Test
    fun integrateMouse_preservesSubpixelMotionAndUsesElapsedTime() {
        val controller = controller()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_MOUSE,
                sensitivity = 1f,
                verticalScale = 2f,
                steadyingDegreesPerSecond = 0f,
            ),
        )

        val first = controller.integrateMouse(0.1f, 0.05f, 0.01f)
        val second = controller.integrateMouse(0.1f, 0.05f, 0.01f)

        assertArrayEquals(intArrayOf(0, 0), first)
        assertArrayEquals(intArrayOf(0, 0), second)
        val third = controller.integrateMouse(0.1f, 0.05f, 0.01f)
        assertArrayEquals(intArrayOf(1, 1), third)
    }

    @Test
    fun smoothRates_usesTimestampBasedLowPassFilter() {
        val controller = controller()
        controller.setSettings(GyroSettings(smoothingMilliseconds = 100f))

        assertArrayEquals(floatArrayOf(0f, 0f), controller.smoothRates(0f, 0f, 1_000_000_000L), 0.0001f)
        val smoothed = controller.smoothRates(1f, -1f, 1_010_000_000L)

        assertEquals(0.09516f, smoothed[0], 0.0001f)
        assertEquals(-0.09516f, smoothed[1], 0.0001f)
    }

    @Test
    fun smoothRates_resetsAcrossSettingsChangesAndLongGaps() {
        val controller = controller()
        controller.setSettings(GyroSettings(smoothingMilliseconds = 100f))
        controller.smoothRates(0f, 0f, 1_000_000_000L)
        controller.smoothRates(1f, 1f, 1_010_000_000L)

        controller.setSettings(GyroSettings(smoothingMilliseconds = 100f, sensitivity = 1.5f))
        assertArrayEquals(
            floatArrayOf(0.5f, -0.5f),
            controller.smoothRates(0.5f, -0.5f, 2_000_000_000L),
            0.0001f,
        )

        assertArrayEquals(
            floatArrayOf(1f, 1f),
            controller.smoothRates(1f, 1f, 2_100_000_000L),
            0.0001f,
        )
    }

    @Test
    fun stickAntiDeadzone_remapsMagnitudeAndClamps() {
        val small = GyroController.applyStickAntiDeadzone(0.1f, 0f, 0.2f)
        val large = GyroController.applyStickAntiDeadzone(5f, 0f, 0.2f)

        assertEquals(0.28f, small[0], 0.0001f)
        assertEquals(0f, small[1], 0.0001f)
        assertEquals(1f, large[0], 0.0001f)
    }

    @Test
    fun stickSmoothing_decaysBelowAntiDeadzoneAfterMotionStops() {
        val controller = controller()
        controller.setSettings(
            GyroSettings(
                smoothingMilliseconds = 100f,
                stickAntiDeadzone = 0.2f,
            ),
        )
        var timestamp = 1_000_000_000L
        val active = controller.applyStickResponse(0.1f, 0f, timestamp)
        var released = active

        repeat(20) {
            timestamp += 10_000_000L
            released = controller.applyStickResponse(0f, 0f, timestamp)
        }

        assertEquals(0.28f, active[0], 0.0001f)
        assertTrue(released[0] >= 0f && released[0] < 0.2f)
    }

    @Test
    fun tiltAngleToStick_usesRelativeAngleDeadzoneInversionAndWraparound() {
        val centered = GyroController.tiltAngleToStick(0.5f, 0.5f, 30f, 2f, false)
        val belowDeadzone = GyroController.tiltAngleToStick(
            Math.toRadians(1.5).toFloat(),
            0f,
            30f,
            2f,
            false,
        )
        val wrapped = GyroController.tiltAngleToStick(
            Math.toRadians(-177.0).toFloat(),
            Math.toRadians(179.0).toFloat(),
            30f,
            2f,
            false,
        )
        val inverted = GyroController.tiltAngleToStick(
            Math.toRadians(30.0).toFloat(),
            0f,
            30f,
            2f,
            true,
        )
        val customRange = GyroController.tiltAngleToStick(
            Math.toRadians(20.0).toFloat(),
            0f,
            40f,
            5f,
            false,
        )

        assertEquals(0f, centered, 0f)
        assertEquals(0f, belowDeadzone, 0f)
        assertEquals(1f / 14f, wrapped, 0.0001f)
        assertEquals(-1f, inverted, 0.0001f)
        assertEquals(3f / 7f, customRange, 0.0001f)
    }

    @Test
    fun tiltSteering_usesOrientationSensorAndMouseSwitchesBackToGyroscope() {
        val (controller, manager, sensors) = controllerWithTiltSensors()
        val (gyro, orientation) = sensors
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_LEFT_STICK,
                tiltSteeringEnabled = true,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()

        verify(manager).registerListener(controller, orientation, GyroController.SENSOR_PERIOD_US)

        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_MOUSE,
                lastTarget = GyroSettings.MODE_MOUSE,
                tiltSteeringEnabled = true,
            ),
        )

        verify(manager).unregisterListener(controller, orientation)
        verify(manager).registerListener(controller, gyro, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun holdActivation_registersOnlyWhileModifierIsHeld() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_HOLD,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()

        verify(manager, never()).registerListener(
            any(SensorEventListener::class.java),
            eq(sensor),
            eq(GyroController.SENSOR_PERIOD_US),
        )

        controller.setModifierPressed(true)

        verify(manager).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        controller.setModifierPressed(false)
        verify(manager).unregisterListener(controller, sensor)
    }

    @Test
    fun holdActivation_remainsActiveUntilEveryModifierSourceIsReleased() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_HOLD,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        val firstSource = Any()
        val secondSource = Any()

        controller.setModifierPressed(firstSource, true)
        controller.setModifierPressed(secondSource, true)
        controller.setModifierPressed(firstSource, false)

        verify(manager).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        verify(manager, never()).unregisterListener(controller, sensor)

        controller.setModifierPressed(secondSource, false)
        verify(manager).unregisterListener(controller, sensor)
    }

    @Test
    fun overlayInterruption_doesNotLeaveHoldActivationLatched() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_HOLD,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        controller.setModifierPressed(true)

        controller.setOverlaySuppressed(true)
        controller.setOverlaySuppressed(false)

        verify(manager, times(1)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        controller.setModifierPressed(true)
        verify(manager, times(2)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun toggleActivation_changesOnlyOnModifierPressEdges() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_TOGGLE,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()

        controller.setModifierPressed(true)
        controller.setModifierPressed(true)
        verify(manager, times(1)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)

        controller.setModifierPressed(false)
        controller.setModifierPressed(true)
        verify(manager).unregisterListener(controller, sensor)
    }

    @Test
    fun toggleActivation_usesAggregateModifierPressEdges() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_TOGGLE,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        val firstSource = Any()
        val secondSource = Any()

        controller.setModifierPressed(firstSource, true)
        controller.setModifierPressed(secondSource, true)
        controller.setModifierPressed(firstSource, false)
        controller.setModifierPressed(secondSource, false)

        verify(manager).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        verify(manager, never()).unregisterListener(controller, sensor)

        controller.setModifierPressed(secondSource, true)
        verify(manager).unregisterListener(controller, sensor)
    }

    @Test
    fun resetActivationState_turnsOffToggleUntilAnotherPress() {
        val listener = RecordingListener()
        val (controller, manager, sensor) = controllerWithSensor(listener)
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_TOGGLE,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        controller.setModifierPressed(true)
        controller.setModifierPressed(false)

        controller.resetActivationState()

        verify(manager).unregisterListener(controller, sensor)
        assertEquals(listOf(true, false), listener.activeEvents)

        controller.setModifierPressed(true)
        verify(manager, times(2)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        assertEquals(listOf(true, false, true), listener.activeEvents)
    }

    @Test
    fun disablingGyro_clearsToggleLatchBeforeReenabling() {
        val listener = RecordingListener()
        val (controller, manager, sensor) = controllerWithSensor(listener)
        val toggleSettings = GyroSettings(
            mode = GyroSettings.MODE_RIGHT_STICK,
            activationMode = GyroSettings.ACTIVATION_TOGGLE,
        )
        controller.setSettings(toggleSettings)
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        controller.setModifierPressed(true)
        controller.setModifierPressed(false)

        controller.setSettings(toggleSettings.copy(mode = GyroSettings.MODE_DISABLED))
        controller.setSettings(toggleSettings)

        verify(manager, times(1)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        assertEquals(listOf(true, false), listener.activeEvents)
    }

    @Test
    fun temporaryOverlay_preservesToggleLatchAndReportsInactiveWhileSuppressed() {
        val listener = RecordingListener()
        val (controller, manager, sensor) = controllerWithSensor(listener)
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_RIGHT_STICK,
                activationMode = GyroSettings.ACTIVATION_TOGGLE,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        controller.setModifierPressed(true)
        controller.setModifierPressed(false)

        controller.setOverlaySuppressed(true)
        controller.setOverlaySuppressed(false)

        verify(manager, times(2)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        assertEquals(listOf(true, false, true), listener.activeEvents)
    }

    @Test
    fun ratchetActivation_pausesWhileModifierIsHeldAndResumesOnRelease() {
        val (controller, manager, sensor) = controllerWithSensor()
        controller.setSettings(
            GyroSettings(
                mode = GyroSettings.MODE_LEFT_STICK,
                activationMode = GyroSettings.ACTIVATION_RATCHET,
            ),
        )
        controller.setHasProfile(true)
        controller.onAttachedToWindow()

        verify(manager).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
        controller.setModifierPressed(true)
        verify(manager).unregisterListener(controller, sensor)
        controller.setModifierPressed(false)
        verify(manager, times(2)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun overlayAndForegroundTransitions_unregisterAndClearStickOutput() {
        val listener = RecordingListener()
        val (controller, manager, sensor) = controllerWithSensor(listener)
        controller.setSettings(GyroSettings(mode = GyroSettings.MODE_RIGHT_STICK))
        controller.setHasProfile(true)
        controller.onAttachedToWindow()
        listener.stickEvents.clear()

        controller.setOverlaySuppressed(true)
        verify(manager).unregisterListener(controller, sensor)
        assertEquals(listOf(Triple(0f, 0f, true)), listener.stickEvents)

        controller.setOverlaySuppressed(false)
        controller.setForeground(false)
        verify(manager, times(2)).unregisterListener(controller, sensor)
        controller.setForeground(true)
        verify(manager, times(3)).registerListener(controller, sensor, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun switchingStickTargets_clearsThePreviousContribution() {
        val listener = RecordingListener()
        val controller = controller(listener)
        controller.setSettings(GyroSettings(mode = GyroSettings.MODE_LEFT_STICK))
        listener.stickEvents.clear()

        controller.setSettings(GyroSettings(mode = GyroSettings.MODE_RIGHT_STICK))

        assertEquals(listOf(Triple(0f, 0f, false)), listener.stickEvents)
    }

    @Test
    fun settingsNormalization_rejectsNonFiniteAndOutOfRangeValues() {
        val normalized = GyroSettings(
            sensitivity = Float.NaN,
            verticalScale = Float.POSITIVE_INFINITY,
            steadyingDegreesPerSecond = Float.NEGATIVE_INFINITY,
            smoothingMilliseconds = 500f,
            stickAntiDeadzone = -1f,
            tiltFullScaleDegrees = Float.NaN,
            tiltDeadzoneDegrees = 50f,
        ).normalized()

        assertEquals(1f, normalized.sensitivity, 0f)
        assertEquals(1f, normalized.verticalScale, 0f)
        assertEquals(1f, normalized.steadyingDegreesPerSecond, 0f)
        assertEquals(GyroSettings.MAX_SMOOTHING_MS, normalized.smoothingMilliseconds, 0f)
        assertEquals(0f, normalized.stickAntiDeadzone, 0f)
        assertEquals(GyroSettings.DEFAULT_TILT_FULL_SCALE_DEGREES, normalized.tiltFullScaleDegrees, 0f)
        assertEquals(GyroSettings.MAX_TILT_DEADZONE_DEGREES, normalized.tiltDeadzoneDegrees, 0f)

        val constrainedDeadzone = GyroSettings(
            tiltFullScaleDegrees = GyroSettings.MIN_TILT_FULL_SCALE_DEGREES,
            tiltDeadzoneDegrees = GyroSettings.MAX_TILT_DEADZONE_DEGREES,
        ).normalized()
        assertEquals(9f, constrainedDeadzone.tiltDeadzoneDegrees, 0f)
    }
}
