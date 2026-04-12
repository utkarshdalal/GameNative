package com.winlator.widget

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class GyroControllerTest {
    private data class GyroEvent(val x: Float, val y: Float, val rightStick: Boolean, val isMouse: Boolean)

    private class RecordingListener : GyroController.Listener {
        val events = mutableListOf<GyroEvent>()

        override fun onGyroOutput(x: Float, y: Float, rightStick: Boolean, isMouse: Boolean) {
            events += GyroEvent(x, y, rightStick, isMouse)
        }
    }

    private fun createController(listener: RecordingListener): GyroController {
        val context = Mockito.mock(Context::class.java)
        return GyroController(context, listener)
    }

    private fun createControllerWithGyroSensor(listener: RecordingListener): Pair<GyroController, SensorManager> {
        val sensorManager = Mockito.mock(SensorManager::class.java)
        val sensor = Mockito.mock(Sensor::class.java)
        Mockito.`when`(sensor.type).thenReturn(Sensor.TYPE_GYROSCOPE)
        Mockito.`when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(sensor)
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        Mockito.`when`(context.getSystemService(Context.WINDOW_SERVICE)).thenReturn(null)
        return Pair(GyroController(context, listener), sensorManager)
    }

    @Test
    fun mapToStick_appliesRotation() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setSensitivity(1f)

        val mapped = controller.mapToStick(0.4f, 0.2f, Surface.ROTATION_90)

        assertEquals(0.2f, mapped[0], 0.0001f)
        assertEquals(-0.4f, mapped[1], 0.0001f)
    }

    @Test
    fun mapToStick_appliesInvertToggles() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setSensitivity(1f)
        controller.setInvertX(true)
        controller.setInvertY(true)

        val mapped = controller.mapToStick(0.3f, -0.6f, Surface.ROTATION_0)

        assertEquals(-0.3f, mapped[0], 0.0001f)
        assertEquals(0.6f, mapped[1], 0.0001f)
    }

    @Test
    fun mapToStick_clampsAndDeadzonesWithSensitivity() {
        val listener = RecordingListener()
        val controller = createController(listener)

        controller.setSensitivity(2f)
        val high = controller.mapToStick(0.8f, -0.8f, Surface.ROTATION_0)
        assertEquals(1f, high[0], 0.0001f)
        assertEquals(-1f, high[1], 0.0001f)

        controller.setSensitivity(0f) // clamp to minimum 0.1
        val low = controller.mapToStick(0.5f, 0.2f, Surface.ROTATION_0)
        assertEquals(0.05f, low[0], 0.0001f)
        assertEquals(0f, low[1], 0.0001f) // deadzone (< 0.03 after scaling)
    }

    @Test
    fun setMode_clearsPreviousStickContribution() {
        val listener = RecordingListener()
        val controller = createController(listener)

        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        assertTrue(listener.events.isEmpty())

        controller.setMode(InputControlsView.GYRO_MODE_RIGHT_STICK)
        assertEquals(1, listener.events.size)
        assertEquals(0f, listener.events[0].x, 0.0001f)
        assertEquals(0f, listener.events[0].y, 0.0001f)
        assertFalse(listener.events[0].rightStick)
        assertFalse(listener.events[0].isMouse)

        controller.setMode(InputControlsView.GYRO_MODE_DISABLED)
        assertEquals(2, listener.events.size)
        assertTrue(listener.events[1].rightStick)
        assertFalse(listener.events[1].isMouse)
    }

    @Test
    fun setEditMode_enteringEditMode_clearsLeftStick() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        assertTrue(listener.events.isEmpty())

        controller.setEditMode(true)

        assertEquals(1, listener.events.size)
        assertEquals(0f, listener.events[0].x, 0.0001f)
        assertEquals(0f, listener.events[0].y, 0.0001f)
        assertFalse(listener.events[0].rightStick)
        assertFalse(listener.events[0].isMouse)
    }

    @Test
    fun setEditMode_enteringEditMode_clearsRightStick() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_RIGHT_STICK)
        assertTrue(listener.events.isEmpty())

        controller.setEditMode(true)

        assertEquals(1, listener.events.size)
        assertEquals(0f, listener.events[0].x, 0.0001f)
        assertEquals(0f, listener.events[0].y, 0.0001f)
        assertTrue(listener.events[0].rightStick)
        assertFalse(listener.events[0].isMouse)
    }

    @Test
    fun setEditMode_enteringEditMode_withGyroDisabled_doesNotNotifyListener() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_DISABLED)

        controller.setEditMode(true)

        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun setEditMode_exitingEditMode_doesNotClearStickAgain() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        controller.setEditMode(true)
        assertEquals(1, listener.events.size)

        controller.setEditMode(false)

        assertEquals(1, listener.events.size)
    }

    /**
     * When profile goes away while gyro targets a stick, unregister alone would leave the
     * last merged stick value latched in InputControlsView until something else overwrites it.
     */
    @Test
    fun setHasProfile_losingProfile_clearsLeftStick() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        controller.setHasProfile(true)
        listener.events.clear()

        controller.setHasProfile(false)

        assertEquals(1, listener.events.size)
        assertEquals(0f, listener.events[0].x, 0.0001f)
        assertEquals(0f, listener.events[0].y, 0.0001f)
        assertFalse(listener.events[0].rightStick)
        assertFalse(listener.events[0].isMouse)
    }

    @Test
    fun setMode_mouseMode_doesNotEmitStickClearWhenSwitchingAway() {
        val listener = RecordingListener()
        val controller = createController(listener)
        controller.setMode(InputControlsView.GYRO_MODE_MOUSE)
        assertTrue(listener.events.isEmpty())

        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun updateRegistration_registersGyroListenerOnlyAfterViewAttached() {
        val listener = RecordingListener()
        val (controller, sensorManager) = createControllerWithGyroSensor(listener)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!

        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        controller.setHasProfile(true)

        verify(sensorManager, never()).registerListener(
            any(SensorEventListener::class.java),
            eq(sensor),
            eq(SensorManager.SENSOR_DELAY_GAME),
        )

        controller.onAttachedToWindow()

        verify(sensorManager).registerListener(
            eq(controller),
            eq(sensor),
            eq(SensorManager.SENSOR_DELAY_GAME),
        )
    }

    @Test
    fun onDetachedFromWindow_unregistersGyroListener() {
        val listener = RecordingListener()
        val (controller, sensorManager) = createControllerWithGyroSensor(listener)
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)!!

        controller.setMode(InputControlsView.GYRO_MODE_LEFT_STICK)
        controller.setHasProfile(true)
        controller.onAttachedToWindow()

        verify(sensorManager).registerListener(
            eq(controller),
            eq(sensor),
            eq(SensorManager.SENSOR_DELAY_GAME),
        )

        controller.onDetachedFromWindow()

        verify(sensorManager).unregisterListener(
            eq(controller),
            eq(sensor),
        )
    }
}
