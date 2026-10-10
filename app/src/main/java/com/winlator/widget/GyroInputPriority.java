package com.winlator.widget;

import app.gamenative.data.GyroSettings;
import com.winlator.math.Mathf;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Arbitration after input deadzones/steadying, shared by mouse movement producers. */
final class GyroInputPriority {
    // Touch drags are deltas rather than held axes; bridge the gaps between move events.
    static final long TOUCH_MOTION_GRACE_MS = 80;
    private final Set<Object> manualMouseSources = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean preferManualMouse;
    private long touchMovingUntil;

    synchronized void setSettings(GyroSettings settings) {
        preferManualMouse = settings.getMode() == GyroSettings.MODE_MOUSE
                && settings.getInputPriority() == GyroSettings.PRIORITY_STICK_TOUCH;
    }

    static float mixStickAxis(int priority, float base, float gyro, boolean baseMoving) {
        if (priority == GyroSettings.PRIORITY_STICK_TOUCH) return Mathf.clamp(baseMoving ? base : gyro, -1f, 1f);
        return Mathf.clamp(base + gyro, -1f, 1f);
    }

    synchronized void setManualMouseMoving(Object source, boolean moving) {
        if (moving) manualMouseSources.add(source);
        else manualMouseSources.remove(source);
    }

    synchronized void onTouchMouseMovement(float x, float y, long now) {
        if (x != 0f || y != 0f) touchMovingUntil = now + TOUCH_MOTION_GRACE_MS;
    }

    synchronized boolean allowGyroMouse(long now) {
        return !preferManualMouse || (manualMouseSources.isEmpty() && now >= touchMovingUntil);
    }

    synchronized void reset() {
        manualMouseSources.clear();
        touchMovingUntil = 0;
    }
}
