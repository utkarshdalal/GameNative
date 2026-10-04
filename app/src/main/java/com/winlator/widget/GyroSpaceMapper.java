package com.winlator.widget;

import android.view.Surface;

import app.gamenative.data.GyroSettings;

/** Projects angular velocity onto aiming axes. Up is a unit vector in device coordinates. */
final class GyroSpaceMapper {
    private GyroSpaceMapper() {}

    static float[] map(float deviceX, float deviceY, float deviceZ, int rotation,
                       int style, float upX, float upY, float upZ) {
        float pitch = deviceX;
        float yaw = deviceY;
        float roll = deviceZ;
        // Express both vectors in screen coordinates, matching SensorManager.remapCoordinateSystem.
        // At ROTATION_90, natural +X points screen-up and natural +Y points screen-left.
        switch (rotation) {
            case Surface.ROTATION_90:
                pitch = -deviceY;
                yaw = deviceX;
                float oldUpX90 = upX;
                upX = -upY;
                upY = oldUpX90;
                break;
            case Surface.ROTATION_180:
                pitch = -deviceX;
                yaw = -deviceY;
                upX = -upX;
                upY = -upY;
                break;
            case Surface.ROTATION_270:
                pitch = deviceY;
                yaw = -deviceX;
                float oldUpX270 = upX;
                upX = upY;
                upY = -oldUpX270;
                break;
            default:
                break;
        }

        float horizontal = yaw;
        float vertical = pitch;
        switch (style) {
            case GyroSettings.CONVERSION_LOCAL_ROLL:
                horizontal = roll;
                break;
            case GyroSettings.CONVERSION_LOCAL_YAW_ROLL:
                // A continuous sum avoids abrupt sign flips when the dominant axis changes.
                horizontal = yaw + roll;
                break;
            case GyroSettings.CONVERSION_PLAYER_SPACE:
                // Jibb Smart's player-space projection, adapted to Android's upward vector:
                // https://gyrowiki.jibbsmart.com/blog:player-space-gyro-and-alternatives-explained
                float worldYaw = yaw * upY + roll * upZ;
                horizontal = Math.signum(worldYaw) * Math.min(
                        Math.abs(worldYaw) * 1.41f, (float)Math.hypot(yaw, roll));
                break;
            case GyroSettings.CONVERSION_WORLD_SPACE:
                horizontal = pitch * upX + yaw * upY + roll * upZ;
                // Project the screen's pitch axis onto the plane perpendicular to gravity.
                float axisX = 1f - upX * upX;
                float axisY = -upX * upY;
                float axisZ = -upX * upZ;
                float length = (float)Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
                // Fade pitch near the singularity with the device on its side.
                float sideReduction = Math.max(0f, Math.min(1f,
                        (Math.max(Math.abs(upY), Math.abs(upZ)) - 0.125f) / 0.125f));
                vertical = length > 0.0001f
                        ? sideReduction * (pitch * axisX + yaw * axisY + roll * axisZ) / length
                        : 0f;
                break;
            default:
                break;
        }

        // Mouse/stick signs are fixed in screen space. This preserves the original Local Yaw
        // mapping without reversing roll or gravity-based yaw when the display rotates.
        return new float[]{-horizontal, -vertical};
    }
}
