package com.winlator.widget;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import app.gamenative.data.GyroSettings;
import com.winlator.inputcontrols.GamepadState;

import java.util.HashSet;
import java.util.Set;

/** Converts the device gyroscope into timestamp-independent mouse or stick output. */
class GyroController implements SensorEventListener {
    interface Listener {
        void onGyroMouseDelta(int x, int y);
        void onGyroStick(float x, float y, boolean rightStick);
        void onGyroActiveChanged(boolean active);
    }

    static final int SENSOR_PERIOD_US = 5_000;
    static final long MIN_STICK_OUTPUT_INTERVAL_NS = 10_000_000L;
    static final float MOUSE_PIXELS_PER_RADIAN = 450f;
    static final float STICK_UNITS_PER_RADIAN_PER_SECOND = 0.35f;
    private static final float MAX_MOUSE_EVENT_DELTA_SECONDS = 0.25f;
    private static final float MAX_SMOOTHING_DELTA_SECONDS = 0.05f;
    private static final float RADIANS_TO_DEGREES = (float)(180.0 / Math.PI);

    private final SensorManager sensorManager;
    private final Sensor gyroscopeSensor;
    private final Sensor orientationSensor;
    private final Listener listener;
    private final WindowManager windowManager;
    private final float[] rotationMatrix = new float[9];
    private final float[] remappedRotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private final float[] rotationVector3 = new float[3];
    private final float[] rotationVector4 = new float[4];
    private final Set<Object> modifierSources = new HashSet<>();
    private final Object defaultModifierSource = new Object();

    private volatile GyroSettings settings = new GyroSettings();
    private boolean editMode;
    private boolean hasProfile;
    private boolean attached;
    private boolean foreground = true;
    private boolean gameplayActive = true;
    private boolean overlaySuppressed;
    private boolean modifierPressed;
    private boolean toggleActive;
    private boolean registered;
    private boolean reportedActive;
    private volatile Sensor registeredSensor;
    private long lastTimestampNs;
    private long lastSmoothingTimestampNs;
    private double mouseRemainderX;
    private double mouseRemainderY;
    private boolean hasSmoothedRates;
    private float smoothedRateX;
    private float smoothedRateY;
    private boolean tiltCentered;
    private float tiltCenterRadians;
    private boolean hasDispatchedStick;
    private short lastDispatchedStickX;
    private short lastDispatchedStickY;
    private boolean lastDispatchedRightStick;
    private long lastStickOutputTimestampNs;

