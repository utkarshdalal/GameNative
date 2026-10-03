package com.winlator.widget

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import app.gamenative.data.GyroSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class GyroConversionSensorTest {
    private class Fixture(
        orientationAvailable: Boolean = true,
        orientationRegisters: Boolean = true,
        orientationType: Int = Sensor.TYPE_GAME_ROTATION_VECTOR,
    ) {
        val manager = mock<SensorManager>()
        val gyro = mock<Sensor> { on { type }.thenReturn(Sensor.TYPE_GYROSCOPE) }
        val orientation = mock<Sensor> { on { type }.thenReturn(orientationType) }
        val sticks = mutableListOf<Pair<Float, Float>>()
        val mouse = mutableListOf<Pair<Int, Int>>()
        var rotation = Surface.ROTATION_0
        val controller: GyroController
        val settings = GyroSettings(
            mode = GyroSettings.MODE_RIGHT_STICK,
            conversionStyle = GyroSettings.CONVERSION_WORLD_SPACE, steadyingDegreesPerSecond = 0f,
        )

        init {
            whenever(manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyro)
            whenever(manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR))
                .thenReturn(if (orientationAvailable && orientationType == Sensor.TYPE_GAME_ROTATION_VECTOR) orientation else null)
            whenever(manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR))
                .thenReturn(if (orientationAvailable && orientationType == Sensor.TYPE_ROTATION_VECTOR) orientation else null)
            whenever(manager.registerListener(any(), eq(gyro), any<Int>())).thenReturn(true)
            whenever(manager.registerListener(any(), eq(orientation), any<Int>())).thenReturn(orientationRegisters)
            val context = mock<Context>()
            whenever(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(manager)
            val display = mock<Display>()
            whenever(display.rotation).thenAnswer { rotation }
            val windows = mock<WindowManager>()
            whenever(windows.defaultDisplay).thenReturn(display)
            whenever(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(windows)
            controller = GyroController(
                context,
                object : GyroController.Listener {
                    override fun onGyroMouseDelta(x: Int, y: Int) {
                        mouse += x to y
                    }
                    override fun onGyroStick(x: Float, y: Float, rightStick: Boolean) {
                        sticks += x to y
                    }
                    override fun onGyroActiveChanged(active: Boolean) {}
                },
            )
        }

        fun start(config: GyroSettings = settings) = apply {
            controller.setSettings(config)
            controller.setHasProfile(true)
            controller.onAttachedToWindow()
            sticks.clear()
        }

        fun event(sensor: Sensor, time: Long, vararg values: Float) {
            val event = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.newInstance(values.size)
            event.sensor = sensor
            event.timestamp = time
            values.copyInto(event.values)
            controller.onSensorChanged(event)
        }

        fun flat(time: Long = 1_000_000_000L) = event(orientation, time, 0f, 0f, 0f, 1f)
        fun turn(time: Long = 1_010_000_000L) = event(gyro, time, 0f, 0f, 1f)
    }

    @Test
    fun screenProjectionMatchesAndroidCoordinateRemapping() {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val axes = listOf(
            SensorManager.AXIS_X to SensorManager.AXIS_Y,
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X,
            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y,
            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X,
        )
        val gyro = floatArrayOf(0.2f, -0.7f, 0.9f)
        val up = floatArrayOf(0.36f, 0.48f, 0.8f)
        axes.forEachIndexed { rotation, (x, y) ->
            val matrix = FloatArray(9)
            assertTrue(SensorManager.remapCoordinateSystem(identity, x, y, matrix))
            // Columns of the device-to-world matrix are the screen axes in device space.
            fun screenVector(vector: FloatArray) = FloatArray(3) { axis ->
                vector[0] * matrix[axis] + vector[1] * matrix[3 + axis] + vector[2] * matrix[6 + axis]
            }
            val screenGyro = screenVector(gyro)
            val screenUp = screenVector(up)
            for (style in GyroSettings.CONVERSION_LOCAL_YAW..GyroSettings.CONVERSION_WORLD_SPACE) {
                val expected = GyroSpaceMapper.map(
                    screenGyro[0], screenGyro[1], screenGyro[2], Surface.ROTATION_0,
                    style, screenUp[0], screenUp[1], screenUp[2],
                )
                val actual = GyroSpaceMapper.map(gyro[0], gyro[1], gyro[2], rotation, style, up[0], up[1], up[2])
                assertArrayEquals("style $style, rotation $rotation", expected, actual, 0.0001f)
            }
        }
    }

    @Test
    fun regularRotationVectorSupportsThreeComponentsAndIgnoresHeadingAccuracy() {
        val f = Fixture(orientationType = Sensor.TYPE_ROTATION_VECTOR).start()
        f.event(f.orientation, 1_000_000_000L, 0f, 0f, 0f)
        f.turn()
        assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
        // Upright: the quaternion places world up along the device's Y axis.
        val halfRoot = kotlin.math.sqrt(0.5f)
        f.event(f.orientation, 1_020_000_000L, halfRoot, 0f, 0f, halfRoot, Float.NaN)
        f.event(f.gyro, 1_030_000_000L, 0.5f, 2f, 0f)
        assertEquals(-0.7f, f.sticks.last().first, 0.0001f)
        assertEquals(-0.175f, f.sticks.last().second, 0.0001f)
        verify(f.manager).registerListener(f.controller, f.orientation, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun tiltSteeringKeepsItsCenteredResponseWithGravityConversionSelected() {
        val f = Fixture()
        f.start(f.settings.copy(tiltSteeringEnabled = true))
        f.flat()
        val halfAngle = Math.toRadians(15.0 / 2.0)
        f.event(
            f.orientation, 1_020_000_000L, 0f, kotlin.math.sin(halfAngle).toFloat(),
            0f, kotlin.math.cos(halfAngle).toFloat(),
        )
        assertEquals((15f - 2f) / (30f - 2f), f.sticks.single().first, 0.0001f)
        assertEquals(0f, f.sticks.single().second, 0f)
        verify(f.manager, never()).registerListener(f.controller, f.gyro, GyroController.SENSOR_PERIOD_US)
    }

    @Test
    fun gravityStylesWaitForOrientationAndOnlyGyroEventsProduceOutput() {
        for (style in listOf(GyroSettings.CONVERSION_PLAYER_SPACE, GyroSettings.CONVERSION_WORLD_SPACE)) {
            val f = Fixture()
            f.start(f.settings.copy(conversionStyle = style))
            f.turn()
            assertTrue(f.sticks.isEmpty())
            f.flat()
            assertTrue(f.sticks.isEmpty())
            f.turn()
            assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
            verify(f.manager).registerListener(f.controller, f.gyro, GyroController.SENSOR_PERIOD_US)
            verify(f.manager).registerListener(f.controller, f.orientation, GyroController.SENSOR_PERIOD_US)
        }
    }

    @Test
    fun staleGravityOrScreenRotationReleasesStickUntilFreshOrientation() {
        for (rotate in listOf(false, true)) {
            val f = Fixture().start()
            f.flat()
            f.turn()
            if (rotate) f.rotation = Surface.ROTATION_90
            f.turn(if (rotate) 1_020_000_000L else 1_300_000_000L)
            assertEquals("rotate $rotate", 0f to 0f, f.sticks.last())
            f.flat(1_310_000_000L)
            f.turn(1_320_000_000L)
            assertEquals("rotate $rotate", -0.35f, f.sticks.last().first, 0.0001f)
        }
    }

    @Test
    fun overlayResumeNeedsFreshGravityAndUnregistersBothSensors() {
        val f = Fixture().start()
        f.flat()
        f.turn()
        f.controller.setOverlaySuppressed(true)
        verify(f.manager).unregisterListener(f.controller, f.gyro)
        verify(f.manager).unregisterListener(f.controller, f.orientation)
        f.controller.setOverlaySuppressed(false)
        f.sticks.clear()
        f.turn(1_020_000_000L)
        assertTrue(f.sticks.isEmpty())
        f.flat(1_030_000_000L)
        f.turn(1_040_000_000L)
        assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
    }

    @Test
    fun localAndTiltModesReleaseAuxiliarySensorAndIgnoreItsQueuedEvents() {
        val f = Fixture().start()
        f.controller.setSettings(f.settings.copy(conversionStyle = GyroSettings.CONVERSION_LOCAL_ROLL))
        verify(f.manager).unregisterListener(f.controller, f.orientation)
        f.sticks.clear()
        f.flat()
        f.turn()
        assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
        f.controller.setSettings(f.settings.copy(tiltSteeringEnabled = true))
        f.sticks.clear()
        f.turn()
        assertTrue(f.sticks.isEmpty())
    }

    @Test
    fun missingOrFailedOrientationFallsBackToLocalYaw() {
        for ((available, registers) in listOf(false to false, true to false)) {
            val f = Fixture(available, registers).start()
            f.event(f.gyro, 1_010_000_000L, 0.5f, 1f, 4f)
            assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
            assertEquals(-0.175f, f.sticks.single().second, 0.0001f)
            f.controller.onDetachedFromWindow()
            verify(f.manager, never()).unregisterListener(f.controller, f.orientation)
        }
    }

    @Test
    fun invalidOrOutOfOrderOrientationCannotReplaceFreshGravity() {
        val f = Fixture().start()
        f.flat()
        f.event(f.orientation, 1_010_000_000L, Float.NaN, 0f, 0f, 1f)
        f.event(f.orientation, 990_000_000L, 0.70710677f, 0f, 0f, 0.70710677f)
        f.event(f.orientation, 1_020_000_000L, 0f, 1f)
        f.event(f.orientation, 1_021_000_000L, 0f, 0f, 0f, 0f)
        f.event(f.orientation, 1_022_000_000L, 2f, 0f, 0f, 1f)
        f.turn(1_030_000_000L)
        assertEquals(-0.35f, f.sticks.single().first, 0.0001f)
    }

    @Test
    fun worldMouseUsesElapsedTimeAndDoesNotAccumulateAcrossGravityGaps() {
        val f = Fixture()
        f.start(f.settings.copy(mode = GyroSettings.MODE_MOUSE))
        f.flat()
        f.turn()
        f.turn(1_020_000_000L)
        assertEquals(-4 to 0, f.mouse.single())
        f.turn(1_400_000_000L)
        f.flat(1_500_000_000L)
        f.turn(1_510_000_000L)
        assertEquals(1, f.mouse.size)
        f.turn(1_520_000_000L)
        assertEquals(listOf(-4 to 0, -4 to 0), f.mouse)
    }
}
