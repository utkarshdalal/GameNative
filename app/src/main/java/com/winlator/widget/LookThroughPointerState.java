package com.winlator.widget;

/** Tracks the pointer exclusively owned by general button look-through. */
final class LookThroughPointerState {
    static final int INVALID_POINTER_ID = -1;

    static final class Delta {
        float x;
        float y;
    }

    private int pointerId = INVALID_POINTER_ID;
    private float startX;
    private float startY;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private final Delta moveDelta = new Delta();

    boolean tryStart(int pointerId, float x, float y, boolean normalTouchActive) {
        if (this.pointerId != INVALID_POINTER_ID || normalTouchActive) return false;
        this.pointerId = pointerId;
        startX = lastX = x;
        startY = lastY = y;
        dragging = false;
        return true;
    }

    boolean owns(int pointerId) {
        return this.pointerId == pointerId;
    }

    boolean isActive() {
        return pointerId != INVALID_POINTER_ID;
    }

    Delta move(int pointerId, float x, float y, float touchSlop) {
        if (!owns(pointerId)) return null;

        if (!dragging) {
            float dx = x - startX;
            float dy = y - startY;
            if (dx * dx + dy * dy < touchSlop * touchSlop) return null;
            dragging = true;
        }

        moveDelta.x = x - lastX;
        moveDelta.y = y - lastY;
        lastX = x;
        lastY = y;
        return moveDelta;
    }

    void release(int pointerId) {
        if (owns(pointerId)) {
            this.pointerId = INVALID_POINTER_ID;
            dragging = false;
        }
    }

    void clear() {
        pointerId = INVALID_POINTER_ID;
        dragging = false;
    }
}