    GyroController(@NonNull Context context, @NonNull Listener listener) {
        this.listener = listener;
        sensorManager = (SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        gyroscopeSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                : null;
        Sensor gameRotationSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                : null;
        orientationSensor = gameRotationSensor != null || sensorManager == null
                ? gameRotationSensor
                : sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        windowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean isAvailable() {
        return gyroscopeSensor != null;
    }

    boolean isTiltAvailable() {
        return orientationSensor != null;
    }

    synchronized void setSettings(@NonNull GyroSettings value) {
        GyroSettings normalized = value.normalized();
        boolean settingsChanged = !settings.equals(normalized);
        boolean targetChanged = settings.getMode() != normalized.getMode();
        boolean activationChanged = settings.getActivationMode() != normalized.getActivationMode();
        boolean motionStyleChanged = settings.getTiltSteeringEnabled() != normalized.getTiltSteeringEnabled();
        if (settingsChanged) clearOutput();
        if (targetChanged || activationChanged || motionStyleChanged) {
            unregister();
        }
        settings = normalized;
        if (activationChanged || normalized.getMode() == GyroSettings.MODE_DISABLED) {
            clearModifierSources();
            toggleActive = false;
        }
        if (settingsChanged) resetMotionState();
        updateRegistration();
    }

    synchronized void setEditMode(boolean value) {
        if (editMode != value) {
            clearOutput();
            if (value) clearModifierSources();
        }
        editMode = value;
        updateRegistration();
    }

    synchronized void setHasProfile(boolean value) {
        if (hasProfile && !value) {
            clearOutput();
            clearModifierSources();
        }
        hasProfile = value;
        updateRegistration();
    }

    synchronized void setForeground(boolean value) {
        if (foreground && !value) {
            clearOutput();
            clearModifierSources();
        }
        foreground = value;
        updateRegistration();
    }

    synchronized void setGameplayActive(boolean value) {
        if (gameplayActive && !value) {
            clearOutput();
            clearModifierSources();
        }
        gameplayActive = value;
        updateRegistration();
    }

    synchronized void setOverlaySuppressed(boolean value) {
        if (!overlaySuppressed && value) {
            clearOutput();
            clearModifierSources();
        }
        overlaySuppressed = value;
        updateRegistration();
    }

    synchronized void setModifierPressed(boolean value) {
        setModifierPressed(defaultModifierSource, value);
    }

    synchronized void setModifierPressed(@NonNull Object source, boolean value) {
        boolean wasActive = isActivationSatisfied();
        boolean wasPressed = modifierPressed;
        boolean changed = value ? modifierSources.add(source) : modifierSources.remove(source);
        if (!changed) return;
        modifierPressed = !modifierSources.isEmpty();
        if (settings.getActivationMode() == GyroSettings.ACTIVATION_TOGGLE && modifierPressed && !wasPressed) {
            toggleActive = !toggleActive;
        }
        if (wasActive && !isActivationSatisfied()) clearOutput();
        updateRegistration();
    }

    synchronized void clearModifierSources() {
        modifierSources.clear();
        modifierPressed = false;
    }

    synchronized void resetActivationState() {
        boolean wasActive = isActivationSatisfied();
        clearModifierSources();
        toggleActive = false;
        if (registered && wasActive && !isActivationSatisfied()) clearOutput();
        updateRegistration();
    }

    synchronized void onAttachedToWindow() {
        attached = true;
        updateRegistration();
    }

    synchronized void onDetachedFromWindow() {
        attached = false;
        clearModifierSources();
        unregister();
        clearOutput();
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        Sensor activeSensor = registeredSensor;
        if (event == null || event.sensor == null || activeSensor == null
                || event.sensor.getType() != activeSensor.getType() || event.values == null) return;
        if (!shouldRun()) return;

        int rotation = Surface.ROTATION_0;
        if (windowManager != null && windowManager.getDefaultDisplay() != null) {
            rotation = windowManager.getDefaultDisplay().getRotation();
        }

        if (isTiltSteeringActive()) {
            processTiltSteering(event, rotation);
            return;
        }

        if (event.sensor.getType() != Sensor.TYPE_GYROSCOPE || event.values.length < 2
                || !Float.isFinite(event.values[0]) || !Float.isFinite(event.values[1])) return;
        float[] rates = mapAndFilterRates(-event.values[1], -event.values[0], rotation);
        if (settings.getMode() == GyroSettings.MODE_MOUSE) {
            if (settings.getSmoothingMilliseconds() > 0f) {
                rates = smoothRates(rates[0], rates[1], event.timestamp);
            }
            if (lastTimestampNs == 0L) {
                lastTimestampNs = event.timestamp;
                return;
            }
            float seconds = (event.timestamp - lastTimestampNs) * 1.0e-9f;
            lastTimestampNs = event.timestamp;
            if (!(seconds > 0f) || seconds > MAX_MOUSE_EVENT_DELTA_SECONDS) return;

            int[] delta = integrateMouse(rates[0], rates[1], seconds);
            int deltaX = delta[0];
            int deltaY = delta[1];
            if (deltaX != 0 || deltaY != 0) listener.onGyroMouseDelta(deltaX, deltaY);
            return;
        }

        float x = rates[0] * STICK_UNITS_PER_RADIAN_PER_SECOND * settings.getSensitivity();
        float y = rates[1] * STICK_UNITS_PER_RADIAN_PER_SECOND
                * settings.getSensitivity() * settings.getVerticalScale();
        float[] stick = applyStickResponse(x, y, event.timestamp);
        dispatchStickOutput(
                stick[0],
                stick[1],
                settings.getMode() == GyroSettings.MODE_RIGHT_STICK,
                event.timestamp);
    }

    private void processTiltSteering(SensorEvent event, int rotation) {
        float steeringAngle = getSteeringAngle(event.values, rotation);
        if (!Float.isFinite(steeringAngle)) return;
        if (!tiltCentered) {
            tiltCenterRadians = steeringAngle;
            tiltCentered = true;
            return;
        }

        float x = tiltAngleToStick(
                steeringAngle,
                tiltCenterRadians,
                settings.getTiltFullScaleDegrees(),
                settings.getTiltDeadzoneDegrees(),
                settings.getInvertX());
        float[] stick = applyStickResponse(x, 0f, event.timestamp);
        dispatchStickOutput(
                stick[0],
                0f,
                settings.getMode() == GyroSettings.MODE_RIGHT_STICK,
                event.timestamp);
    }

    void dispatchStickOutput(float x, float y, boolean rightStick, long timestampNs) {
        short encodedX = GamepadState.encodeThumbAxis(x);
        short encodedY = GamepadState.encodeThumbAxis(y);
        if (hasDispatchedStick
                && rightStick == lastDispatchedRightStick
                && encodedX == lastDispatchedStickX
                && encodedY == lastDispatchedStickY) return;

        boolean neutral = encodedX == 0 && encodedY == 0;
        if (hasDispatchedStick
                && !neutral
                && timestampNs > lastStickOutputTimestampNs
                && timestampNs - lastStickOutputTimestampNs < MIN_STICK_OUTPUT_INTERVAL_NS) return;

        hasDispatchedStick = true;
        lastDispatchedStickX = encodedX;
        lastDispatchedStickY = encodedY;
        lastDispatchedRightStick = rightStick;
        lastStickOutputTimestampNs = timestampNs;
        listener.onGyroStick(x, y, rightStick);
    }

    private float getSteeringAngle(float[] rotationVector, int displayRotation) {
        if (rotationVector.length < 3) return Float.NaN;
        int componentCount = Math.min(rotationVector.length, 4);
        for (int i = 0; i < componentCount; i++) {
            if (!Float.isFinite(rotationVector[i])) return Float.NaN;
        }
        try {
            float[] boundedRotationVector = componentCount == 3 ? rotationVector3 : rotationVector4;
            System.arraycopy(rotationVector, 0, boundedRotationVector, 0, componentCount);
            SensorManager.getRotationMatrixFromVector(rotationMatrix, boundedRotationVector);
            int axisX = SensorManager.AXIS_X;
            int axisY = SensorManager.AXIS_Y;
            switch (displayRotation) {
                case Surface.ROTATION_90:
                    axisX = SensorManager.AXIS_Y;
                    axisY = SensorManager.AXIS_MINUS_X;
                    break;
                case Surface.ROTATION_180:
                    axisX = SensorManager.AXIS_MINUS_X;
                    axisY = SensorManager.AXIS_MINUS_Y;
                    break;
                case Surface.ROTATION_270:
                    axisX = SensorManager.AXIS_MINUS_Y;
                    axisY = SensorManager.AXIS_X;
                    break;
                default:
                    break;
            }
            if (!SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    axisX,
                    axisY,
                    remappedRotationMatrix)) return Float.NaN;
            SensorManager.getOrientation(remappedRotationMatrix, orientationAngles);
            return orientationAngles[2];
        } catch (RuntimeException ignored) {
            return Float.NaN;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No action required. Android's calibrated gyroscope already compensates sensor bias.
    }

    float[] mapAndFilterRates(float rawX, float rawY, int rotation) {
        float x;
        float y;
        switch (rotation) {
            case Surface.ROTATION_90:
                x = rawY;
                y = -rawX;
                break;
            case Surface.ROTATION_180:
                x = -rawX;
                y = -rawY;
                break;
            case Surface.ROTATION_270:
                x = -rawY;
                y = rawX;
                break;
            case Surface.ROTATION_0:
            default:
                x = rawX;
                y = rawY;
                break;
        }

        if (settings.getInvertX()) x = -x;
        if (settings.getInvertY()) y = -y;

        float magnitude = (float)Math.hypot(x, y);
        float threshold = settings.getSteadyingDegreesPerSecond() / RADIANS_TO_DEGREES;
        if (magnitude <= threshold || magnitude == 0f) return new float[]{0f, 0f};
        float scale = (magnitude - threshold) / magnitude;
        return new float[]{x * scale, y * scale};
    }

    static float[] applyStickAntiDeadzone(float x, float y, float antiDeadzone) {
        float magnitude = (float)Math.hypot(x, y);
        if (magnitude == 0f) return new float[]{0f, 0f};
        float clampedMagnitude = Math.min(1f, magnitude);
        float remapped = antiDeadzone + (1f - antiDeadzone) * clampedMagnitude;
        float scale = remapped / magnitude;
        return new float[]{clamp(x * scale), clamp(y * scale)};
    }

    float[] applyStickResponse(float x, float y, long timestampNs) {
        float[] stick = applyStickAntiDeadzone(x, y, settings.getStickAntiDeadzone());
        return settings.getSmoothingMilliseconds() > 0f
                ? smoothRates(stick[0], stick[1], timestampNs)
                : stick;
    }

    static float tiltAngleToStick(
            float angle,
            float center,
            float fullScaleDegrees,
            float deadzoneDegrees,
            boolean invert) {
        float delta = (float)Math.IEEEremainder(angle - center, Math.PI * 2.0);
        float magnitude = Math.abs(delta);
        float deadzone = deadzoneDegrees / RADIANS_TO_DEGREES;
        if (magnitude <= deadzone) return 0f;
        float fullScale = fullScaleDegrees / RADIANS_TO_DEGREES;
        float adjusted = Math.copySign(magnitude - deadzone, delta);
        float value = adjusted / (fullScale - deadzone);
        return clamp(invert ? -value : value);
    }

    int[] integrateMouse(float rateX, float rateY, float seconds) {
        mouseRemainderX += rateX * seconds * MOUSE_PIXELS_PER_RADIAN * settings.getSensitivity();
        mouseRemainderY += rateY * seconds * MOUSE_PIXELS_PER_RADIAN
                * settings.getSensitivity() * settings.getVerticalScale();
        int deltaX = (int)mouseRemainderX;
        int deltaY = (int)mouseRemainderY;
        mouseRemainderX -= deltaX;
        mouseRemainderY -= deltaY;
        return new int[]{deltaX, deltaY};
    }

    float[] smoothRates(float rateX, float rateY, long timestampNs) {
        float smoothingMs = settings.getSmoothingMilliseconds();
        long previousTimestampNs = lastSmoothingTimestampNs;
        lastSmoothingTimestampNs = timestampNs;
        if (smoothingMs <= 0f || !hasSmoothedRates || previousTimestampNs == 0L) {
            hasSmoothedRates = true;
            smoothedRateX = rateX;
            smoothedRateY = rateY;
            return new float[]{rateX, rateY};
        }

        float seconds = (timestampNs - previousTimestampNs) * 1.0e-9f;
        if (!(seconds > 0f) || seconds > MAX_SMOOTHING_DELTA_SECONDS) {
            smoothedRateX = rateX;
            smoothedRateY = rateY;
            return new float[]{rateX, rateY};
        }

        float timeConstantSeconds = smoothingMs * 0.001f;
        float alpha = 1f - (float)Math.exp(-seconds / timeConstantSeconds);
        smoothedRateX += alpha * (rateX - smoothedRateX);
        smoothedRateY += alpha * (rateY - smoothedRateY);
        return new float[]{smoothedRateX, smoothedRateY};
    }

    private boolean shouldRun() {
        return attached && foreground && gameplayActive && !overlaySuppressed && !editMode
                && hasProfile && desiredSensor() != null
                && settings.getMode() != GyroSettings.MODE_DISABLED
                && isActivationSatisfied();
    }

    private boolean isTiltSteeringActive() {
        return settings.getTiltSteeringEnabled()
                && settings.getMode() != GyroSettings.MODE_MOUSE
                && settings.getMode() != GyroSettings.MODE_DISABLED
                && orientationSensor != null;
    }

    private Sensor desiredSensor() {
        return isTiltSteeringActive() ? orientationSensor : gyroscopeSensor;
    }

    private boolean isActivationSatisfied() {
        switch (settings.getActivationMode()) {
            case GyroSettings.ACTIVATION_HOLD:
                return modifierPressed;
            case GyroSettings.ACTIVATION_TOGGLE:
                return toggleActive;
            case GyroSettings.ACTIVATION_RATCHET:
                return !modifierPressed;
            case GyroSettings.ACTIVATION_ALWAYS:
            default:
                return true;
        }
    }

    private void updateRegistration() {
        if (!shouldRun()) {
            unregister();
            return;
        }
        if (registered) {
            publishActiveState();
            return;
        }
        resetMotionState();
        Sensor sensor = desiredSensor();
        registered = sensor != null && sensorManager.registerListener(this, sensor, SENSOR_PERIOD_US);
        registeredSensor = registered ? sensor : null;
        publishActiveState();
    }

    private void unregister() {
        if (sensorManager != null && registered && registeredSensor != null) {
            sensorManager.unregisterListener(this, registeredSensor);
        }
        registered = false;
        registeredSensor = null;
        resetMotionState();
        publishActiveState();
    }

    private void publishActiveState() {
        if (reportedActive == registered) return;
        reportedActive = registered;
        listener.onGyroActiveChanged(registered);
    }

    private void clearOutput() {
        resetMotionState();
        if (settings.getMode() == GyroSettings.MODE_LEFT_STICK) {
            listener.onGyroStick(0f, 0f, false);
        } else if (settings.getMode() == GyroSettings.MODE_RIGHT_STICK) {
            listener.onGyroStick(0f, 0f, true);
        }
    }

    private void resetMotionState() {
        lastTimestampNs = 0L;
        lastSmoothingTimestampNs = 0L;
        mouseRemainderX = 0.0;
        mouseRemainderY = 0.0;
        hasSmoothedRates = false;
        smoothedRateX = 0f;
        smoothedRateY = 0f;
        tiltCentered = false;
        tiltCenterRadians = 0f;
        hasDispatchedStick = false;
        lastDispatchedStickX = 0;
        lastDispatchedStickY = 0;
        lastDispatchedRightStick = false;
        lastStickOutputTimestampNs = 0L;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }
}
