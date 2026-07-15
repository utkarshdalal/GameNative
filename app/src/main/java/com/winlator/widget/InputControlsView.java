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
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.util.SparseArray;

import androidx.compose.ui.input.pointer.PointerIcon;
import androidx.core.graphics.ColorUtils;

import app.gamenative.R;
import app.gamenative.data.ShooterModeConfig;
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
    private static final long SHOOTER_SPRINT_TAP_DURATION_MS = 120;
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
    private float lookDeadzoneAccumX, lookDeadzoneAccumY;
    private float lookSmoothX, lookSmoothY;
    private ControlElement lookFireElement = null;
    // Delays button-originated look/move until the touch becomes an intentional drag.
    private final SparseArray<PendingButtonLook> pendingButtonLooks = new SparseArray<>();
    // Right dynamic joystick (for gamepad_right_stick look type)
    private int rightJoystickPointerId = -1;
    private float rightJoystickCenterX, rightJoystickCenterY;
    private float rightJoystickCurrentX, rightJoystickCurrentY;
    private final boolean[] rightJoystickStates = new boolean[4];
    private boolean shooterSprintActive = false;
    private Binding shooterSprintTapBinding = Binding.NONE;
    private final Runnable shooterSprintTapReleaseRunnable = new Runnable() {
        @Override
        public void run() {
            releaseShooterSprintTap();
        }
    };
    private ShooterModeConfig shooterModeConfig = ShooterModeConfig.fromJson("");

    // Container-level shooter mode (auto-replaces STICK elements)
    private boolean containerShooterMode = false;
    private boolean containerShooterModeRuntime = false; // runtime toggle state

    // Callback invoked when the SHOW_KEYBOARD binding is triggered
    private Runnable showKeyboardCallback;
    // Tracks whether SHOW_KEYBOARD is currently held, so the callback fires once per press (rising edge only)
    private boolean showKeyboardPressed;

    private static class PendingButtonLook {
        private final float startX;
        private final float startY;
        private final ControlElement element;
        private final boolean leftSide;

        private PendingButtonLook(float startX, float startY, ControlElement element, boolean leftSide) {
            this.startX = startX;
            this.startY = startY;
            this.element = element;
            this.leftSide = leftSide;
        }
    }

    @SuppressLint("ResourceType")
    public InputControlsView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        setBackgroundColor(0x00000000);
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

    public float getOverlayOpacity() {
        return overlayOpacity;
    }

    public int getSnappingSize() {
        return snappingSize;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (profile != null && profile.isElementsLoaded() && oldw > 0 && w != oldw) {
            profile.loadElements(this);
        }
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
            float sizeMultiplier = getResolvedLookJoystickSize();
            drawDynamicJoystick(canvas, rightJoystickCenterX, rightJoystickCenterY,
                                rightJoystickCurrentX, rightJoystickCurrentY, sizeMultiplier);
        }

        // Draw container shooter mode toggle button
        if (containerShooterMode && isRuntimeToggleVisible() && !editMode) {
            drawContainerShooterToggle(canvas);
        }

        super.onDraw(canvas);
    }

    private void drawGrid(Canvas canvas) {
        canvas.drawColor(0x80000000);
        paint.setStrokeWidth(snappingSize * 0.0625f);

        paint.setAntiAlias(false);
        paint.setColor(0x80303030);

        int width = getMaxWidth();
        int height = getMaxHeight();

        for (int i = 0; i < width; i += snappingSize) {
            canvas.drawLine(i, 0, i, height, paint);
            canvas.drawLine(0, i, width, i, paint);
        }

        float cx = Mathf.roundTo(width * 0.5f, snappingSize);
        float cy = Mathf.roundTo(height * 0.5f, snappingSize);
        paint.setColor(0x99424242);

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
        if (!active) {
            releaseAllShooterInputs();
        }
        this.shooterModeActive = active;
        if (!active) commitGamepadState();
        invalidate();
    }

    public void setContainerShooterMode(boolean enabled) {
        this.containerShooterMode = enabled;
        this.containerShooterModeRuntime = enabled;
        if (!enabled && !shooterModeActive) {
            releaseAllShooterInputs();
            commitGamepadState();
        }
        invalidate();
    }

    public void setShooterModeConfig(ShooterModeConfig config) {
        releaseAllShooterInputs();
        commitGamepadState();
        this.shooterModeConfig = config != null ? config : ShooterModeConfig.fromJson("");
        lookAccumX = 0;
        lookAccumY = 0;
        lookDeadzoneAccumX = 0;
        lookDeadzoneAccumY = 0;
        lookSmoothX = 0;
        lookSmoothY = 0;
        invalidate();
    }

    public void setShooterModeConfigJson(String json) {
        setShooterModeConfig(ShooterModeConfig.fromJson(json));
    }

    public void setShowKeyboardCallback(Runnable callback) {
        this.showKeyboardCallback = callback;
    }

    public void triggerShowKeyboard() {
        if (showKeyboardCallback != null) showKeyboardCallback.run();
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

    private String getResolvedShooterMovementType() {
        if (containerShooterModeRuntime && shooterModeConfig != null) {
            return shooterModeConfig.getMovementType();
        }

        ControlElement smElement = getShooterModeElement();
        if (shooterModeActive && smElement != null) {
            return smElement.getShooterMovementType();
        }

        return shooterModeConfig != null ? shooterModeConfig.getMovementType() : "gamepad_left_stick";
    }

    private String getResolvedShooterLookType() {
        if (containerShooterModeRuntime && shooterModeConfig != null) {
            return shooterModeConfig.getLookType();
        }

        ControlElement smElement = getShooterModeElement();
        if (shooterModeActive && smElement != null) {
            return smElement.getShooterLookType();
        }

        return shooterModeConfig != null ? shooterModeConfig.getLookType() : "gamepad_right_stick";
    }

    private float getResolvedBaseLookSensitivity() {
        ControlElement smElement = getShooterModeElement();
        return smElement != null ? smElement.getShooterLookSensitivity() : 1.0f;
    }

    private float getProfileShooterJoystickSize() {
        ControlElement smElement = getShooterModeElement();
        return smElement != null ? smElement.getShooterJoystickSize() : 1.0f;
    }

    private float getResolvedMovementJoystickSize() {
        float configSize = shooterModeConfig != null ? shooterModeConfig.getMovementJoystickSize() : 1.0f;
        return getProfileShooterJoystickSize() * configSize;
    }

    private float getResolvedLookJoystickSize() {
        float configSize = shooterModeConfig != null ? shooterModeConfig.getLookJoystickSize() : 1.0f;
        return getProfileShooterJoystickSize() * configSize;
    }

    private float getMovementJoystickDeadzone() {
        return shooterModeConfig != null ? shooterModeConfig.getMovementJoystickDeadzone() : ShooterModeConfig.DEFAULT_ANALOG_STICK_DEADZONE;
    }

    private float getLookJoystickDeadzone() {
        return shooterModeConfig != null ? shooterModeConfig.getLookJoystickDeadzone() : ShooterModeConfig.DEFAULT_ANALOG_STICK_DEADZONE;
    }

    private float getMovementStickSensitivity() {
        return shooterModeConfig != null ? shooterModeConfig.getMovementStickSensitivity() : 1.0f;
    }

    private float getLookStickSensitivity() {
        return shooterModeConfig != null ? shooterModeConfig.getLookStickSensitivity() : 1.0f;
    }

    private boolean isMovementFloatingJoystickBehavior() {
        return shooterModeConfig != null &&
                ShooterModeConfig.JOYSTICK_FLOATING.equals(shooterModeConfig.getMovementJoystickBehavior());
    }

    private boolean isLookFloatingJoystickBehavior() {
        return shooterModeConfig != null &&
                ShooterModeConfig.JOYSTICK_FLOATING.equals(shooterModeConfig.getLookJoystickBehavior());
    }

    private boolean isRuntimeToggleVisible() {
        return shooterModeConfig == null || shooterModeConfig.getShowRuntimeToggle();
    }

    private float getJoystickOpacity() {
        if (shooterModeConfig != null && shooterModeConfig.getJoystickOpacity() > 0) {
            return shooterModeConfig.getJoystickOpacity();
        }
        return overlayOpacity;
    }

    private float applyJoystickDeadzone(float value, float deadzone) {
        float magnitude = Math.abs(value);
        if (magnitude <= deadzone) return 0;
        if (deadzone >= 1.0f) return 0;
        return ((magnitude - deadzone) / (1.0f - deadzone)) * Mathf.sign(value);
    }

    private float getStickOutputValue(float value, float deadzone, float sensitivity) {
        float adjustedValue = applyJoystickDeadzone(value, deadzone);
        return Mathf.clamp(adjustedValue * ControlElement.STICK_SENSITIVITY * sensitivity, -1, 1);
    }

    private int alphaForOpacity(float opacity, int maxAlpha) {
        return Mathf.clamp((int)(opacity * maxAlpha), 0, 255);
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
            Binding[] bindings = getJoystickBindings(getResolvedShooterMovementType());
            for (int i = 0; i < 4; i++) {
                if (joystickStates[i]) {
                    handleInputEvent(bindings[i], false);
                }
            }
            joystickPointerId = -1;
            Arrays.fill(joystickStates, false);
            releaseShooterSprint();
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
            lookDeadzoneAccumX = 0;
            lookDeadzoneAccumY = 0;
            lookSmoothX = 0;
            lookSmoothY = 0;
        }
    }

    private void releasePendingButtonLook() {
        pendingButtonLooks.clear();
    }

    private void releasePendingButtonLook(int pointerId) {
        pendingButtonLooks.remove(pointerId);
    }

    private void releaseAllShooterInputs() {
        releaseShooterJoystick();
        releaseShooterLook();
        releaseRightJoystick();
        releasePendingButtonLook();
        releaseShooterSprint();
    }

    private Binding getShooterSprintBinding() {
        String bindingName = shooterModeConfig != null ? shooterModeConfig.getSprintBinding() : ShooterModeConfig.SPRINT_BINDING_SHIFT;
        Binding binding = Binding.fromString(bindingName);
        return binding != Binding.NONE ? binding : Binding.KEY_SHIFT_L;
    }

    private boolean isShooterSprintPressMode() {
        return shooterModeConfig != null && shooterModeConfig.getOuterRingSprintPressMode();
    }

    private void tapShooterSprintBinding() {
        Binding binding = getShooterSprintBinding();
        cancelPendingShooterSprintTap();
        handleInputEvent(binding, true);
        commitGamepadStateIfNeeded(binding);
        shooterSprintTapBinding = binding;
        postDelayed(shooterSprintTapReleaseRunnable, SHOOTER_SPRINT_TAP_DURATION_MS);
    }

    private void cancelPendingShooterSprintTap() {
        removeCallbacks(shooterSprintTapReleaseRunnable);
        releaseShooterSprintTap();
    }

    private void releaseShooterSprintTap() {
        Binding binding = shooterSprintTapBinding;
        if (binding == Binding.NONE) return;

        shooterSprintTapBinding = Binding.NONE;
        handleInputEvent(binding, false);
        commitGamepadStateIfNeeded(binding);
    }

    private void commitGamepadStateIfNeeded(Binding binding) {
        if (binding == null || !binding.isGamepad()) return;
        commitGamepadState();
    }

    private void commitGamepadState() {
        WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
        if (winHandler != null && profile != null) {
            GamepadState state = profile.getGamepadState();
            winHandler.sendGamepadState();
            winHandler.sendVirtualGamepadState(state);
        }
    }

    private void releaseShooterSprint() {
        cancelPendingShooterSprintTap();
        if (shooterSprintActive) {
            if (!isShooterSprintPressMode()) {
                handleInputEvent(getShooterSprintBinding(), false);
            }
            shooterSprintActive = false;
        }
    }

    private void updateShooterSprint(float deltaX, float deltaY) {
        if (shooterModeConfig == null || !shooterModeConfig.getOuterRingSprintEnabled()) {
            releaseShooterSprint();
            return;
        }

        float magnitude = (float)Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        boolean shouldSprint = magnitude >= shooterModeConfig.getOuterRingSprintThreshold();
        if (shooterModeConfig.getOuterRingSprintPressMode()) {
            if (shouldSprint && !shooterSprintActive) {
                tapShooterSprintBinding();
            }
            shooterSprintActive = shouldSprint;
            return;
        }

        if (shouldSprint != shooterSprintActive) {
            handleInputEvent(getShooterSprintBinding(), shouldSprint);
            shooterSprintActive = shouldSprint;
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
        float sizeMultiplier = getResolvedLookJoystickSize();
        float radius = snappingSize * 6 * sizeMultiplier;
        if (radius <= 0) return;

        float localX = x - rightJoystickCenterX;
        float localY = y - rightJoystickCenterY;

        float distance = (float)Math.sqrt(localX * localX + localY * localY);
        if (distance > radius) {
            float angle = (float)Math.atan2(localY, localX);
            localX = (float)(Math.cos(angle) * radius);
            localY = (float)(Math.sin(angle) * radius);
            if (isLookFloatingJoystickBehavior()) {
                rightJoystickCenterX = x - localX;
                rightJoystickCenterY = y - localY;
            }
        }

        rightJoystickCurrentX = rightJoystickCenterX + localX;
        rightJoystickCurrentY = rightJoystickCenterY + localY;

        float deltaX = Mathf.clamp(localX / radius, -1, 1);
        float deltaY = Mathf.clamp(localY / radius, -1, 1);

        Binding[] bindings = getRightJoystickBindings();

        float deadzone = getLookJoystickDeadzone();
        float sensitivity = getLookStickSensitivity();

        for (int i = 0; i < 4; i++) {
            float value = (i == 1 || i == 3) ? deltaX : deltaY;
            value = getStickOutputValue(value, deadzone, sensitivity);
            handleInputEvent(bindings[i], true, value);
            rightJoystickStates[i] = true;
        }
        invalidate();
    }

    private void drawDynamicJoystick(Canvas canvas, float centerX, float centerY, float currentX, float currentY, float sizeMultiplier) {
        float radius = snappingSize * 6 * sizeMultiplier;
        float strokeWidth = snappingSize * 0.25f;
        int primaryColor = getPrimaryColor();
        float opacity = getJoystickOpacity();

        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, alphaForOpacity(opacity, 255)));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        canvas.drawCircle(centerX, centerY, radius, paint);

        float thumbRadius = snappingSize * 3.5f * sizeMultiplier;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, alphaForOpacity(opacity, 50)));
        canvas.drawCircle(currentX, currentY, thumbRadius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(ColorUtils.setAlphaComponent(primaryColor, alphaForOpacity(opacity, 255)));
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
        float joystickSizeMultiplier = getResolvedMovementJoystickSize();
        String movementType = getResolvedShooterMovementType();
        float radius = snappingSize * 6 * joystickSizeMultiplier;
        if (radius <= 0) return;

        float localX = x - joystickCenterX;
        float localY = y - joystickCenterY;

        float distance = (float)Math.sqrt(localX * localX + localY * localY);
        if (distance > radius) {
            float angle = (float)Math.atan2(localY, localX);
            localX = (float)(Math.cos(angle) * radius);
            localY = (float)(Math.sin(angle) * radius);
            if (isMovementFloatingJoystickBehavior()) {
                joystickCenterX = x - localX;
                joystickCenterY = y - localY;
            }
        }

        joystickCurrentX = joystickCenterX + localX;
        joystickCurrentY = joystickCenterY + localY;

        float deltaX = Mathf.clamp(localX / radius, -1, 1);
        float deltaY = Mathf.clamp(localY / radius, -1, 1);

        Binding[] bindings = getJoystickBindings(movementType);

        float analogDeadzone = getMovementJoystickDeadzone();
        float digitalDeadzone = ControlElement.STICK_DEAD_ZONE;
        float sensitivity = getMovementStickSensitivity();
        boolean[] newStates = {
            deltaY <= -digitalDeadzone,   // up
            deltaX >= digitalDeadzone,    // right
            deltaY >= digitalDeadzone,    // down
            deltaX <= -digitalDeadzone    // left
        };

        for (int i = 0; i < 4; i++) {
            float value = (i == 1 || i == 3) ? deltaX : deltaY;
            if (bindings[i].isGamepad()) {
                value = getStickOutputValue(value, analogDeadzone, sensitivity);
                handleInputEvent(bindings[i], true, value);
                joystickStates[i] = true;
            } else {
                if (newStates[i] != joystickStates[i]) {
                    handleInputEvent(bindings[i], newStates[i]);
                    joystickStates[i] = newStates[i];
                }
            }
        }

        updateShooterSprint(deltaX, deltaY);
        invalidate();
    }

    private void drawShooterJoystick(Canvas canvas) {
        float joystickSizeMultiplier = getResolvedMovementJoystickSize();
        drawDynamicJoystick(canvas, joystickCenterX, joystickCenterY, joystickCurrentX, joystickCurrentY, joystickSizeMultiplier);
    }

    private boolean isLeftSideTouch(float x) {
        float split = shooterModeConfig != null ? shooterModeConfig.getMovementZoneSplit() : 0.5f;
        return x < getWidth() * split;
    }

    private boolean shouldUseRightJoystickLook() {
        return "gamepad_right_stick".equals(getResolvedShooterLookType());
    }

    private boolean startShooterJoystickPointer(int pointerId, float x, float y) {
        if (joystickPointerId != -1) return false;

        joystickPointerId = pointerId;
        joystickCenterX = x;
        joystickCenterY = y;
        joystickCurrentX = x;
        joystickCurrentY = y;
        invalidate();
        return true;
    }

    private boolean startRightJoystickPointer(int pointerId, float x, float y) {
        if (rightJoystickPointerId != -1) return false;

        rightJoystickPointerId = pointerId;
        rightJoystickCenterX = x;
        rightJoystickCenterY = y;
        rightJoystickCurrentX = x;
        rightJoystickCurrentY = y;
        invalidate();
        return true;
    }

    private boolean startShooterLookPointer(int pointerId, float x, float y, ControlElement fireElement) {
        if (lookPointerId != -1) return false;

        lookPointerId = pointerId;
        lookLastX = x;
        lookLastY = y;
        lookAccumX = 0;
        lookAccumY = 0;
        lookDeadzoneAccumX = 0;
        lookDeadzoneAccumY = 0;
        lookSmoothX = 0;
        lookSmoothY = 0;
        lookFireElement = fireElement;
        return true;
    }

    private boolean startRightSideShooterPointer(int pointerId, float x, float y, ControlElement fireElement) {
        if (shouldUseRightJoystickLook()) {
            return startRightJoystickPointer(pointerId, x, y);
        }
        return startShooterLookPointer(pointerId, x, y, fireElement);
    }

    private void startPendingButtonLookPointer(int pointerId, float x, float y, ControlElement fireElement) {
        if (shooterModeConfig == null || !shooterModeConfig.getButtonLookThroughEnabled()) return;

        int threshold = shooterModeConfig.getButtonLookThroughDragThreshold();
        if (threshold <= 0) {
            if (isLeftSideTouch(x)) startShooterJoystickPointer(pointerId, x, y);
            else startRightSideShooterPointer(pointerId, x, y, fireElement);
            return;
        }

        if (pendingButtonLooks.get(pointerId) != null) return;
        pendingButtonLooks.put(pointerId, new PendingButtonLook(x, y, fireElement, isLeftSideTouch(x)));
    }

    private boolean handlePendingButtonLookMove(int pointerId, float x, float y) {
        PendingButtonLook pending = pendingButtonLooks.get(pointerId);
        if (pending == null) return false;

        float dx = x - pending.startX;
        float dy = y - pending.startY;
        float threshold = shooterModeConfig != null ? shooterModeConfig.getButtonLookThroughDragThreshold() : 0;
        if (dx * dx + dy * dy < threshold * threshold) {
            return true;
        }

        releasePendingButtonLook(pointerId);

        boolean started = pending.leftSide
                ? startShooterJoystickPointer(pointerId, pending.startX, pending.startY)
                : startRightSideShooterPointer(pointerId, pending.startX, pending.startY, pending.element);

        if (started) {
            return handleShooterTouchMovePointer(pointerId, x, y);
        }
        return true;
    }

    private boolean handleShooterTouchDown(int pointerId, float x, float y) {
        boolean handled = false;

        // Check container shooter mode toggle button first
        if (containerShooterMode && isRuntimeToggleVisible()) {
            android.graphics.RectF toggleRect = getToggleButtonRect();
            if (toggleRect.contains(x, y)) {
                containerShooterModeRuntime = !containerShooterModeRuntime;
                if (!containerShooterModeRuntime) {
                    releaseAllShooterInputs();
                    commitGamepadState();
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
                if (element.getType() == ControlElement.Type.BUTTON && element.isShooterLookThrough()) {
                    startPendingButtonLookPointer(pointerId, x, y, element);
                }
                break;
            }
        }
        if (!handled) {
            if (isLeftSideTouch(x)) {
                handled = startShooterJoystickPointer(pointerId, x, y);
            } else {
                handled = startRightSideShooterPointer(pointerId, x, y, null);
            }
        }
        return handled;
    }

    private boolean handleShooterTouchMovePointer(int pid, float x, float y) {
        if (handlePendingButtonLookMove(pid, x, y)) {
            return true;
        }
        if (pid == joystickPointerId) {
            handleShooterJoystickMove(x, y);
            return true;
        }
        if (pid == rightJoystickPointerId) {
            handleRightJoystickMove(x, y);
            return true;
        }
        if (pid == lookPointerId) {
            float rawDx = x - lookLastX;
            float rawDy = y - lookLastY;
            lookLastX = x;
            lookLastY = y;

            float dx = rawDx;
            float dy = rawDy;
            float lookDeadzone = shooterModeConfig != null ? shooterModeConfig.getLookDeadzone() : 0;
            if (lookDeadzone > 0) {
                lookDeadzoneAccumX += rawDx;
                lookDeadzoneAccumY += rawDy;
                if (lookDeadzoneAccumX * lookDeadzoneAccumX + lookDeadzoneAccumY * lookDeadzoneAccumY <
                        lookDeadzone * lookDeadzone) {
                    return true;
                }
                dx = lookDeadzoneAccumX;
                dy = lookDeadzoneAccumY;
                lookDeadzoneAccumX = 0;
                lookDeadzoneAccumY = 0;
            }

            float baseSensitivity = getResolvedBaseLookSensitivity();
            float sensitivityX = shooterModeConfig != null ? shooterModeConfig.getLookSensitivityX() : 1.0f;
            float sensitivityY = shooterModeConfig != null ? shooterModeConfig.getLookSensitivityY() : 1.0f;
            float scaledDx = dx * baseSensitivity * sensitivityX;
            float scaledDy = dy * baseSensitivity * sensitivityY;
            float accelerationMultiplier = getMouseLookAccelerationMultiplier(dx, dy);
            scaledDx *= accelerationMultiplier;
            scaledDy *= accelerationMultiplier;
            if (shooterModeConfig != null && shooterModeConfig.getInvertLookY()) {
                scaledDy = -scaledDy;
            }

            float smoothing = shooterModeConfig != null ? shooterModeConfig.getLookSmoothing() : 0;
            if (smoothing > 0) {
                lookSmoothX = lookSmoothX * smoothing + scaledDx * (1.0f - smoothing);
                lookSmoothY = lookSmoothY * smoothing + scaledDy * (1.0f - smoothing);
                scaledDx = lookSmoothX;
                scaledDy = lookSmoothY;
            }

            lookAccumX += scaledDx;
            lookAccumY += scaledDy;
            int moveX = (int)lookAccumX;
            int moveY = (int)lookAccumY;
            lookAccumX -= moveX;
            lookAccumY -= moveY;
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

    private float getMouseLookAccelerationMultiplier(float dx, float dy) {
        if (shooterModeConfig == null || !shooterModeConfig.getMouseAccelerationEnabled()) {
            return 1.0f;
        }

        float speed = (float)Math.sqrt(dx * dx + dy * dy);
        float maxMultiplier = shooterModeConfig.getMouseAccelerationMaxMultiplier();
        if (maxMultiplier <= 1.0f) return 1.0f;

        float multiplier = 1.0f + (speed / 24.0f) * shooterModeConfig.getMouseAccelerationStrength();
        return Mathf.clamp(multiplier, 1.0f, maxMultiplier);
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
                    touchpadView.setPointerButtonRightEnabled(true);
                    for (ControlElement element : profile.getElements()) {
                        if (element.handleTouchDown(pointerId, x, y)) {
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            handled = true;
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_LEFT_BUTTON) {
                            touchpadView.setPointerButtonLeftEnabled(false);
                        }
                        if (element.getBindingAt(0) == Binding.MOUSE_RIGHT_BUTTON) {
                            touchpadView.setPointerButtonRightEnabled(false);
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
                        int pid = event.getPointerId(i);
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(pid, x, y)) handled = true;
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
                        if (actionMasked == MotionEvent.ACTION_CANCEL) {
                            releaseAllShooterInputs();
                            commitGamepadState();
                            handled = true;
                        }
                        else {
                            if (pendingButtonLooks.get(pointerId) != null) {
                                releasePendingButtonLook(pointerId);
                                handled = true;
                            }
                            if (pointerId == joystickPointerId) {
                                releaseShooterJoystick();
                                handled = true;
                            }
                            if (pointerId == rightJoystickPointerId) {
                                releaseRightJoystick();
                                handled = true;
                            }
                            if (pointerId == lookPointerId) {
                                releaseShooterLook();
                                handled = true;
                            }
                        }
                    }
                    for (ControlElement element : profile.getElements()) if (element.handleTouchUp(pointerId)) handled = true;
                    if (!handled) touchpadView.onTouchEvent(event);
                    break;
            }

            // commit on-screen joystick state
            WinHandler winHandler = xServer != null ? xServer.getWinHandler() : null;
            if (winHandler != null) {
                GamepadState state = profile.getGamepadState();
                winHandler.sendGamepadState();
                winHandler.sendVirtualGamepadState(state);
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
        if (binding == null || binding == Binding.NONE) return;

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
            }
        }
        else {
            if (binding == Binding.SHOW_KEYBOARD) {
                if (isActionDown) {
                    if (!showKeyboardPressed) {
                        showKeyboardPressed = true;
                        if (showKeyboardCallback != null) showKeyboardCallback.run();
                    }
                } else {
                    showKeyboardPressed = false;
                }
                return;
            }
            else if (binding == Binding.ALT_ENTER) {
                if (isActionDown) {
                    xServer.injectKeyPress(Binding.KEY_ALT_L.keycode);
                    xServer.injectKeyPress(Binding.KEY_ENTER.keycode);
                }
                else {
                    xServer.injectKeyRelease(Binding.KEY_ENTER.keycode);
                    xServer.injectKeyRelease(Binding.KEY_ALT_L.keycode);
                }
                return;
            }
            else if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
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
                    else binding.inject(xServer, true);
                }
                else {
                    if (pointerButton != null) {
                        xServer.injectPointerButtonRelease(pointerButton);
                    }
                    else binding.inject(xServer, false);
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
