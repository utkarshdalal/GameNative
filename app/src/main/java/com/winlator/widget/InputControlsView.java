package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.util.Log;

import androidx.core.graphics.ColorUtils;

import app.gamenative.R;
import com.winlator.inputcontrols.Binding;
import com.winlator.inputcontrols.ControlElement;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.ExternalControllerBinding;
import com.winlator.inputcontrols.GamepadState;
import com.winlator.math.Mathf;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;

public class InputControlsView extends View {
    public static final float DEFAULT_OVERLAY_OPACITY = 0.4f;
    private boolean editMode = false;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final ColorFilter colorFilter = new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN);
    private final Point cursor = new Point();
    private boolean readyToDraw = false;
    private boolean moveCursor = false;
    private int snappingSize;
    private float offsetX;
    private float offsetY;
    private ControlElement selectedElement;
    private ControlsProfile profile;
    private float overlayOpacity = DEFAULT_OVERLAY_OPACITY;
    private TouchpadView touchpadView;
    private XServer xServer;
    private final Bitmap[] icons = new Bitmap[40];
    private Timer mouseMoveTimer;
    private final PointF mouseMoveOffset = new PointF();
    private boolean showTouchscreenControls = true;

    // Shooter mode state
    private boolean shooterModeActive = false;
    // Dynamic joystick (left side in shooter mode)
    private int joystickPointerId = -1;
    private float joystickCenterX, joystickCenterY;
    private float joystickCurrentX, joystickCurrentY;
    private final boolean[] joystickStates = new boolean[4];
    // Look-around (right side or fire button look-through)
    private int lookPointerId = -1;
    private float lookLastX, lookLastY;
    private float lookAccumX, lookAccumY;
    private ControlElement lookFireElement = null;
    // Right dynamic joystick (for gamepad_right_stick look type)
    private int rightJoystickPointerId = -1;
    private float rightJoystickCenterX, rightJoystickCenterY;
    private float rightJoystickCurrentX, rightJoystickCurrentY;
    private final boolean[] rightJoystickStates = new boolean[4];

    // Container-level shooter mode (auto-replaces STICK elements)
    private boolean containerShooterMode = false;
    private boolean containerShooterModeRuntime = false; // runtime toggle state

    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0x00000000);
        setPointerIcon(PointerIcon.load(getResources(), R.drawable.hidden_pointer_arrow));
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public boolean isEditMode() {
        return editMode;
    }

    public void setEditMode(boolean editMode) {
        this.editMode = editMode;
        invalidate(); // Trigger redraw to show/hide grid background immediately
    }

    public void setOverlayOpacity(float overlayOpacity) {
        this.overlayOpacity = overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            readyToDraw = false;
            return;
        }

        snappingSize = width / 100;
        readyToDraw = true;

        if (editMode) {
            drawGrid(canvas);
            drawCursor(canvas);
        }

        if (profile != null) {
            if (!profile.isElementsLoaded()) profile.loadElements(this);
            if (showTouchscreenControls) {
                for (ControlElement element : profile.getElements()) {
                    // Hide STICK elements replaced by container shooter mode
                    if (isStickHiddenByShooterMode(element)) continue;
                    element.draw(canvas);
                }
            }
        }

        // Draw dynamic joysticks when shooter mode is active
        boolean anyShooterActive = shooterModeActive || containerShooterModeRuntime;
        if (anyShooterActive && joystickPointerId != -1) {
            drawShooterJoystick(canvas);
        }
        if (anyShooterActive && rightJoystickPointerId != -1) {
            float sizeMultiplier = 1.0f;
            ControlElement smElement = getShooterModeElement();
            if (smElement != null) sizeMultiplier = smElement.getShooterJoystickSize();
            drawDynamicJoystick(canvas, rightJoystickCenterX, rightJoystickCenterY,
                                rightJoystickCurrentX, rightJoystickCurrentY, sizeMultiplier);
        }

        // Draw container shooter mode toggle button
        if (containerShooterMode && !editMode) {
            drawContainerShooterToggle(canvas);
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xff000000);
        canvas.drawColor(Color.BLACK);

        paint.setAntiAlias(false);
        paint.setColor(0xff303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0xff424242);

        for (int i = 0; i < width; i += snappingSize * 2) {
            canvas.drawLine(cx, i, cx, i + snappingSize, paint);
            canvas.drawLine(i, cy, i + snappingSize, cy, paint);
        }

        paint.setAntiAlias(true);
    }

    private void drawCursor(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(snappingSize * 0.0625f);
        paint.setColor(0xffc62828);

        paint.setAntiAlias(false);
        canvas.drawLine(0, cursor.y, getMaxWidth(), cursor.y, paint);
        canvas.drawLine(cursor.x, 0, cursor.x, getMaxHeight(), paint);

        paint.setAntiAlias(true);
    }

    public synchronized boolean addElement() {
        if (editMode && profile != null) {
            ControlElement element = new ControlElement(this);
            // Calculate center position, snapped to grid
            int centerX = (int)Mathf.roundTo(getMaxWidth() * 0.5f, snappingSize);
            int centerY = (int)Mathf.roundTo(getMaxHeight() * 0.5f, snappingSize);
            element.setX(centerX);
            element.setY(centerY);
            profile.addElement(element);
            profile.save();
            selectElement(element);
            return true;
        }
        else return false;
    }

    public synchronized boolean removeElement() {
        if (editMode && selectedElement != null && profile != null) {
            profile.removeElement(selectedElement);
            selectedElement = null;
            profile.save();
            invalidate();
            return true;
        }
        else return false;
    }

    public ControlElement getSelectedElement() {
        return selectedElement;
    }

    private synchronized void deselectAllElements() {
        selectedElement = null;
        if (profile != null) {
            for (ControlElement element : profile.getElements()) element.setSelected(false);
        }
    }

    private void selectElement(ControlElement element) {
        deselectAllElements();
        if (element != null) {
            selectedElement = element;
            selectedElement.setSelected(true);
        }
        invalidate();
    }

    public synchronized ControlsProfile getProfile() {
        return profile;
    }

    public synchronized void setProfile(ControlsProfile profile) {
        if (profile != null) {
            this.profile = profile;
            deselectAllElements();
        }
        else this.profile = null;
    }

    public boolean isShowTouchscreenControls() {
        return showTouchscreenControls;
    }

    public void setShowTouchscreenControls(boolean showTouchscreenControls) {
        this.showTouchscreenControls = showTouchscreenControls;
    }

    public int getPrimaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 255, 255, 255);
    }

    public int getSecondaryColor() {
        return Color.argb((int)(overlayOpacity * 255), 2, 119, 189);
    }

    private synchronized ControlElement intersectElement(float x, float y) {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.containsPoint(x, y)) return element;
            }
        }
        return null;
    }

    public Paint getPaint() {
        return paint;
    }

    public Path getPath() {
        return path;
    }

    public ColorFilter getColorFilter() {
        return colorFilter;
    }

    public TouchpadView getTouchpadView() {
        return touchpadView;
    }

    public void setTouchpadView(TouchpadView touchpadView) {
        this.touchpadView = touchpadView;
    }

    public XServer getXServer() {
        return xServer;
    }

    public void setXServer(XServer xServer) {
        this.xServer = xServer;
        createMouseMoveTimer();
    }

    public int getMaxWidth() {
        return (int)Mathf.roundTo(getWidth(), snappingSize);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mouseMoveTimer != null)
            mouseMoveTimer.cancel();
        super.onDetachedFromWindow();
    }

    public int getMaxHeight() {
        return (int)Mathf.roundTo(getHeight(), snappingSize);
    }

    private void createMouseMoveTimer() {
        if (profile != null && mouseMoveTimer == null) {
            final float cursorSpeed = profile.getCursorSpeed();
            mouseMoveTimer = new Timer();
            mouseMoveTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    xServer.injectPointerMoveDelta((int)(mouseMoveOffset.x * 10 * cursorSpeed), (int)(mouseMoveOffset.y * 10 * cursorSpeed));
                }
            }, 0, 1000 / 60);
        }
    }

    private void processJoystickInput(ExternalController controller) {
        ExternalControllerBinding controllerBinding;
        final int[] axes = {MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y};
        final float[] values = {controller.state.thumbLX, controller.state.thumbLY, controller.state.thumbRX, controller.state.thumbRY, controller.state.getDPadX(), controller.state.getDPadY()};

        for (byte i = 0; i < axes.length; i++) {
            if (Math.abs(values[i]) > ControlElement.STICK_DEAD_ZONE) {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], Mathf.sign(values[i])));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), true, values[i]);
            }
            else {
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte) 1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), false, values[i]);
                controllerBinding = controller.getControllerBinding(ExternalControllerBinding.getKeyCodeForAxis(axes[i], (byte)-1));
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), false, values[i]);
            }
        }
    }

    // ========== Shooter Mode Methods ==========

    public boolean isShooterModeActive() {
        return shooterModeActive;
    }

    public void setShooterModeActive(boolean active) {
        this.shooterModeActive = active;
        if (!active) {
            releaseShooterJoystick();
            releaseShooterLook();
            releaseRightJoystick();
        }
        invalidate();
    }

    public void setContainerShooterMode(boolean enabled) {
        this.containerShooterMode = enabled;
        this.containerShooterModeRuntime = enabled;
        invalidate();
    }

    /** Check if a STICK element should be hidden because container shooter mode replaces it. */
    private boolean isStickHiddenByShooterMode(ControlElement element) {
        if (!containerShooterModeRuntime) return false;
        if (element.getType() != ControlElement.Type.STICK) return false;
        Binding b0 = element.getBindingAt(0);
        return b0 == Binding.GAMEPAD_LEFT_THUMB_UP || b0 == Binding.GAMEPAD_RIGHT_THUMB_UP;
    }

    private ControlElement getShooterModeElement() {
        if (profile != null) {
            for (ControlElement element : profile.getElements()) {
                if (element.getType() == ControlElement.Type.SHOOTER_MODE) return element;
            }
        }
        return null;
    }

    private boolean isFireButton(ControlElement element) {
        if (element.getType() != ControlElement.Type.BUTTON) return false;
        Binding binding = element.getBindingAt(0);
        return binding == Binding.MOUSE_LEFT_BUTTON || binding == Binding.MOUSE_RIGHT_BUTTON;
    }

    private Binding[] getJoystickBindings(String movementType) {
        switch (movementType) {
            case "arrow_keys":
                return new Binding[]{Binding.KEY_UP, Binding.KEY_RIGHT, Binding.KEY_DOWN, Binding.KEY_LEFT};
            case "gamepad_left_stick":
                return new Binding[]{Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_RIGHT,
                                     Binding.GAMEPAD_LEFT_THUMB_DOWN, Binding.GAMEPAD_LEFT_THUMB_LEFT};
            case "wasd":
            default:
                return new Binding[]{Binding.KEY_W, Binding.KEY_D, Binding.KEY_S, Binding.KEY_A};
        }
    }

    private void releaseShooterJoystick() {
        if (joystickPointerId != -1) {
            ControlElement smElement = getShooterModeElement();
            Binding[] bindings;
            if (smElement != null) {
                bindings = getJoystickBindings(smElement.getShooterMovementType());
            } else {
                // Container shooter mode: default to gamepad left stick
                bindings = getJoystickBindings("gamepad_left_stick");
            }
            for (int i = 0; i < 4; i++) {
                if (joystickStates[i]) {
                    handleInputEvent(bindings[i], false);
                }
            }
            joystickPointerId = -1;
            Arrays.fill(joystickStates, false);
            invalidate();
        }
    }

    private void releaseShooterLook() {
        if (lookPointerId != -1) {
            if (lookFireElement != null) {
                lookFireElement.handleTouchUp(lookPointerId);
                lookFireElement = null;
            }
            lookPointerId = -1;
            lookAccumX = 0;
            lookAccumY = 0;
        }
    }

    private void releaseRightJoystick() {
        if (rightJoystickPointerId != -1) {
            Binding[] bindings = getRightJoystickBindings();
            for (int i = 0; i < 4; i++) {
                if (rightJoystickStates[i]) {
                    handleInputEvent(bindings[i], false);
                }
            }
            rightJoystickPointerId = -1;
            Arrays.fill(rightJoystickStates, false);
            invalidate();
        }
    }

    private Binding[] getRightJoystickBindings() {
        return new Binding[]{
            Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
            Binding.GAMEPAD_RIGHT_THUMB_DOWN, Binding.GAMEPAD_RIGHT_THUMB_LEFT
        };
    }

    private void handleRightJoystickMove(float x, float y) {
        float sizeMultiplier = 1.0f;
        ControlElement smElement = getShooterModeElement();
        if (smElement != null) sizeMultiplier = smElement.getShooterJoystickSize();
        float radius = snappingSize * 6 * sizeMultiplier;
        if (radius <= 0) return;

        float localX = x - rightJoystickCenterX;
        float localY = y - rightJoystickCenterY;

        float distance = (float)Math.sqrt(localX * localX + localY * localY);
        if (distance > radius) {
            float angle = (float)Math.atan2(localY, localX);
            localX = (float)(Math.cos(angle) * radius);
            localY = (float)(Math.sin(angle) * radius);
        }

        rightJoystickCurrentX = rightJoystickCenterX + localX;
        rightJoystickCurrentY = rightJoystickCenterY + localY;

        float deltaX = Mathf.clamp(localX / radius, -1, 1);
        float deltaY = Mathf.clamp(localY / radius, -1, 1);

        Binding[] bindings = getRightJoystickBindings();

        boolean[] newStates = {
            deltaY <= -ControlElement.STICK_DEAD_ZONE,
            deltaX >= ControlElement.STICK_DEAD_ZONE,
            deltaY >= ControlElement.STICK_DEAD_ZONE,
            deltaX <= -ControlElement.STICK_DEAD_ZONE
        };

        for (int i = 0; i < 4; i++) {
            float value = (i == 1 || i == 3) ? deltaX : deltaY;
            value = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * ControlElement.STICK_SENSITIVITY, -1, 1);
            handleInputEvent(bindings[i], true, value);
            rightJoystickStates[i] = true;
        }
        invalidate();
    }

    private void drawDynamicJoystick(Canvas canvas, float centerX, float centerY, float currentX, float currentY, float sizeMultiplier) {
        float radius = snappingSize * 6 * sizeMultiplier;
        float strokeWidth = snappingSize * 0.25f;
        int primaryColor = getPrimaryColor();

        paint.setColor(primaryColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        canvas.drawCircle(centerX, centerY, radius, paint);

        float thumbRadius = snappingSize * 3.5f * sizeMultiplier;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, 50));
        canvas.drawCircle(currentX, currentY, thumbRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(primaryColor);
        canvas.drawCircle(currentX, currentY, thumbRadius + strokeWidth * 0.5f, paint);
    }

    /** Get the bounding rect for the container shooter mode toggle button at top center. */
    private android.graphics.RectF getToggleButtonRect() {
        float btnW = snappingSize * 12;
        float btnH = snappingSize * 4;
        float cx = getWidth() / 2f;
        return new android.graphics.RectF(cx - btnW / 2, snappingSize * 0.5f, cx + btnW / 2, snappingSize * 0.5f + btnH);
    }

    private void drawContainerShooterToggle(Canvas canvas) {
        android.graphics.RectF rect = getToggleButtonRect();
        float radius = snappingSize * 0.5f;
        int primaryColor = getPrimaryColor();

        // Background
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(containerShooterModeRuntime ? 0xFF0277BD : 0xFF616161,
                        (int)(overlayOpacity * 200)));
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Border
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(snappingSize * 0.125f);
        paint.setColor(primaryColor);
        canvas.drawRoundRect(rect, radius, radius, paint);

        // Text
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(primaryColor);
        paint.setTextSize(snappingSize * 1.8f);
        paint.setTextAlign(Paint.Align.CENTER);
        float textY = rect.centerY() - (paint.descent() + paint.ascent()) * 0.5f;
        String label = getContext().getString(containerShooterModeRuntime
                ? app.gamenative.R.string.shooter_mode_on
                : app.gamenative.R.string.shooter_mode_off);
        canvas.drawText(label, rect.centerX(), textY, paint);
    }

    private void handleShooterJoystickMove(float x, float y) {
        float joystickSizeMultiplier = 1.0f;
        String movementType = "gamepad_left_stick";
        ControlElement smElement = getShooterModeElement();
        if (smElement != null) {
            joystickSizeMultiplier = smElement.getShooterJoystickSize();
            movementType = smElement.getShooterMovementType();
        }
        float radius = snappingSize * 6 * joystickSizeMultiplier;
        if (radius <= 0) return;

        float localX = x - joystickCenterX;
        float localY = y - joystickCenterY;

        float distance = (float)Math.sqrt(localX * localX + localY * localY);
        if (distance > radius) {
            float angle = (float)Math.atan2(localY, localX);
            localX = (float)(Math.cos(angle) * radius);
            localY = (float)(Math.sin(angle) * radius);
        }

        joystickCurrentX = joystickCenterX + localX;
        joystickCurrentY = joystickCenterY + localY;

        float deltaX = Mathf.clamp(localX / radius, -1, 1);
        float deltaY = Mathf.clamp(localY / radius, -1, 1);

        Binding[] bindings = getJoystickBindings(movementType);

        boolean[] newStates = {
            deltaY <= -ControlElement.STICK_DEAD_ZONE,   // up
            deltaX >= ControlElement.STICK_DEAD_ZONE,     // right
            deltaY >= ControlElement.STICK_DEAD_ZONE,     // down
            deltaX <= -ControlElement.STICK_DEAD_ZONE     // left
        };

        for (int i = 0; i < 4; i++) {
            float value = (i == 1 || i == 3) ? deltaX : deltaY;
            if (bindings[i].isGamepad()) {
                value = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * ControlElement.STICK_SENSITIVITY, -1, 1);
                handleInputEvent(bindings[i], true, value);
                joystickStates[i] = true;
            } else {
                if (newStates[i] != joystickStates[i]) {
                    handleInputEvent(bindings[i], newStates[i]);
                    joystickStates[i] = newStates[i];
                }
            }
        }

        invalidate();
    }

    private void drawShooterJoystick(Canvas canvas) {
        float joystickSizeMultiplier = 1.0f;
        ControlElement smElement = getShooterModeElement();
        if (smElement != null) joystickSizeMultiplier = smElement.getShooterJoystickSize();
        drawDynamicJoystick(canvas, joystickCenterX, joystickCenterY, joystickCurrentX, joystickCurrentY, joystickSizeMultiplier);
    }

    private boolean handleShooterTouchDown(int pointerId, float x, float y) {
        boolean handled = false;

        // Check container shooter mode toggle button first
        if (containerShooterMode) {
            android.graphics.RectF toggleRect = getToggleButtonRect();
            if (toggleRect.contains(x, y)) {
                containerShooterModeRuntime = !containerShooterModeRuntime;
                if (!containerShooterModeRuntime) {
                    releaseShooterJoystick();
                    releaseRightJoystick();
                }
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                invalidate();
                return true;
            }
        }

        // If runtime is off (and no SHOOTER_MODE element active), only the toggle button above should handle
        if (!shooterModeActive && !containerShooterModeRuntime) {
            return false;
        }

        for (ControlElement element : profile.getElements()) {
            // Skip hidden sticks in container shooter mode
            if (isStickHiddenByShooterMode(element)) continue;
            if (element.handleTouchDown(pointerId, x, y)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                handled = true;
                // Also track this pointer for look-around so the user can
                // press any button and still look/aim with the same finger.
                ControlElement smElement = getShooterModeElement();
                boolean useRightStick = (smElement != null && "gamepad_right_stick".equals(smElement.getShooterLookType()))
                                     || (containerShooterModeRuntime && smElement == null);
                if (useRightStick) {
                    if (rightJoystickPointerId == -1) {
                        rightJoystickPointerId = pointerId;
                        rightJoystickCenterX = x;
                        rightJoystickCenterY = y;
                        rightJoystickCurrentX = x;
                        rightJoystickCurrentY = y;
                    }
                } else {
                    if (lookPointerId == -1) {
                        lookPointerId = pointerId;
                        lookLastX = x;
                        lookLastY = y;
                        lookAccumX = 0;
                        lookAccumY = 0;
                        lookFireElement = element;
                    }
                }
                break;
            }
        }
        if (!handled) {
            int screenMidX = getWidth() / 2;
            if (x < screenMidX && joystickPointerId == -1) {
                // Left side: spawn dynamic joystick
                joystickPointerId = pointerId;
                joystickCenterX = x;
                joystickCenterY = y;
                joystickCurrentX = x;
                joystickCurrentY = y;
                handled = true;
                invalidate();
            } else if (x >= screenMidX) {
                // Right side: check look type
                ControlElement smElement = getShooterModeElement();
                boolean useRightStick = (smElement != null && "gamepad_right_stick".equals(smElement.getShooterLookType()))
                                     || (containerShooterModeRuntime && smElement == null);
                if (useRightStick && rightJoystickPointerId == -1) {
                    // Right side: spawn dynamic right joystick
                    rightJoystickPointerId = pointerId;
                    rightJoystickCenterX = x;
                    rightJoystickCenterY = y;
                    rightJoystickCurrentX = x;
                    rightJoystickCurrentY = y;
                    handled = true;
                    invalidate();
                } else if (!useRightStick && lookPointerId == -1) {
                    // Right side: mouse look
                    lookPointerId = pointerId;
                    lookLastX = x;
                    lookLastY = y;
                    lookAccumX = 0;
                    lookAccumY = 0;
                    lookFireElement = null;
                    handled = true;
                }
            }
        }
        return handled;
    }

    private boolean handleShooterTouchMovePointer(int pid, float x, float y) {
        if (pid == joystickPointerId) {
            handleShooterJoystickMove(x, y);
            return true;
        }
        if (pid == rightJoystickPointerId) {
            handleRightJoystickMove(x, y);
            return true;
        }
        if (pid == lookPointerId) {
            float dx = x - lookLastX;
            float dy = y - lookLastY;
            lookLastX = x;
            lookLastY = y;
            ControlElement smElement = getShooterModeElement();
            float sensitivity = smElement != null ? smElement.getShooterLookSensitivity() : 1.0f;
            float scaledDx = dx * sensitivity;
            float scaledDy = dy * sensitivity;
            int moveX = Mathf.roundPoint(scaledDx);
            int moveY = Mathf.roundPoint(scaledDy);
            if (moveX != 0 || moveY != 0) {
                if (xServer.isRelativeMouseMovement()) {
                    xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, moveX, moveY, 0);
                } else {
                    xServer.injectPointerMoveDelta(moveX, moveY);
                }
            }
            return true;
        }
        return false;
    }

    // ========== End Shooter Mode Methods ==========

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!editMode && profile != null) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null && controller.updateStateFromMotionEvent(event)) {
                ExternalControllerBinding controllerBinding;
                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_L2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_L2));

                controllerBinding = controller.getControllerBinding(KeyEvent.KEYCODE_BUTTON_R2);
                if (controllerBinding != null) handleInputEvent(controllerBinding.getBinding(), controller.state.isPressed(ExternalController.IDX_BUTTON_R2));

                processJoystickInput(controller);
                return true;
            }
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode && readyToDraw) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = event.getX();
                    float y = event.getY();

                    ControlElement element = intersectElement(x, y);
                    moveCursor = true;
                    if (element != null) {
                        offsetX = x - element.getX();
                        offsetY = y - element.getY();
                        moveCursor = false;
                    }

                    selectElement(element);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selectedElement != null) {
                        selectedElement.setX((int)Mathf.roundTo(event.getX() - offsetX, snappingSize));
                        selectedElement.setY((int)Mathf.roundTo(event.getY() - offsetY, snappingSize));
                        invalidate();
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (selectedElement != null && profile != null) profile.save();
                    if (moveCursor) cursor.set((int)Mathf.roundTo(event.getX(), snappingSize), (int)Mathf.roundTo(event.getY(), snappingSize));
                    invalidate();
                    break;
                }
            }
        }

        if (!editMode && profile != null) {
            int actionIndex = event.getActionIndex();
            int pointerId = event.getPointerId(actionIndex);
            int actionMasked = event.getActionMasked();
            boolean handled = false;

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN: {
                    float x = event.getX(actionIndex);
                    float y = event.getY(actionIndex);

                    // Shooter mode intercept (use containerShooterMode so toggle button is always reachable)
                    if ((shooterModeActive || containerShooterMode) && handleShooterTouchDown(pointerId, x, y)) {
                        break;
                    }

                    touchpadView.setPointerButtonLeftEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            handled = true;
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                    }
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);

                        // Shooter mode intercept per pointer
                        if (shooterModeActive || containerShooterModeRuntime) {
                            int pid = event.getPointerId(i);
                            if (handleShooterTouchMovePointer(pid, x, y)) continue;
                            // Non-intercepted pointer in shooter mode: try elements with correct ID
                            handled = false;
                            for (ControlElement element : profile.getElements()) {
                                if (element.handleTouchMove(pid, x, y)) handled = true;
                            }
                            continue;
                        }

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(i, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Shooter mode intercept
                    if (shooterModeActive || containerShooterModeRuntime) {
                        if (pointerId == joystickPointerId) {
                            releaseShooterJoystick();
                            handled = true;
                        }
                        if (pointerId == rightJoystickPointerId) {
                            releaseRightJoystick();
                            handled = true;
                        }
                        if (pointerId == lookPointerId) {
                            lookPointerId = -1;
                            lookFireElement = null;
                            handled = true;
                        }
                    }
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
            }
        }
        return true;
    }

    public boolean onKeyEvent(KeyEvent event) {
        if (profile != null && event.getRepeatCount() == 0) {
            ExternalController controller = profile.getController(event.getDeviceId());
            if (controller != null) {
                ExternalControllerBinding controllerBinding = controller.getControllerBinding(event.getKeyCode());
                if (controllerBinding != null) {
                    int action = event.getAction();
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleInputEvent(controllerBinding.getBinding(), true);
                    }
                    else if (action == KeyEvent.ACTION_UP) {
                        handleInputEvent(controllerBinding.getBinding(), false);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public void handleInputEvent(Binding binding, boolean isActionDown) {
        handleInputEvent(binding, isActionDown, 0);
    }

    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {
        if (binding.isGamepad()) {
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            GamepadState state = profile.getGamepadState();

            int buttonIdx = binding.ordinal() - Binding.GAMEPAD_BUTTON_A.ordinal();
            if (buttonIdx <= ExternalController.IDX_BUTTON_R2) {
                if (buttonIdx == ExternalController.IDX_BUTTON_L2) {
                    state.triggerL = isActionDown ? 1.0f : 0f;
                    state.setPressed(ExternalController.IDX_BUTTON_L2, isActionDown);
                } else if (buttonIdx == ExternalController.IDX_BUTTON_R2) {
                    state.triggerR = isActionDown ? 1.0f : 0f;
                    state.setPressed(ExternalController.IDX_BUTTON_R2, isActionDown);
                } else
                    state.setPressed(buttonIdx, isActionDown);
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_UP || binding == Binding.GAMEPAD_LEFT_THUMB_DOWN) {
                state.thumbLY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_LEFT_THUMB_LEFT || binding == Binding.GAMEPAD_LEFT_THUMB_RIGHT) {
                state.thumbLX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_UP || binding == Binding.GAMEPAD_RIGHT_THUMB_DOWN) {
                state.thumbRY = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_RIGHT_THUMB_LEFT || binding == Binding.GAMEPAD_RIGHT_THUMB_RIGHT) {
                state.thumbRX = isActionDown ? offset : 0;
            }
            else if (binding == Binding.GAMEPAD_DPAD_UP || binding == Binding.GAMEPAD_DPAD_RIGHT ||
                     binding == Binding.GAMEPAD_DPAD_DOWN || binding == Binding.GAMEPAD_DPAD_LEFT) {
                state.dpad[binding.ordinal() - Binding.GAMEPAD_DPAD_UP.ordinal()] = isActionDown;
            }

            if (winHandler != null) {
                ExternalController controller = winHandler.getCurrentController();
                if (controller != null) controller.state.copy(state);
                winHandler.sendGamepadState();
                winHandler.sendVirtualGamepadState(state);
            }
        }
        else {
            if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                mouseMoveOffset.x = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_LEFT ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else if (binding == Binding.MOUSE_MOVE_DOWN || binding == Binding.MOUSE_MOVE_UP) {
                mouseMoveOffset.y = isActionDown ? (offset != 0 ? offset : (binding == Binding.MOUSE_MOVE_UP ? -1 : 1)) : 0;
                if (isActionDown) createMouseMoveTimer();
            }
            else {
                Pointer.Button pointerButton = binding.getPointerButton();
                if (isActionDown) {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonPress(pointerButton);
                    }
                    else xServer.injectKeyPress(binding.keycode);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else xServer.injectKeyRelease(binding.keycode);
                }
            }
        }
    }

    public Bitmap getIcon(byte id) {
        if (icons[id] == null) {
            Context context = getContext();
            try (InputStream is = context.getAssets().open("inputcontrols/icons/"+id+".png")) {
                icons[id] = BitmapFactory.decodeStream(is);
            }
            catch (IOException e) {}
        }
        return icons[id];
    }
}
