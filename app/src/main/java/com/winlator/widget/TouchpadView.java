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
import com.winlator.core.AppUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.ViewTransformation;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.ScreenInfo;
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
            Pointer pointer = this.xServer.pointer;
            Pointer.Button button = Pointer.Button.BUTTON_RIGHT;
            if (!pointer.isButtonPressed(button)) {
                this.xServer.injectPointerButtonPress(button);
                this.fingerPointerButtonRight = finger;
            }
        }
    }

    private void releasePointerButtonLeft(Finger finger) {
        if (isEnabled() && this.pointerButtonLeftEnabled && finger == this.fingerPointerButtonLeft && this.xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(Finger finger) {
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

    public void setTouchscreenMouseDisabled(boolean disabled) {
        this.touchscreenMouseDisabled = disabled;
    }
}
