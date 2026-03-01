package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.gamenative.R;
import app.gamenative.data.TouchGestureConfig;
import com.winlator.core.AppUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.ViewTransformation;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XKeycode;
import com.winlator.xserver.XServer;

public class TouchpadView extends View implements View.OnCapturedPointerListener {
    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.5f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private final Finger[] fingers;
    private Runnable fourFingersTapCallback;
    private boolean moveCursorToTouchpoint;
    private byte numFingers;
    private boolean pointerButtonLeftEnabled;
    private boolean pointerButtonRightEnabled;
    private float scrollAccumY;
    private boolean scrolling;
    private float sensitivity;
    private final XServer xServer;
    private final float[] xform;
    private boolean simTouchScreen = false;
    private boolean continueClick = true;
    private int lastTouchedPosX;
    private int lastTouchedPosY;
    private static final Byte CLICK_DELAYED_TIME = 50;
    private static final Byte EFFECTIVE_TOUCH_DISTANCE = 20;
    private float resolutionScale;
    private static final int UPDATE_FORM_DELAYED_TIME = 50;
    private boolean touchscreenMouseDisabled = false;
    private boolean isTouchscreenMode = false;
    private Runnable delayedPress;

    private boolean pressExecuted;
    private final boolean capturePointerOnExternalMouse;
    private boolean pointerCaptureRequested;

    // ── Gesture configuration ────────────────────────────────────────
    private TouchGestureConfig gestureConfig = new TouchGestureConfig();

    // Long-press detection
    private Runnable longPressRunnable;
    private boolean longPressTriggered;

    // Double-tap detection
    private long lastTapTime;
    private float lastTapX;
    private float lastTapY;
    private boolean doubleTapDetected;

    // Drag tracking (box selection)
    private boolean isDragging;
    private boolean dragButtonPressed;

    // Two-finger tracking
    private boolean twoFingerDragging;
    private float twoFingerLastX0, twoFingerLastY0;
    private float twoFingerLastX1, twoFingerLastY1;
    private boolean twoFingerMiddleButtonDown;
    // Pinch tracking
    private float pinchLastDistance;
    // Two-finger tap detection
    private boolean twoFingerTapPossible;
    // Track which WASD/arrow keys are currently held
    private boolean panKeyUp, panKeyDown, panKeyLeft, panKeyRight;
    // True when finger has moved beyond tap tolerance (prevents spurious tap on lift)
    private boolean movedBeyondTapThreshold;
    // Remember which keycodes were actually pressed so releasePanKeys releases only those
    private XKeycode panLeftKey, panRightKey, panUpKey, panDownKey;
    // Long-press movement tolerance (pixels of raw finger movement before cancelling long-press)
    private static final float LONG_PRESS_MOVE_TOLERANCE = 50f;
    // Raw touch-down coordinates for measuring finger travel
    private float touchDownRawX, touchDownRawY;
    // Two-finger gesture mutual exclusion (only pan OR pinch, not both)
    private static final int TWO_FINGER_GESTURE_NONE = 0;
    private static final int TWO_FINGER_GESTURE_PAN = 1;
    private static final int TWO_FINGER_GESTURE_ZOOM = 2;
    private int twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
    // Accumulated deltas for deciding which two-finger gesture to lock into
    private float accumulatedPinchDelta, accumulatedPanDelta;

    public TouchpadView(Context context, XServer xServer, boolean capturePointerOnExternalMouse) {
        super(context);
        this.capturePointerOnExternalMouse = capturePointerOnExternalMouse;
        this.fingers = new Finger[4];
        this.numFingers = (byte) 0;
        this.sensitivity = 1.0f;
        this.pointerButtonLeftEnabled = true;
        this.pointerButtonRightEnabled = true;
        this.moveCursorToTouchpoint = false;
        this.scrollAccumY = 0.0f;
        this.scrolling = false;
        this.xform = XForm.getInstance();
        this.simTouchScreen = false;
        this.continueClick = true;
        this.touchscreenMouseDisabled = false;
        this.isTouchscreenMode = false;
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBackground());
        setClickable(true);
        setFocusable(true);
        int screenWidth = AppUtils.getScreenWidth();
        int screenHeight = AppUtils.getScreenHeight();
        ScreenInfo screenInfo = xServer.screenInfo;
        updateXform(screenWidth, screenHeight, screenInfo.width, screenInfo.height);
        if (capturePointerOnExternalMouse) {
            setFocusableInTouchMode(true);
            setOnCapturedPointerListener(this);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // allow re-capture after app returns from background
        if (hasFocus) pointerCaptureRequested = false;
    }

