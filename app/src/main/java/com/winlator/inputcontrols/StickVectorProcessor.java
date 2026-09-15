package com.winlator.inputcontrols;

import com.winlator.math.Mathf;

/** Pure two-dimensional transforms shared by physical-stick passthrough and digital bindings. */
public final class StickVectorProcessor {
    public static final int DIRECTION_NONE = -1;
    private static final double MAGNITUDE_EPSILON = 1.0e-6;
    private static final double HYSTERESIS_RADIANS = Math.toRadians(5.0);
    private static final double TWO_PI = Math.PI * 2.0;
    private static final float INVERSE_SQRT_TWO = (float)(1.0 / Math.sqrt(2.0));

    private StickVectorProcessor() {}

    public static final class Vector {
        public static final Vector ZERO = new Vector(0, 0);
        public final float x;
        public final float y;

        public Vector(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static Vector tune(
            float x,
            float y,
            float deadzone,
            float sensitivity,
            ControlsProfile.StickDeadzoneMode mode) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return Vector.ZERO;
        ControlsProfile.StickDeadzoneMode resolvedMode = mode != null
                ? mode
                : ControlsProfile.DEFAULT_STICK_DEADZONE_MODE;
        Vector adjusted;
        switch (resolvedMode) {
            case CIRCULAR:
                adjusted = applyCircularDeadzone(x, y, deadzone);
                break;
            case HYBRID:
                adjusted = applyHybridDeadzone(x, y, deadzone);
                break;
            case AXIAL:
            default:
                adjusted = new Vector(
                        ControlsProfile.applyStickDeadzone(x, deadzone),
                        ControlsProfile.applyStickDeadzone(y, deadzone));
                break;
        }

        float resolvedSensitivity = Float.isFinite(sensitivity)
                ? Mathf.clamp(
                        sensitivity,
                        ControlsProfile.MIN_STICK_SENSITIVITY,
                        ControlsProfile.MAX_STICK_SENSITIVITY)
                : ControlsProfile.DEFAULT_STICK_SENSITIVITY;
        float scaledX = adjusted.x * resolvedSensitivity;
        float scaledY = adjusted.y * resolvedSensitivity;
        if (resolvedMode == ControlsProfile.StickDeadzoneMode.AXIAL) {
            return new Vector(Mathf.clamp(scaledX, -1, 1), Mathf.clamp(scaledY, -1, 1));
        }
        return clampToUnitCircle(scaledX, scaledY);
    }

    static Vector applyCircularDeadzone(float x, float y, float deadzone) {
        float resolvedDeadzone = resolveDeadzone(deadzone);
        double magnitude = Math.hypot(x, y);
        if (magnitude <= resolvedDeadzone + MAGNITUDE_EPSILON || resolvedDeadzone >= 1.0f) {
            return Vector.ZERO;
        }
        float outputMagnitude = Mathf.clamp(
                (float)((magnitude - resolvedDeadzone) / (1.0f - resolvedDeadzone)),
                0,
                1);
        float scale = outputMagnitude / (float)magnitude;
        return new Vector(x * scale, y * scale);
    }

    static Vector applyHybridDeadzone(float x, float y, float deadzone) {
        float resolvedDeadzone = resolveDeadzone(deadzone);
        Vector circular = applyCircularDeadzone(x, y, resolvedDeadzone);
        if (circular.x == 0 && circular.y == 0) return circular;

        // Add narrow axial corridors to a circular center without changing diagonal magnitude.
        float outputX = Math.abs(circular.x) <= resolvedDeadzone * Math.abs(circular.y)
                ? 0
                : circular.x;
        float outputY = Math.abs(circular.y) <= resolvedDeadzone * Math.abs(circular.x)
                ? 0
                : circular.y;
        return new Vector(outputX, outputY);
    }

    private static Vector clampToUnitCircle(float x, float y) {
        double magnitude = Math.hypot(x, y);
        if (magnitude <= 1.0) return new Vector(x, y);
        float scale = 1.0f / (float)magnitude;
        return new Vector(x * scale, y * scale);
    }

    private static float resolveDeadzone(float deadzone) {
        if (!Float.isFinite(deadzone)) return ControlsProfile.DEFAULT_STICK_DEADZONE;
        return Mathf.clamp(
                deadzone,
                ControlsProfile.MIN_STICK_DEADZONE,
                ControlsProfile.MAX_STICK_DEADZONE);
    }

    /**
     * Resolves a stable 4-way or 8-way sector. Direction indices are clockwise eighth-turns:
     * right=0, down=2, left=4, up=6. The previous sector gets a five-degree margin.
     */
    public static int snapDirection(
            float x,
            float y,
            ControlsProfile.StickDigitalMode mode,
            int previousDirection) {
        if (x == 0 && y == 0) return DIRECTION_NONE;
        if (!Float.isFinite(x) || !Float.isFinite(y)) return DIRECTION_NONE;
        ControlsProfile.StickDigitalMode resolvedMode = mode != null
                ? mode
                : ControlsProfile.DEFAULT_STICK_DIGITAL_MODE;
        if (resolvedMode == ControlsProfile.StickDigitalMode.UNRESTRICTED) return DIRECTION_NONE;

        int sectorCount = resolvedMode == ControlsProfile.StickDigitalMode.FOUR_WAY ? 4 : 8;
        double sectorSize = TWO_PI / sectorCount;
        double angle = normalizeAngle(Math.atan2(y, x));
        if (isDirectionValid(previousDirection, sectorCount)) {
            double previousCenter = previousDirection * Math.PI / 4.0;
            if (angularDistance(angle, previousCenter) <= sectorSize / 2.0 + HYSTERESIS_RADIANS) {
                return previousDirection;
            }
        }

        int sector = (int)Math.floor((angle + sectorSize / 2.0) / sectorSize) % sectorCount;
        return sectorCount == 4 ? sector * 2 : sector;
    }

    static Vector snapToDirection(float x, float y, int direction) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || direction < 0 || direction > 7) {
            return Vector.ZERO;
        }
        float strength = Mathf.clamp((float)Math.hypot(x, y), 0, 1);
        float diagonalComponent = strength * INVERSE_SQRT_TWO;
        switch (direction) {
            case 0: return new Vector(strength, 0);
            case 1: return new Vector(diagonalComponent, diagonalComponent);
            case 2: return new Vector(0, strength);
            case 3: return new Vector(-diagonalComponent, diagonalComponent);
            case 4: return new Vector(-strength, 0);
            case 5: return new Vector(-diagonalComponent, -diagonalComponent);
            case 6: return new Vector(0, -strength);
            case 7: return new Vector(diagonalComponent, -diagonalComponent);
            default: return Vector.ZERO;
        }
    }

    private static boolean isDirectionValid(int direction, int sectorCount) {
        if (direction < 0 || direction > 7) return false;
        return sectorCount == 8 || direction % 2 == 0;
    }

    private static double normalizeAngle(double angle) {
        return angle < 0 ? angle + TWO_PI : angle;
    }

    private static double angularDistance(double first, double second) {
        double difference = Math.abs(normalizeAngle(first) - normalizeAngle(second));
        return Math.min(difference, TWO_PI - difference);
    }
}
