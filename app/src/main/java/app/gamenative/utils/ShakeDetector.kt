package app.gamenative.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

class ShakeDetector(
    context: Context,
    private val thresholdMs2: Float = DEFAULT_THRESHOLD,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastShakeTime = 0L

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
        if (magnitude < thresholdMs2) return

        val now = System.currentTimeMillis()
        if (now - lastShakeTime < cooldownMs) return

        lastShakeTime = now
        mainHandler.post { onShake() }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val DEFAULT_THRESHOLD = 12f
        private const val DEFAULT_COOLDOWN_MS = 1000L
    }
}