    private static StateListDrawable createTransparentBackground() {
        StateListDrawable stateListDrawable = new StateListDrawable();
        ColorDrawable focusedDrawable = new ColorDrawable(0);
        ColorDrawable defaultDrawable = new ColorDrawable(0);
        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[0], defaultDrawable);
        return stateListDrawable;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ScreenInfo screenInfo = this.xServer.screenInfo;
        updateXform(w, h, screenInfo.width, screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);
        float invAspect = 1.0f / viewTransformation.aspect;
        if (!this.xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(this.xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(this.xform, invAspect, invAspect);
        } else {
            XForm.makeScale(this.xform, invAspect, invAspect);
        }
    }

    private class Finger {
        private int lastX;
        private int lastY;
        private final int startX;
        private final int startY;
        private final long touchTime;
        private int x;
        private int y;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(TouchpadView.this.xform, x, y);
            this.x = this.startX = this.lastX = (int)transformedPoint[0];
            this.y = this.startY = this.lastY = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            this.lastX = this.x;
            this.lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(TouchpadView.this.xform, x, y);
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
        }

        public int deltaX() {
            float dx = (this.x - this.lastX) * TouchpadView.this.sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dx);
        }

        public int deltaY() {
            float dy = (this.y - this.lastY) * TouchpadView.this.sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dy);
        }

        public boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        public float travelDistance() {
            return (float) Math.hypot(this.x - this.startX, this.y - this.startY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int toolType = event.getToolType(0);
        if (touchscreenMouseDisabled
                && toolType != MotionEvent.TOOL_TYPE_STYLUS
                && !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return true; // consume without generating mouse events
        }
        if (toolType == MotionEvent.TOOL_TYPE_STYLUS) {
            return handleStylusEvent(event);
        } else if (isTouchscreenMode) {
            return handleTouchscreenEvent(event);
        } else {
            return handleTouchpadEvent(event);
        }
    }

    private boolean handleStylusHoverEvent(MotionEvent event) {
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
                Log.d("StylusEvent", "Hover Enter");
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                Log.d("StylusEvent", "Hover Move: (" + event.getX() + ", " + event.getY() + ")");
                float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                Log.d("StylusEvent", "Hover Exit");
                break;
            default:
                return false;
        }
        return true;
    }

    private boolean handleStylusEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int buttonState = event.getButtonState();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                if ((buttonState & MotionEvent.BUTTON_SECONDARY) != 0) {
                    handleStylusRightClick(event);
                } else {
                    handleStylusLeftClick(event);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                handleStylusMove(event);
                break;
            case MotionEvent.ACTION_UP:
                handleStylusUp(event);
                break;
        }

        return true;
    }

    private void handleStylusLeftClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
    }

    private void handleStylusRightClick(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
    }

    private void handleStylusMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleStylusUp(MotionEvent event) {
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
    }

    private boolean handleTouchpadEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();
        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                if (simTouchScreen) {
                    final Runnable clickDelay = () -> {
                        if (continueClick) {
                            xServer.injectPointerMove(lastTouchedPosX, lastTouchedPosY);
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                        }
                    };
                    if (pointerId == 0) {
                        continueClick = true;
                        if (Math.hypot(fingers[0].x - lastTouchedPosX, fingers[0].y - lastTouchedPosY) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                            lastTouchedPosX = fingers[0].x;
                            lastTouchedPosY = fingers[0].y;
                        }
                        postDelayed(clickDelay, CLICK_DELAYED_TIME);
                    } else if (pointerId == 1) {
                        // When put a finger on InputControl, such as a button.
                        // The pointerId that TouchPadView got won't increase from 1, so map 1 as 0 here.
                        if (numFingers < 2) {
                            continueClick = true;
                            if (Math.hypot(fingers[1].x - lastTouchedPosX, fingers[1].y - lastTouchedPosY) * resolutionScale > EFFECTIVE_TOUCH_DISTANCE) {
                                lastTouchedPosX = fingers[1].x;
                                lastTouchedPosY = fingers[1].y;
                            }
                            postDelayed(clickDelay, CLICK_DELAYED_TIME);
                        } else
                            continueClick = System.currentTimeMillis() - fingers[0].touchTime > CLICK_DELAYED_TIME;
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                    else
                        xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                } else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(fingers[i]);
                            } else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }

        return true;
    }

    private boolean handleTouchscreenEvent(MotionEvent event) {
        // Use gesture-aware handling when gesture system is configured
        if (gestureConfig != null) {
            return handleGestureTouchscreenEvent(event);
        }
        // Original GameNative touchscreen handling
        int action = event.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handleTouchDown(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() == 2) {
                    handleTwoFingerScroll(event);
                } else {
                    handleTouchMove(event);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerCount() == 2) {
                    handleTwoFingerTap(event);
                } else {
                    handleTouchUp(event);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (xServer.isRelativeMouseMovement()) {
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                }
                else {
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                }
                break;
        }
        return true;
    }

    // Original GameNative touchscreen handler methods
    private void handleTouchDown(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
        else
            xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);

        // Handle long press for right click (or use a dedicated method to detect long press)
        if (event.getPointerCount() == 1) {
            if (xServer.isRelativeMouseMovement())
                xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
            else {
                pressExecuted = false;
                delayedPress = () -> {
                    pressExecuted = true;
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                };
                postDelayed(delayedPress, CLICK_DELAYED_TIME);
            }
        }
    }

    private void handleTouchMove(MotionEvent event) {
        float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
        else
            xServer.injectPointerMove((int) transformedPoint[0], (int) transformedPoint[1]);
    }

    private void handleTouchUp(MotionEvent event) {
        if (xServer.isRelativeMouseMovement())
            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
        else {
            if (pressExecuted) {
                // press already happened → release immediately
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            } else {
                // finger lifted *before* the press fires
                // queue a release to run just after the pending press
                postDelayed(() -> xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT), CLICK_DELAYED_TIME);
            }
        }
    }

    private void handleTwoFingerScroll(MotionEvent event) {
        float scrollDistance = event.getY(0) - event.getY(1);
        if (Math.abs(scrollDistance) > 10) {
            if (scrollDistance > 0) {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
            } else {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
            }
        }
    }

    private void handleTwoFingerTap(MotionEvent event) {
        if (event.getPointerCount() == 2) {
            if (xServer.isRelativeMouseMovement()) {
                xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
            }
            else {
                xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
            }
        }
    }

    // Gesture-aware touchscreen handling (delegates to Ts* methods)
    private boolean handleGestureTouchscreenEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                handleTsDown(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                handleTsPointerDown(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (pointerCount >= 2) {
                    handleTsTwoFingerMove(event);
                } else {
                    handleTsMove(event);
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                handleTsPointerUp(event);
                break;
            case MotionEvent.ACTION_UP:
                handleTsUp(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                handleTsCancel();
                break;
        }
        return true;
    }

    // ── Primary finger down ──────────────────────────────────────────
    private void handleTsDown(MotionEvent event) {
        float[] pt = XForm.transformPoint(xform, event.getX(), event.getY());
        int x = (int) pt[0];
        int y = (int) pt[1];

        longPressTriggered = false;
        doubleTapDetected = false;
        isDragging = false;
        dragButtonPressed = false;
        movedBeyondTapThreshold = false;
        twoFingerDragging = false;
        twoFingerTapPossible = false;
        twoFingerMiddleButtonDown = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;

        // Store raw touch coordinates for long-press movement tolerance
        touchDownRawX = event.getX();
        touchDownRawY = event.getY();

        // Move cursor to touch point
        moveCursorTo(x, y);

        // Double-tap detection (fixed action: double left click)
        if (gestureConfig.getDoubleTapEnabled()) {
            long now = System.currentTimeMillis();
            float dist = (float) Math.hypot(x - lastTapX, y - lastTapY);
            if ((now - lastTapTime) < gestureConfig.getDoubleTapDelay()
                    && dist < TouchGestureConfig.DOUBLE_TAP_DISTANCE_PX) {
                // Double-tap detected — send two rapid clicks so Windows/Wine
                // reliably recognises the double-click pattern.
                cancelLongPressTimer();
                doubleTapDetected = true;
                injectClick(TouchGestureConfig.ACTION_LEFT_CLICK);
                injectRelease(TouchGestureConfig.ACTION_LEFT_CLICK);
                injectClick(TouchGestureConfig.ACTION_LEFT_CLICK);
                injectRelease(TouchGestureConfig.ACTION_LEFT_CLICK);
                lastTapTime = 0; // reset so triple doesn't fire
                return;
            }
        }

        // Schedule long-press
        if (gestureConfig.getLongPressEnabled()) {
            longPressRunnable = () -> {
                longPressTriggered = true;
                injectClick(gestureConfig.getLongPressAction());
            };
            postDelayed(longPressRunnable, gestureConfig.getLongPressDelay());
        }

        // Tap: immediate cursor move (press is deferred to UP for click, or MOVE for drag)
    }

    // ── Second+ finger down ──────────────────────────────────────────
    private void handleTsPointerDown(MotionEvent event) {
        // Cancel any single-finger long-press
        cancelLongPressTimer();
        cancelDrag();

        if (event.getPointerCount() == 2) {
            twoFingerTapPossible = true;
            twoFingerDragging = false;
            twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
            accumulatedPinchDelta = 0;
            accumulatedPanDelta = 0;
            twoFingerLastX0 = event.getX(0);
            twoFingerLastY0 = event.getY(0);
            twoFingerLastX1 = event.getX(1);
            twoFingerLastY1 = event.getY(1);
            pinchLastDistance = twoFingerDistance(event);
        }
    }

    // ── Single-finger move ───────────────────────────────────────────
    private void handleTsMove(MotionEvent event) {
        float[] pt = XForm.transformPoint(xform, event.getX(), event.getY());
        int x = (int) pt[0];
        int y = (int) pt[1];

        // If long press already triggered, ignore movement (right-click already sent)
        if (longPressTriggered) return;

        // Measure raw finger travel from initial touch point
        float rawTravel = (float) Math.hypot(event.getX() - touchDownRawX, event.getY() - touchDownRawY);

        // Within long-press tolerance: keep cursor at touch-down point, keep timer alive
        if (rawTravel <= LONG_PRESS_MOVE_TOLERANCE) {
            return;
        }

        // Beyond tolerance — cancel long press
        cancelLongPressTimer();
        movedBeyondTapThreshold = true;

        // Detect drag start
        if (!isDragging && gestureConfig.getDragEnabled()) {
            isDragging = true;
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            dragButtonPressed = true;
            // Don't move cursor this frame — button was pressed at touch-down point.
            // Next frame will smoothly move from touch-down, so the drag starts
            // exactly where the finger first touched.
            return;
        }

        // Move cursor
        moveCursorTo(x, y);
    }

    // ── Two-finger move (pan / pinch) ────────────────────────────────
    private void handleTsTwoFingerMove(MotionEvent event) {
        if (event.getPointerCount() < 2) return;

        float x0 = event.getX(0), y0 = event.getY(0);
        float x1 = event.getX(1), y1 = event.getY(1);

        // Compute per-frame pan and pinch deltas
        float currDist = twoFingerDistance(event);
        float framePinchDelta = Math.abs(currDist - pinchLastDistance);

        float midX = (x0 + x1) / 2f;
        float midY = (y0 + y1) / 2f;
        float lastMidX = (twoFingerLastX0 + twoFingerLastX1) / 2f;
        float lastMidY = (twoFingerLastY0 + twoFingerLastY1) / 2f;
        float dx = midX - lastMidX;
        float dy = midY - lastMidY;
        float framePanDelta = (float) Math.hypot(dx, dy);

        // Accumulate deltas until we have enough movement to decide gesture type
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_NONE) {
            accumulatedPinchDelta += framePinchDelta;
            accumulatedPanDelta += framePanDelta;
            if (accumulatedPinchDelta + accumulatedPanDelta > 40f) {
                // Enough movement to lock into a gesture — no longer a tap
                twoFingerTapPossible = false;
                if (accumulatedPinchDelta > accumulatedPanDelta && gestureConfig.getPinchEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_ZOOM;
                } else if (gestureConfig.getTwoFingerDragEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_PAN;
                } else if (gestureConfig.getPinchEnabled()) {
                    twoFingerGestureMode = TWO_FINGER_GESTURE_ZOOM;
                }
            }
        }

        // ── Pinch-to-zoom (only in zoom mode) ───────────────────────
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_ZOOM) {
            float delta = currDist - pinchLastDistance;
            if (Math.abs(delta) > 30f) {
                performZoomAction(delta > 0);
                pinchLastDistance = currDist;
            }
        }

        // ── Two-finger drag / pan (only in pan mode) ────────────────
        if (twoFingerGestureMode == TWO_FINGER_GESTURE_PAN) {
            if (Math.abs(dx) > 3f || Math.abs(dy) > 3f) {
                twoFingerDragging = true;
                performPanAction(dx, dy);
            }
        }

        twoFingerLastX0 = x0;
        twoFingerLastY0 = y0;
        twoFingerLastX1 = x1;
        twoFingerLastY1 = y1;
    }

    // ── Second finger up ─────────────────────────────────────────────
    private void handleTsPointerUp(MotionEvent event) {
        // Two-finger tap detection
        if (twoFingerTapPossible && !twoFingerDragging && gestureConfig.getTwoFingerTapEnabled()) {
            // Move cursor to midpoint between the two fingers
            float midX = (twoFingerLastX0 + twoFingerLastX1) / 2f;
            float midY = (twoFingerLastY0 + twoFingerLastY1) / 2f;
            float[] pt = XForm.transformPoint(xform, midX, midY);
            moveCursorTo((int) pt[0], (int) pt[1]);
            injectClick(gestureConfig.getTwoFingerTapAction());
            injectRelease(gestureConfig.getTwoFingerTapAction());
        }
        releasePanKeys();
        releaseTwoFingerMiddleButton();
        twoFingerDragging = false;
        twoFingerTapPossible = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
    }

    // ── Primary finger up (tap / end drag) ───────────────────────────
    private void handleTsUp(MotionEvent event) {
        cancelLongPressTimer();

        if (longPressTriggered) {
            // Release the long-press action
            injectRelease(gestureConfig.getLongPressAction());
            longPressTriggered = false;
            return;
        }

        // Always record position/time for double-tap detection, even when
        // a micro-drag was triggered by finger jitter.  This ensures the
        // next tap's double-tap check has fresh data.
        float[] pt = XForm.transformPoint(xform, event.getX(), event.getY());

        if (isDragging && dragButtonPressed) {
            // End drag (release left button)
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            dragButtonPressed = false;
            isDragging = false;
            // Record for double-tap even though this was a drag
            lastTapTime = System.currentTimeMillis();
            lastTapX = pt[0];
            lastTapY = pt[1];
            return;
        }

        // Double-tap already sent its clicks on DOWN — skip the tap on UP
        if (doubleTapDetected) {
            doubleTapDetected = false;
            return;
        }

        // Simple tap — only if finger stayed within tap tolerance
        if (gestureConfig.getTapEnabled() && !movedBeyondTapThreshold) {
            moveCursorTo((int) pt[0], (int) pt[1]);
            injectClick(TouchGestureConfig.ACTION_LEFT_CLICK);
            injectRelease(TouchGestureConfig.ACTION_LEFT_CLICK);
        }

        // Record for double-tap detection (even if tap itself is disabled,
        // so double-tap can still prime from single touch-ups)
        if (!movedBeyondTapThreshold && (gestureConfig.getTapEnabled() || gestureConfig.getDoubleTapEnabled())) {
            lastTapTime = System.currentTimeMillis();
            lastTapX = pt[0];
            lastTapY = pt[1];
        }
        movedBeyondTapThreshold = false;
    }

    // ── Cancel ───────────────────────────────────────────────────────
    private void handleTsCancel() {
        cancelLongPressTimer();
        if (dragButtonPressed) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            dragButtonPressed = false;
        }
        releasePanKeys();
        releaseTwoFingerMiddleButton();
        isDragging = false;
        twoFingerDragging = false;
        twoFingerGestureMode = TWO_FINGER_GESTURE_NONE;
        longPressTriggered = false;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private void cancelLongPressTimer() {
        if (longPressRunnable != null) {
            removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void cancelDrag() {
        if (isDragging && dragButtonPressed) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
            dragButtonPressed = false;
        }
        isDragging = false;
    }

    private void moveCursorTo(int x, int y) {
        if (xServer.isRelativeMouseMovement()) {
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, x, y, 0);
        } else {
            xServer.injectPointerMove(x, y);
        }
    }

    private void injectClick(String action) {
        Pointer.Button btn = actionToButton(action);
        if (btn != null) {
            if (xServer.isRelativeMouseMovement()) {
                xServer.getWinHandler().mouseEvent(buttonToDownFlag(btn), 0, 0, 0);
            } else {
                xServer.injectPointerButtonPress(btn);
            }
        }
    }

    private void injectRelease(String action) {
        Pointer.Button btn = actionToButton(action);
        if (btn != null) {
            if (xServer.isRelativeMouseMovement()) {
                xServer.getWinHandler().mouseEvent(buttonToUpFlag(btn), 0, 0, 0);
            } else {
                xServer.injectPointerButtonRelease(btn);
            }
        }
    }

    private Pointer.Button actionToButton(String action) {
        if (action == null) return Pointer.Button.BUTTON_LEFT;
        switch (action) {
            case TouchGestureConfig.ACTION_RIGHT_CLICK:  return Pointer.Button.BUTTON_RIGHT;
            case TouchGestureConfig.ACTION_MIDDLE_CLICK: return Pointer.Button.BUTTON_MIDDLE;
            default:                                     return Pointer.Button.BUTTON_LEFT;
        }
    }

    private int buttonToDownFlag(Pointer.Button btn) {
        if (btn == Pointer.Button.BUTTON_RIGHT)  return MouseEventFlags.RIGHTDOWN;
        if (btn == Pointer.Button.BUTTON_MIDDLE) return MouseEventFlags.MIDDLEDOWN;
        return MouseEventFlags.LEFTDOWN;
    }

    private int buttonToUpFlag(Pointer.Button btn) {
        if (btn == Pointer.Button.BUTTON_RIGHT)  return MouseEventFlags.RIGHTUP;
        if (btn == Pointer.Button.BUTTON_MIDDLE) return MouseEventFlags.MIDDLEUP;
        return MouseEventFlags.LEFTUP;
    }

    private float twoFingerDistance(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    private void performZoomAction(boolean zoomIn) {
        String action = gestureConfig.getPinchAction();
        switch (action) {
            case TouchGestureConfig.ZOOM_SCROLL_WHEEL:
                Pointer.Button scrollBtn = zoomIn ? Pointer.Button.BUTTON_SCROLL_UP : Pointer.Button.BUTTON_SCROLL_DOWN;
                xServer.injectPointerButtonPress(scrollBtn);
                xServer.injectPointerButtonRelease(scrollBtn);
                break;
            case TouchGestureConfig.ZOOM_PLUS_MINUS:
                XKeycode key = zoomIn ? XKeycode.KEY_EQUAL : XKeycode.KEY_MINUS;
                xServer.injectKeyPress(key);
                xServer.injectKeyRelease(key);
                break;
            case TouchGestureConfig.ZOOM_PAGE_UP_DOWN:
                XKeycode pgKey = zoomIn ? XKeycode.KEY_PRIOR : XKeycode.KEY_NEXT;
                xServer.injectKeyPress(pgKey);
                xServer.injectKeyRelease(pgKey);
                break;
        }
    }

    private void performPanAction(float dx, float dy) {
        String action = gestureConfig.getTwoFingerDragAction();
        switch (action) {
            case TouchGestureConfig.PAN_MIDDLE_MOUSE:
                performMiddleMousePan(dx, dy);
                break;
            case TouchGestureConfig.PAN_WASD:
                performKeyPan(dx, dy, XKeycode.KEY_A, XKeycode.KEY_D, XKeycode.KEY_W, XKeycode.KEY_S);
                break;
            case TouchGestureConfig.PAN_ARROW_KEYS:
                performKeyPan(dx, dy, XKeycode.KEY_LEFT, XKeycode.KEY_RIGHT, XKeycode.KEY_UP, XKeycode.KEY_DOWN);
                break;
        }
    }

    private void performMiddleMousePan(float dx, float dy) {
        if (!twoFingerMiddleButtonDown) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
            twoFingerMiddleButtonDown = true;
        }
        // Convert raw screen pixel delta to X-server coordinates by transforming
        // two reference points and computing the difference.
        float[] ptOrigin = XForm.transformPoint(xform, 0, 0);
        float[] ptDelta  = XForm.transformPoint(xform, dx, dy);
        int mx = (int) (ptDelta[0] - ptOrigin[0]);
        int my = (int) (ptDelta[1] - ptOrigin[1]);
        int curX = xServer.pointer.getX();
        int curY = xServer.pointer.getY();
        if (xServer.isRelativeMouseMovement()) {
            xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, curX + mx, curY + my, 0);
        } else {
            xServer.injectPointerMove(curX + mx, curY + my);
        }
    }

    private void releaseTwoFingerMiddleButton() {
        if (twoFingerMiddleButtonDown) {
            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
            twoFingerMiddleButtonDown = false;
        }
    }

    private void performKeyPan(float dx, float dy, XKeycode leftKey, XKeycode rightKey, XKeycode upKey, XKeycode downKey) {
        // When fingers are actively moving, update key states to match the
        // swipe direction.  When fingers stop moving but stay on screen,
        // keep the current keys held — movement continues until finger-up
        // calls releasePanKeys().

        float totalDelta = Math.abs(dx) + Math.abs(dy);
        if (totalDelta < 5f) return; // Fingers stationary, keep current keys

        // --- Significant movement detected: update each axis ---

        // Horizontal: determine desired state from this frame's delta
        boolean wantLeft  = dx < -3f;
        boolean wantRight = dx > 3f;

        if (panKeyLeft && !wantLeft)   { xServer.injectKeyRelease(leftKey);  panKeyLeft = false; }
        if (panKeyRight && !wantRight) { xServer.injectKeyRelease(rightKey); panKeyRight = false; }
        if (wantLeft && !panKeyLeft)   { xServer.injectKeyPress(leftKey);    panKeyLeft = true; panLeftKey = leftKey; }
        if (wantRight && !panKeyRight) { xServer.injectKeyPress(rightKey);   panKeyRight = true; panRightKey = rightKey; }

        // Vertical: determine desired state from this frame's delta
        boolean wantUp   = dy < -3f;
        boolean wantDown = dy > 3f;

        if (panKeyUp && !wantUp)     { xServer.injectKeyRelease(upKey);   panKeyUp = false; }
        if (panKeyDown && !wantDown) { xServer.injectKeyRelease(downKey); panKeyDown = false; }
        if (wantUp && !panKeyUp)     { xServer.injectKeyPress(upKey);     panKeyUp = true; panUpKey = upKey; }
        if (wantDown && !panKeyDown) { xServer.injectKeyPress(downKey);   panKeyDown = true; panDownKey = downKey; }
    }

    private void releasePanKeys() {
        if (panKeyLeft  && panLeftKey  != null) { xServer.injectKeyRelease(panLeftKey);  panKeyLeft = false; }
        if (panKeyRight && panRightKey != null) { xServer.injectKeyRelease(panRightKey); panKeyRight = false; }
        if (panKeyUp    && panUpKey    != null) { xServer.injectKeyRelease(panUpKey);    panKeyUp = false; }
        if (panKeyDown  && panDownKey  != null) { xServer.injectKeyRelease(panDownKey);  panKeyDown = false; }
    }

    private void handleFingerUp(Finger finger1) {
        switch (this.numFingers) {
            case 1:
                if (finger1.isTap()) {
                    if (this.moveCursorToTouchpoint) {
                        this.xServer.injectPointerMove(finger1.x, finger1.y);
                }
                    pressPointerButtonLeft(finger1);
                    break;
                }
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) {
                    pressPointerButtonRight(finger1);
                    break;
                }
                break;
            case 4:
                if (this.fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i = (byte) (i + 1)) {
                        Finger[] fingerArr = this.fingers;
                        if (fingerArr[i] != null && !fingerArr[i].isTap()) {
                            return;
                    }
                }
                this.fourFingersTapCallback.run();
                break;
            }
            break;
        }
        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        byte b;
        if (isEnabled()) {
            boolean skipPointerMove = false;
            Finger finger2 = this.numFingers == 2 ? findSecondFinger(finger1) : null;
            if (finger2 != null) {
                ScreenInfo screenInfo = this.xServer.screenInfo;
                float resolutionScale = 1000.0f / Math.min((int) screenInfo.width, (int) screenInfo.height);
                float currDistance = ((float) Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y)) * resolutionScale;
                if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                    float f = this.scrollAccumY + (((finger1.y + finger2.y) * 0.5f) - ((finger1.lastY + finger2.lastY) * 0.5f));
                    this.scrollAccumY = f;
                    if (f < -100.0f) {
                        XServer xServer = this.xServer;
                        Pointer.Button button = Pointer.Button.BUTTON_SCROLL_DOWN;
                        xServer.injectPointerButtonPress(button);
                        this.xServer.injectPointerButtonRelease(button);
                        this.scrollAccumY = 0.0f;
                    } else if (f > 100.0f) {
                        XServer xServer2 = this.xServer;
                        Pointer.Button button2 = Pointer.Button.BUTTON_SCROLL_UP;
                        xServer2.injectPointerButtonPress(button2);
                        this.xServer.injectPointerButtonRelease(button2);
                        this.scrollAccumY = 0.0f;
                    }
                    scrolling = true;
                } else if (currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE && !this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) && finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                    pressPointerButtonLeft(finger1);
                    skipPointerMove = true;
                }
            }
            if (!this.scrolling && (b = this.numFingers) <= 2 && !skipPointerMove) {
                if (!this.moveCursorToTouchpoint || b != 1) {
                int dx = finger1.deltaX();
                int dy = finger1.deltaY();
                    WinHandler winHandler = this.xServer.getWinHandler();
                    if (this.xServer.isRelativeMouseMovement()) {
                        winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
                        return;
                    } else {
                        this.xServer.injectPointerMoveDelta(dx, dy);
                        return;
                    }
                }
                this.xServer.injectPointerMove(finger1.x, finger1.y);
            }
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            Finger[] fingerArr = this.fingers;
            if (fingerArr[i] != null && fingerArr[i] != finger) {
                return fingerArr[i];
            }
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (isEnabled() && this.pointerButtonLeftEnabled) {
            // Relative mouse movement support
            if (this.xServer.isRelativeMouseMovement()) {
                this.xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                this.fingerPointerButtonLeft = finger;
                return;
            }
            Pointer pointer = this.xServer.pointer;
            Pointer.Button button = Pointer.Button.BUTTON_LEFT;
            if (!pointer.isButtonPressed(button)) {
                this.xServer.injectPointerButtonPress(button);
                this.fingerPointerButtonLeft = finger;
            }
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (isEnabled() && this.pointerButtonRightEnabled) {
            // Relative mouse movement support
            if (this.xServer.isRelativeMouseMovement()) {
                this.xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                this.fingerPointerButtonRight = finger;
                return;
            }
            Pointer pointer = this.xServer.pointer;
            Pointer.Button button = Pointer.Button.BUTTON_RIGHT;
            if (!pointer.isButtonPressed(button)) {
                this.xServer.injectPointerButtonPress(button);
                this.fingerPointerButtonRight = finger;
            }
        }
    }

    private void releasePointerButtonLeft(Finger finger) {
        // Relative mouse movement support
        if (isEnabled() && this.pointerButtonLeftEnabled && finger == this.fingerPointerButtonLeft && this.xServer.isRelativeMouseMovement()) {
            postDelayed(() -> {
                xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                fingerPointerButtonLeft = null;
            }, 30);
            return;
        }
        if (isEnabled() && this.pointerButtonLeftEnabled && finger == this.fingerPointerButtonLeft && this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(Finger finger) {
        // Relative mouse movement support
        if (isEnabled() && this.pointerButtonRightEnabled && finger == this.fingerPointerButtonRight && this.xServer.isRelativeMouseMovement()) {
            postDelayed(() -> {
                xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                fingerPointerButtonRight = null;
            }, 30);
            return;
        }
        if (isEnabled() && this.pointerButtonRightEnabled && finger == this.fingerPointerButtonRight && this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = null;
            }, 30);
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public void setMoveCursorToTouchpoint(boolean moveCursorToTouchpoint) {
        this.moveCursorToTouchpoint = moveCursorToTouchpoint;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        // one-shot: capture external mouse on first event, don't re-capture after user release
        if (capturePointerOnExternalMouse && !pointerCaptureRequested) {
            pointerCaptureRequested = true;
            if (!hasFocus() && !requestFocus()) {
                Log.w("TouchpadView", "requestFocus() failed, skipping pointer capture");
            } else {
                requestPointerCapture();
            }
        }
        boolean handled = false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEDOWN, 0, 0, 0);
                        else
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_MIDDLE);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.LEFTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    } else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.RIGHTUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    } else if (actionButton == MotionEvent.BUTTON_TERTIARY) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.MIDDLEUP, 0, 0, 0);
                        else
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_MIDDLE);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_HOVER_MOVE:
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    if (xServer.isRelativeMouseMovement())
                        xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, (int)transformedPoint[0], (int)transformedPoint[1], 0);
                    else
                        xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0, (int)scrollY);
                        else {
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                        }
                    } else if (scrollY >= 1.0f) {
                        if (xServer.isRelativeMouseMovement())
                            xServer.getWinHandler().mouseEvent(MouseEventFlags.WHEEL, 0, 0,(int)scrollY);
                        else {
                            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                            xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                        }
                    }
                    handled = true;
                    break;
            }
        }
        return handled;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        float[] result = {0, 0};
        XForm.transformPoint(this.xform, lastX, lastY, result);
        float lastX2 = result[0];
        float lastY2 = result[1];
        XForm.transformPoint(this.xform, x, y, result);
        float x2 = result[0];
        float y2 = result[1];
        result[0] = x2 - lastX2;
        result[1] = y2 - lastY2;
        return result;
    }

    @Override // android.view.View.OnCapturedPointerListener
    public boolean onCapturedPointer(View view, MotionEvent event) {
        if (event.getAction() == 2) {
            float dx = event.getX() * this.sensitivity;
            if (Math.abs(dx) > 6.0f) {
                dx *= CURSOR_ACCELERATION;
            }
            float dy = event.getY() * this.sensitivity;
            if (Math.abs(dy) > 6.0f) {
                dy *= CURSOR_ACCELERATION;
            }
            this.xServer.injectPointerMoveDelta(Mathf.roundPoint(dx), Mathf.roundPoint(dy));
            return true;
        }
        event.setSource(event.getSource() | 8194);
        return onExternalMouseEvent(event);
    }

    public void setSimTouchScreen(boolean simTouchScreen) {
        this.simTouchScreen = simTouchScreen;
        xServer.setSimulateTouchScreen(this.simTouchScreen);
    }

    public boolean isSimTouchScreen() {
        return simTouchScreen;
    }

    public void setTouchscreenMode(boolean isTouchscreenMode) {
        Log.d("TouchpadView", "Setting touchscreen mode to " + isTouchscreenMode);
        this.isTouchscreenMode = isTouchscreenMode;
    }

    public boolean isTouchscreenMode() {
        return this.isTouchscreenMode;
    }

    public void setGestureConfig(TouchGestureConfig config) {
        this.gestureConfig = config != null ? config : new TouchGestureConfig();
    }

    public TouchGestureConfig getGestureConfig() {
        return this.gestureConfig;
    }

    public void setTouchscreenMouseDisabled(boolean disabled) {
        this.touchscreenMouseDisabled = disabled;
    }
}
