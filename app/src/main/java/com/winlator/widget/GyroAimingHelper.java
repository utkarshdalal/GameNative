package com.winlator.widget;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import timber.log.Timber;

/**
 * Listens to the device gyroscope and converts angular velocity to relative mouse deltas
 * for aiming. Only active when started; use from the main thread.
 */
public class GyroAimingHelper implements SensorEventListener {
    public interface Listener {
        void onMouseDelta(int dx, int dy);
    }

    private static final float DEFAULT_SENSITIVITY = 400f;
    private static final float MAX_DELTA_PER_FRAME = 120f;
    private static final int SENSOR_DELAY_US = 5_000; // 200 Hz

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler;
    private float sensitivity;

    private SensorManager sensorManager;
    private DisplayManager displayManager;
    private Sensor gyro;
    private boolean running;
    private long lastTimestampNs = 0;
    private boolean hasLastTimestamp;
    /** Sub-pixel accumulation so small movements aren't lost when casting to int */
    private float accumDx = 0f;
    private float accumDy = 0f;

    public GyroAimingHelper(Context context, Listener listener) {
        this(context, listener, DEFAULT_SENSITIVITY);
    }

    public GyroAimingHelper(Context context, Listener listener, float sensitivity) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.sensitivity = sensitivity > 0 ? sensitivity : DEFAULT_SENSITIVITY;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (running) return;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (sensorManager == null) {
            Timber.w("GyroAiming: SensorManager is null");
            return;
        }
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro == null) {
            Timber.w("GyroAiming: no TYPE_GYROSCOPE sensor on this device");
            return;
        }
        hasLastTimestamp = false;
        accumDx = 0f;
        accumDy = 0f;
        boolean registered = sensorManager.registerListener(this, gyro, SENSOR_DELAY_US);
        if (!registered) {
            Timber.e("GyroAiming: failed to start, registerListener returned false (sensor=%s)", gyro.getName());
            return;
        }
        running = true;
        Timber.d("GyroAiming: started (sensor=%s)", gyro.getName());
    }

    public void stop() {
        if (!running) return;
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (sensorManager != null && gyro != null) {
            sensorManager.unregisterListener(this, gyro);
        }
        sensorManager = null;
        displayManager = null;
        gyro = null;
        Timber.d("GyroAiming: stopped");
    }

    public boolean isRunning() {
        return running;
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity > 0 ? sensitivity : DEFAULT_SENSITIVITY;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running) return;
        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE || listener == null) return;

        long t = event.timestamp;
        if (!hasLastTimestamp) {
            lastTimestampNs = t;
            hasLastTimestamp = true;
            return;
        }
        float dt = (t - lastTimestampNs) * 1e-9f;
        lastTimestampNs = t;
        if (dt <= 0 || dt > 0.5f) return; // skip invalid or huge gaps

        float deviceRadX = event.values[0];
        float deviceRadY = event.values[1];

        float radX;
        float radY;
        // Offset by +90deg to match aiming orientation.
        int effectiveRotation = (getDisplayRotation() + 1) & 0x3;
        switch (effectiveRotation) {
            case Surface.ROTATION_90:
                radX = deviceRadY;
                radY = -deviceRadX;
                break;
            case Surface.ROTATION_180:
                radX = -deviceRadX;
                radY = -deviceRadY;
                break;
            case Surface.ROTATION_270:
                radX = -deviceRadY;
                radY = deviceRadX;
                break;
            case Surface.ROTATION_0:
            default:
                radX = deviceRadX;
                radY = deviceRadY;
                break;
        }

        float dx = -radX * dt * sensitivity;
        float dy = radY * dt * sensitivity;

        // Clamp per-frame delta for stability
        dx = clamp(dx, -MAX_DELTA_PER_FRAME, MAX_DELTA_PER_FRAME);
        dy = clamp(dy, -MAX_DELTA_PER_FRAME, MAX_DELTA_PER_FRAME);

        accumDx += dx;
        accumDy += dy;
        int ix = (int) accumDx;
        int iy = (int) accumDy;
        if (ix != 0 || iy != 0) {
            accumDx -= ix;
            accumDy -= iy;
            final int fix = ix;
            final int fiy = iy;
            mainHandler.post(() -> {
                if (!running) return;
                listener.onMouseDelta(fix, fiy);
            });
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private int getDisplayRotation() {
        if (displayManager == null) return Surface.ROTATION_0;
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return Surface.ROTATION_0;
        return display.getRotation();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
