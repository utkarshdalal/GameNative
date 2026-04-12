package com.winlator.widget;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.winlator.math.Mathf;

class GyroController implements SensorEventListener {
    interface Listener {
        void onGyroOutput(float x, float y, boolean rightStick, boolean isMouse);
    }

    private final SensorManager sensorManager;
    private final Sensor gyroscopeSensor;
    private final Listener listener;
    private final WindowManager windowManager;

    private int mode = InputControlsView.GYRO_MODE_DISABLED;
    private float sensitivity = 0.35f;
    private boolean invertX = false;
    private boolean invertY = false;
    private boolean editMode = false;
    private boolean hasProfile = false;
    private boolean isRegistered = false;
    private boolean isAttached = false;

    GyroController(@NonNull Context context, @NonNull Listener listener) {
        this.listener = listener;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        gyroscopeSensor = sensorManager != null ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) : null;
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    int getMode() {
        return mode;
    }

    void setMode(int mode) {
        int normalizedMode = (mode == InputControlsView.GYRO_MODE_LEFT_STICK
                || mode == InputControlsView.GYRO_MODE_RIGHT_STICK
                || mode == InputControlsView.GYRO_MODE_MOUSE)
                ? mode : InputControlsView.GYRO_MODE_DISABLED;
        if (this.mode == normalizedMode) {
            updateRegistration();
            return;
        }
        clearCurrentStick();
        this.mode = normalizedMode;
        updateRegistration();
    }

    void setEditMode(boolean editMode) {
        if (!this.editMode && editMode) {
            clearCurrentStick();
        }
        this.editMode = editMode;
        updateRegistration();
    }

    void setSensitivity(float sensitivity) {
        this.sensitivity = Mathf.clamp(sensitivity, 0.1f, 2.0f);
    }

    void setInvertX(boolean invertX) {
        this.invertX = invertX;
    }

    void setInvertY(boolean invertY) {
        this.invertY = invertY;
    }

    void setHasProfile(boolean hasProfile) {
        if (this.hasProfile && !hasProfile &&
                (mode == InputControlsView.GYRO_MODE_LEFT_STICK || mode == InputControlsView.GYRO_MODE_RIGHT_STICK)) {
            clearCurrentStick();
        }
        this.hasProfile = hasProfile;
        updateRegistration();
    }

    void onAttachedToWindow() {
        isAttached = true;
        updateRegistration();
    }

    void onDetachedFromWindow() {
        isAttached = false;
        unregister();
        clearCurrentStick();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        if (mode == InputControlsView.GYRO_MODE_DISABLED || editMode || !hasProfile) return;

        float rawX = -event.values[1];
        float rawY = -event.values[0];
        int rotation = windowManager != null && windowManager.getDefaultDisplay() != null
                ? windowManager.getDefaultDisplay().getRotation()
                : Surface.ROTATION_0;
        float[] mapped = mapToStick(rawX, rawY, rotation);
        float x = mapped[0];
        float y = mapped[1];

        boolean isMouse = mode == InputControlsView.GYRO_MODE_MOUSE;
        listener.onGyroOutput(x, y, mode == InputControlsView.GYRO_MODE_RIGHT_STICK, isMouse);
    }

    float[] mapToStick(float rawX, float rawY, int rotation) {
        float mappedX;
        float mappedY;
        switch (rotation) {
            case Surface.ROTATION_90:
                mappedX = rawY;
                mappedY = -rawX;
                break;
            case Surface.ROTATION_180:
                mappedX = -rawX;
                mappedY = -rawY;
                break;
            case Surface.ROTATION_270:
                mappedX = -rawY;
                mappedY = rawX;
                break;
            case Surface.ROTATION_0:
            default:
                mappedX = rawX;
                mappedY = rawY;
                break;
        }

        if (invertX) mappedX = -mappedX;
        if (invertY) mappedY = -mappedY;

        float x = Mathf.clamp(mappedX * sensitivity, -1f, 1f);
        float y = Mathf.clamp(mappedY * sensitivity, -1f, 1f);
        if (Math.abs(x) < 0.03f) x = 0f;
        if (Math.abs(y) < 0.03f) y = 0f;
        return new float[]{x, y};
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // no-op
    }

    private void clearCurrentStick() {
        if (mode == InputControlsView.GYRO_MODE_LEFT_STICK) {
            listener.onGyroOutput(0f, 0f, false, false);
        } else if (mode == InputControlsView.GYRO_MODE_RIGHT_STICK) {
            listener.onGyroOutput(0f, 0f, true, false);
        }
    }

    private void updateRegistration() {
        if (!isAttached || sensorManager == null || gyroscopeSensor == null) {
            unregister();
            return;
        }
        if (mode == InputControlsView.GYRO_MODE_DISABLED || editMode || !hasProfile) {
            unregister();
            return;
        }
        if (isRegistered) return;
        sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME);
        isRegistered = true;
    }

    private void unregister() {
        if (sensorManager == null || !isRegistered) return;
        sensorManager.unregisterListener(this, gyroscopeSensor);
        isRegistered = false;
    }
}
