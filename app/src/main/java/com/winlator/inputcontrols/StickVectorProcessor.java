package com.winlator.inputcontrols;

import com.winlator.math.Mathf;

/** Pure two-dimensional transforms shared by physical-stick passthrough and digital bindings. */
public final class StickVectorProcessor {
    public static final int DIRECTION_NONE = -1;
    public static final int MASK_RIGHT = 1;
    public static final int MASK_DOWN = 1 << 1;
    public static final int MASK_LEFT = 1 << 2;
    public static final int MASK_UP = 1 << 3;
    public static final int MASK_ALL = MASK_RIGHT | MASK_DOWN | MASK_LEFT | MASK_UP;
    private static final double MAGNITUDE_EPSILON = 1.0e-6;
    private static final double HYSTERESIS_RADIANS = Math.toRadians(5.0);
    private static final double TWO_PI = Math.PI * 2.0;

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

    public static int directionMask(int direction) {
        switch (direction) {
            case 0: return MASK_RIGHT;
            case 1: return MASK_RIGHT | MASK_DOWN;
            case 2: return MASK_DOWN;
            case 3: return MASK_DOWN | MASK_LEFT;
            case 4: return MASK_LEFT;
            case 5: return MASK_LEFT | MASK_UP;
            case 6: return MASK_UP;
            case 7: return MASK_UP | MASK_RIGHT;
            default: return 0;
        }
    }

    public static boolean allowsAxis(int directionMask, boolean horizontal, float value) {
        if (value == 0) return false;
        int requiredMask = horizontal
                ? (value > 0 ? MASK_RIGHT : MASK_LEFT)
                : (value > 0 ? MASK_DOWN : MASK_UP);
        return (directionMask & requiredMask) != 0;
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
