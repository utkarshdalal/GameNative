package com.winlator.inputcontrols;

import com.winlator.math.Mathf;

/**
 * Pure two-dimensional transforms shared by physical-stick passthrough and digital bindings. The
 * {@link MutableVector} overloads write into a caller-owned result, so the per-event path doesn't allocate.
 */
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

    /** Reusable result of the allocation-free overloads. */
    public static final class MutableVector {
        public float x;
        public float y;

        public MutableVector() {}

        public MutableVector(float x, float y) {
            set(x, y);
        }

        public void set(float x, float y) {
            this.x = x;
            this.y = y;
        }

        public Vector toVector() {
            return x == 0 && y == 0 ? Vector.ZERO : new Vector(x, y);
        }
    }

    public static Vector tune(
            float x,
            float y,
            float deadzone,
            float sensitivity,
            ControlsProfile.StickDeadzoneMode mode) {
        MutableVector out = new MutableVector();
        tune(x, y, deadzone, sensitivity, mode, out);
        return out.toVector();
    }

    /** {@link #tune(float, float, float, float, ControlsProfile.StickDeadzoneMode)} into {@code out}. */
    public static void tune(
            float x,
            float y,
            float deadzone,
            float sensitivity,
            ControlsProfile.StickDeadzoneMode mode,
            MutableVector out) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            out.set(0, 0);
            return;
        }
        ControlsProfile.StickDeadzoneMode resolvedMode = mode != null
                ? mode
                : ControlsProfile.DEFAULT_STICK_DEADZONE_MODE;
        switch (resolvedMode) {
            case CIRCULAR:
                applyCircularDeadzone(x, y, deadzone, out);
                break;
            case HYBRID:
                applyHybridDeadzone(x, y, deadzone, out);
                break;
            case AXIAL:
            default:
                out.set(
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
        float scaledX = out.x * resolvedSensitivity;
        float scaledY = out.y * resolvedSensitivity;
        if (resolvedMode == ControlsProfile.StickDeadzoneMode.AXIAL) {
            out.set(Mathf.clamp(scaledX, -1, 1), Mathf.clamp(scaledY, -1, 1));
            return;
        }
        clampToUnitCircle(scaledX, scaledY, out);
    }

    static void applyCircularDeadzone(float x, float y, float deadzone, MutableVector out) {
        float resolvedDeadzone = resolveDeadzone(deadzone);
        // Squared: a stick at rest or inside the dead zone, the common case, needs no root.
        double magnitudeSquared = magnitudeSquared(x, y);
        double threshold = resolvedDeadzone + MAGNITUDE_EPSILON;
        if (magnitudeSquared <= threshold * threshold || resolvedDeadzone >= 1.0f) {
            out.set(0, 0);
            return;
        }
        double magnitude = Math.sqrt(magnitudeSquared);
        float outputMagnitude = Mathf.clamp(
                (float)((magnitude - resolvedDeadzone) / (1.0f - resolvedDeadzone)),
                0,
                1);
        float scale = outputMagnitude / (float)magnitude;
        out.set(x * scale, y * scale);
    }

    static void applyHybridDeadzone(float x, float y, float deadzone, MutableVector out) {
        float resolvedDeadzone = resolveDeadzone(deadzone);
        applyCircularDeadzone(x, y, resolvedDeadzone, out);
        float circularX = out.x;
        float circularY = out.y;
        if (circularX == 0 && circularY == 0) return;

        // Add narrow axial corridors to a circular center without changing diagonal magnitude.
        float outputX = Math.abs(circularX) <= resolvedDeadzone * Math.abs(circularY)
                ? 0
                : circularX;
        float outputY = Math.abs(circularY) <= resolvedDeadzone * Math.abs(circularX)
                ? 0
                : circularY;
        out.set(outputX, outputY);
    }

    private static void clampToUnitCircle(float x, float y, MutableVector out) {
        double magnitudeSquared = magnitudeSquared(x, y);
        if (magnitudeSquared <= 1.0) {
            out.set(x, y);
            return;
        }
        float scale = 1.0f / (float)Math.sqrt(magnitudeSquared);
        out.set(x * scale, y * scale);
    }

    // Squared length, compared against squared bounds so a root is taken only where the length itself is needed.
    // (Math.hypot guards against overflow at a large cost; stick vectors are bounded.)
    private static double magnitudeSquared(float x, float y) {
        return (double)x * x + (double)y * y;
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
        MutableVector out = new MutableVector();
        snapToDirection(x, y, direction, out);
        return out.toVector();
    }

    /** {@link #snapToDirection(float, float, int)} into {@code out}, which may hold {@code x} and {@code y}. */
    static void snapToDirection(float x, float y, int direction, MutableVector out) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || direction < 0 || direction > 7) {
            out.set(0, 0);
            return;
        }
        double magnitudeSquared = magnitudeSquared(x, y);
        float strength = magnitudeSquared >= 1.0 ? 1 : (float)Math.sqrt(magnitudeSquared);
        float diagonalComponent = strength * INVERSE_SQRT_TWO;
        switch (direction) {
            case 0: out.set(strength, 0); break;
            case 1: out.set(diagonalComponent, diagonalComponent); break;
            case 2: out.set(0, strength); break;
            case 3: out.set(-diagonalComponent, diagonalComponent); break;
            case 4: out.set(-strength, 0); break;
            case 5: out.set(-diagonalComponent, -diagonalComponent); break;
            case 6: out.set(0, -strength); break;
            case 7: out.set(diagonalComponent, -diagonalComponent); break;
            default: out.set(0, 0); break;
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
