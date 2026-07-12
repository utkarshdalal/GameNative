package com.winlator.inputcontrols;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;

import androidx.core.graphics.ColorUtils;

import com.winlator.core.CubicBezierInterpolator;
import com.winlator.math.Mathf;
import com.winlator.widget.InputControlsView;
import com.winlator.widget.TouchpadView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Locale;

public class ControlElement {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final float DPAD_DEAD_ZONE = 0.3f;
    public static final float STICK_SENSITIVITY = 3.0f;
    public static final float TRACKPAD_MIN_SPEED = 0.8f;
    public static final float TRACKPAD_MAX_SPEED = 20.0f;
    public static final byte TRACKPAD_ACCELERATION_THRESHOLD = 4;
    public static final short BUTTON_MIN_TIME_TO_KEEP_PRESSED = 300;
    public static final int DEFAULT_BUTTON_COLOR = 0x00ffffff;
    public static final int DEFAULT_BUTTON_ACTIVE_COLOR = 0x00ffffff;
    public static final float INHERIT_BUTTON_OPACITY = -1.0f;
    public static final float DEFAULT_BUTTON_STROKE_SCALE = 1.0f;
    public enum Type {
        BUTTON, D_PAD, RANGE_BUTTON, STICK, TRACKPAD, SHOOTER_MODE;

        public static String[] names() {
            Type[] types = values();
            String[] names = new String[types.length];
            for (int i = 0; i < types.length; i++) names[i] = types[i].name().replace("_", "-");
            return names;
        }
    }
    public enum Shape {
        CIRCLE, RECT, ROUND_RECT, SQUARE;

        public static String[] names() {
            Shape[] shapes = values();
            String[] names = new String[shapes.length];
            for (int i = 0; i < shapes.length; i++) names[i] = shapes[i].name().replace("_", " ");
            return names;
        }
    }
    public enum Range {
        FROM_A_TO_Z(26), FROM_0_TO_9(10), FROM_F1_TO_F12(12), FROM_NP0_TO_NP9(10);
        public final byte max;

        Range(int max) {
            this.max = (byte)max;
        }

        public static String[] names() {
            Range[] ranges = values();
            String[] names = new String[ranges.length];
            for (int i = 0; i < ranges.length; i++) names[i] = ranges[i].name().replace("_", " ");
            return names;
        }
    }
    private final InputControlsView inputControlsView;
    private Type type = Type.BUTTON;
    private Shape shape = Shape.CIRCLE;
    private Binding[] bindings = {Binding.NONE, Binding.NONE, Binding.NONE, Binding.NONE};
    private float scale = 1.0f;
    private short x;
    private short y;
    private boolean selected = false;
    private boolean toggleSwitch = false;
    private boolean scrollLocked = false;
    private int currentPointerId = -1;
    private final Rect boundingBox = new Rect();
    private boolean[] states = new boolean[4];
    private boolean boundingBoxNeedsUpdate = true;
    private String text = "";
    private byte iconId;
    private Range range;
    private byte orientation;
    private PointF currentPosition;
    private RangeScroller scroller;
    private CubicBezierInterpolator interpolator;
    private Object touchTime;
    private String shooterMovementType = "wasd";
    private String shooterLookType = "mouse";
    private float shooterLookSensitivity = 1.0f;
    private float shooterJoystickSize = 1.0f;
    private int buttonColor = DEFAULT_BUTTON_COLOR;
    private int buttonActiveColor = DEFAULT_BUTTON_ACTIVE_COLOR;
    private boolean buttonActiveColorCustom = false;
    private float buttonOpacity = INHERIT_BUTTON_OPACITY;
    private float buttonStrokeScale = DEFAULT_BUTTON_STROKE_SCALE;
    private boolean shooterLookThrough = true;

    public ControlElement(InputControlsView inputControlsView) {
        this.inputControlsView = inputControlsView;
    }

    private void reset() {
        setBinding(Binding.NONE);
        scroller = null;

        if (type == Type.STICK) {
            bindings[0] = Binding.GAMEPAD_LEFT_THUMB_UP;
            bindings[1] = Binding.GAMEPAD_LEFT_THUMB_RIGHT;
            bindings[2] = Binding.GAMEPAD_LEFT_THUMB_DOWN;
            bindings[3] = Binding.GAMEPAD_LEFT_THUMB_LEFT;
        }
        else if (type == Type.D_PAD) {
            bindings[0] = Binding.GAMEPAD_DPAD_UP;
            bindings[1] = Binding.GAMEPAD_DPAD_RIGHT;
            bindings[2] = Binding.GAMEPAD_DPAD_DOWN;
            bindings[3] = Binding.GAMEPAD_DPAD_LEFT;
        }
        else if (type == Type.TRACKPAD) {
            bindings[0] = Binding.MOUSE_MOVE_UP;
            bindings[1] = Binding.MOUSE_MOVE_RIGHT;
            bindings[2] = Binding.MOUSE_MOVE_DOWN;
            bindings[3] = Binding.MOUSE_MOVE_LEFT;
        }
        else if (type == Type.RANGE_BUTTON) {
            scroller = new RangeScroller(inputControlsView, this);
        }
        else if (type == Type.SHOOTER_MODE) {
            shape = Shape.CIRCLE;
            iconId = 7;
            shooterMovementType = "wasd";
            shooterLookType = "mouse";
            shooterLookSensitivity = 1.0f;
            shooterJoystickSize = 1.0f;
        }

        text = "";
        if (type != Type.SHOOTER_MODE) iconId = 0;
        range = null;
        boundingBoxNeedsUpdate = true;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
        reset();
    }

    public void setTypeWithoutReset(Type type) {
        this.type = type;
        if (type == Type.RANGE_BUTTON && scroller == null) {
            scroller = new RangeScroller(inputControlsView, this);
        }
        boundingBoxNeedsUpdate = true;
    }

    public int getBindingCount() {
        return bindings.length;
    }

    public void setBindingCount(int bindingCount) {
        bindings = new Binding[bindingCount];
        setBinding(Binding.NONE);
        states = new boolean[bindingCount];
        boundingBoxNeedsUpdate = true;
    }

    public Shape getShape() {
        return shape;
    }

    public void setShape(Shape shape) {
        this.shape = shape;
        boundingBoxNeedsUpdate = true;
    }

    public Range getRange() {
        return range != null ? range : Range.FROM_A_TO_Z;
    }

    public void setRange(Range range) {
        this.range = range;
    }

    public byte getOrientation() {
        return orientation;
    }

    public void setOrientation(byte orientation) {
        this.orientation = orientation;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isToggleSwitch() {
        return toggleSwitch;
    }

    public void setToggleSwitch(boolean toggleSwitch) {
        this.toggleSwitch = toggleSwitch;
    }

    public boolean isScrollLocked() {
        return scrollLocked;
    }

    public void setScrollLocked(boolean scrollLocked) {
        this.scrollLocked = scrollLocked;
    }

    public Binding getBindingAt(int index) {
        return index < bindings.length ? bindings[index] : Binding.NONE;
    }

    public void setBindingAt(int index, Binding binding) {
        if (index >= bindings.length) {
            int oldLength = bindings.length;
            bindings = Arrays.copyOf(bindings, index+1);
            Arrays.fill(bindings, oldLength, bindings.length, Binding.NONE);
            states = new boolean[bindings.length];
            boundingBoxNeedsUpdate = true;
        }
        bindings[index] = binding;
    }

    public void setBinding(Binding binding) {
        Arrays.fill(bindings, binding);
    }

    public String getShooterMovementType() {
        return shooterMovementType;
    }

    public void setShooterMovementType(String shooterMovementType) {
        this.shooterMovementType = shooterMovementType != null ? shooterMovementType : "wasd";
    }

    public String getShooterLookType() {
        return shooterLookType;
    }

    public void setShooterLookType(String shooterLookType) {
        this.shooterLookType = shooterLookType != null ? shooterLookType : "mouse";
    }

    public float getShooterLookSensitivity() {
        return shooterLookSensitivity;
    }

    public void setShooterLookSensitivity(float shooterLookSensitivity) {
        this.shooterLookSensitivity = shooterLookSensitivity;
    }

    public float getShooterJoystickSize() {
        return shooterJoystickSize;
    }

    public void setShooterJoystickSize(float shooterJoystickSize) {
        this.shooterJoystickSize = shooterJoystickSize;
    }

    public int getButtonColor() {
        return buttonColor;
    }

    public void setButtonColor(int buttonColor) {
        this.buttonColor = buttonColor & 0x00ffffff;
    }

    public int getButtonActiveColor() {
        return buttonActiveColor;
    }

    public void setButtonActiveColor(int buttonActiveColor) {
        setButtonActiveColor(buttonActiveColor, true);
    }

    public void setButtonActiveColor(int buttonActiveColor, boolean custom) {
        this.buttonActiveColor = buttonActiveColor & 0x00ffffff;
        this.buttonActiveColorCustom = custom;
    }

    public boolean hasCustomButtonActiveColor() {
        return buttonActiveColorCustom;
    }

    public float getButtonOpacity() {
        return buttonOpacity;
    }

    public void setButtonOpacity(float buttonOpacity) {
        if (buttonOpacity < 0) {
            this.buttonOpacity = INHERIT_BUTTON_OPACITY;
        }
        else {
            this.buttonOpacity = Mathf.clamp(buttonOpacity, 0.0f, 1.0f);
        }
    }

    public float getButtonStrokeScale() {
        return buttonStrokeScale;
    }

    public void setButtonStrokeScale(float buttonStrokeScale) {
        this.buttonStrokeScale = Mathf.clamp(buttonStrokeScale, 0.5f, 2.0f);
    }

    public boolean isShooterLookThrough() {
        return shooterLookThrough;
    }

    public void setShooterLookThrough(boolean shooterLookThrough) {
        this.shooterLookThrough = shooterLookThrough;
    }

    public void copyButtonAppearanceFrom(ControlElement element) {
        buttonColor = element.buttonColor;
        buttonActiveColor = element.buttonActiveColor;
        buttonActiveColorCustom = element.buttonActiveColorCustom;
        buttonOpacity = element.buttonOpacity;
        buttonStrokeScale = element.buttonStrokeScale;
        shooterLookThrough = element.shooterLookThrough;
    }

    public float getEffectiveButtonOpacity(float fallbackOpacity) {
        return buttonOpacity >= 0 ? buttonOpacity : fallbackOpacity;
    }

    public static int parseRgbColor(Object value, int fallbackColor) {
        if (value instanceof Number) return ((Number)value).intValue() & 0x00ffffff;
        if (value instanceof String) {
            String hex = ((String)value).trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (hex.length() == 8) hex = hex.substring(2);
            if (hex.length() == 6) {
                try {
                    return (int)Long.parseLong(hex, 16) & 0x00ffffff;
                }
                catch (NumberFormatException ignored) {
                }
            }
        }
        return fallbackColor;
    }

    public static String formatRgbColor(int color) {
        return String.format(Locale.US, "#%06X", color & 0x00ffffff);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = scale;
        boundingBoxNeedsUpdate = true;
    }

    public short getX() {
        return x;
    }

    public void setX(int x) {
        this.x = (short)x;
        boundingBoxNeedsUpdate = true;
    }

    public short getY() {
        return y;
    }

    public void setY(int y) {
        this.y = (short)y;
        boundingBoxNeedsUpdate = true;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text : "";
    }

    public byte getIconId() {
        return iconId;
    }

    public void setIconId(int iconId) {
        this.iconId = (byte)iconId;
    }

    public Rect getBoundingBox() {
        if (boundingBoxNeedsUpdate) computeBoundingBox();
        return boundingBox;
    }

    private Rect computeBoundingBox() {
        int snappingSize = inputControlsView.getSnappingSize();
        int halfWidth = 0;
        int halfHeight = 0;

        switch (type) {
            case BUTTON:
                switch (shape) {
                    case RECT:
                    case ROUND_RECT:
                        halfWidth = snappingSize * 4;
                        halfHeight = snappingSize * 2;
                        break;
                    case SQUARE:
                        halfWidth = (int)(snappingSize * 2.5f);
                        halfHeight = (int)(snappingSize * 2.5f);
                        break;
                    case CIRCLE:
                        halfWidth = snappingSize * 3;
                        halfHeight = snappingSize * 3;
                        break;
                }
                break;
            case D_PAD: {
                halfWidth = snappingSize * 7;
                halfHeight = snappingSize * 7;
                break;
            }
            case TRACKPAD:
            case STICK: {
                halfWidth = snappingSize * 6;
                halfHeight = snappingSize * 6;
                break;
            }
            case RANGE_BUTTON: {
                halfWidth = snappingSize * ((bindings.length * 4) / 2);
                halfHeight = snappingSize * 2;

                if (orientation == 1) {
                    int tmp = halfWidth;
                    halfWidth = halfHeight;
                    halfHeight = tmp;
                }
                break;
            }
            case SHOOTER_MODE: {
                halfWidth = snappingSize * 3;
                halfHeight = snappingSize * 3;
                break;
            }
        }

        halfWidth *= scale;
        halfHeight *= scale;
        boundingBox.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
        boundingBoxNeedsUpdate = false;
        return boundingBox;
    }

    private String getDisplayText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        else {
            Binding binding = getBindingAt(0);
            String text = binding.toString().replace("NUMPAD ", "NP").replace("BUTTON ", "").replace("SHOW KEYBOARD", "KEY");
            if (text.length() > 7) {
                String[] parts = text.split(" ");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) sb.append(part.charAt(0));
                return (binding.isMouse() ? "M" : "")+ sb;
            }
            else return text;
        }
    }

    private static float getTextSizeForWidth(Paint paint, String text, float desiredWidth) {
        final byte testTextSize = 48;
        paint.setTextSize(testTextSize);
        return testTextSize * desiredWidth / paint.measureText(text);
    }

    private static String getRangeTextForIndex(Range range, int index) {
        String text = "";
        switch (range) {
            case FROM_A_TO_Z:
                text = String.valueOf((char)(65 + index));
                break;
            case FROM_0_TO_9:
                text = String.valueOf((index + 1) % 10);
                break;
            case FROM_F1_TO_F12:
                text = "F"+(index + 1);
                break;
            case FROM_NP0_TO_NP9:
                text = "NP"+((index + 1) % 10);
                break;
        }
        return text;
    }

    private int getAppearanceDrawColor(boolean active) {
        int rgb = active ? buttonActiveColor : buttonColor;
        int alpha = (int)(getEffectiveButtonOpacity(inputControlsView.getOverlayOpacity()) * 255);
        return ColorUtils.setAlphaComponent(0xff000000 | rgb, alpha);
    }

    private int getEditorSelectionDrawColor() {
        int rgb = buttonActiveColorCustom ? buttonActiveColor : inputControlsView.getSecondaryColor();
        int alpha = (int)(getEffectiveButtonOpacity(inputControlsView.getOverlayOpacity()) * 255);
        return ColorUtils.setAlphaComponent(0xff000000 | (rgb & 0x00ffffff), alpha);
    }

    private int getRuntimeSelectedDrawColor() {
        if (buttonActiveColorCustom) return getAppearanceDrawColor(true);
        int alpha = (int)(getEffectiveButtonOpacity(inputControlsView.getOverlayOpacity()) * 255);
        return ColorUtils.setAlphaComponent(inputControlsView.getSecondaryColor(), alpha);
    }

    private static void setDPadDirectionPath(Path path, byte direction, Rect boundingBox, float cx, float cy, float offsetX, float offsetY, float start) {
        path.reset();
        switch (direction) {
            case 0:
                path.moveTo(cx, cy - start);
                path.lineTo(cx - offsetX, cy - offsetY);
                path.lineTo(cx - offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, boundingBox.top);
                path.lineTo(cx + offsetX, cy - offsetY);
                break;
            case 1:
                path.moveTo(cx + start, cy);
                path.lineTo(cx + offsetY, cy - offsetX);
                path.lineTo(boundingBox.right, cy - offsetX);
                path.lineTo(boundingBox.right, cy + offsetX);
                path.lineTo(cx + offsetY, cy + offsetX);
                break;
            case 2:
                path.moveTo(cx, cy + start);
                path.lineTo(cx - offsetX, cy + offsetY);
                path.lineTo(cx - offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, boundingBox.bottom);
                path.lineTo(cx + offsetX, cy + offsetY);
                break;
            case 3:
                path.moveTo(cx - start, cy);
                path.lineTo(cx - offsetY, cy - offsetX);
                path.lineTo(boundingBox.left, cy - offsetX);
                path.lineTo(boundingBox.left, cy + offsetX);
                path.lineTo(cx - offsetY, cy + offsetX);
                break;
        }
        path.close();
    }

    private static void setHorizontalRangeSegmentPath(Path path, Rect boundingBox, float segmentLeft, float segmentRight, float radius) {
        float left = Math.max(segmentLeft, boundingBox.left);
        float right = Math.min(segmentRight, boundingBox.right);
        float top = boundingBox.top;
        float bottom = boundingBox.bottom;
        float width = Math.max(0.0f, right - left);
        float leftRadius = segmentLeft <= boundingBox.left ? Math.min(radius, width * 0.5f) : 0.0f;
        float rightRadius = segmentRight >= boundingBox.right ? Math.min(radius, width * 0.5f) : 0.0f;

        path.reset();
        path.moveTo(left + leftRadius, top);
        path.lineTo(right - rightRadius, top);
        if (rightRadius > 0.0f) {
            path.quadTo(right, top, right, top + rightRadius);
            path.lineTo(right, bottom - rightRadius);
            path.quadTo(right, bottom, right - rightRadius, bottom);
        }
        else {
            path.lineTo(right, bottom);
        }
        path.lineTo(left + leftRadius, bottom);
        if (leftRadius > 0.0f) {
            path.quadTo(left, bottom, left, bottom - leftRadius);
            path.lineTo(left, top + leftRadius);
            path.quadTo(left, top, left + leftRadius, top);
        }
        else {
            path.lineTo(left, top);
        }
        path.close();
    }

    private static void setVerticalRangeSegmentPath(Path path, Rect boundingBox, float segmentTop, float segmentBottom, float radius) {
        float left = boundingBox.left;
        float right = boundingBox.right;
        float top = Math.max(segmentTop, boundingBox.top);
        float bottom = Math.min(segmentBottom, boundingBox.bottom);
        float height = Math.max(0.0f, bottom - top);
        float topRadius = segmentTop <= boundingBox.top ? Math.min(radius, height * 0.5f) : 0.0f;
        float bottomRadius = segmentBottom >= boundingBox.bottom ? Math.min(radius, height * 0.5f) : 0.0f;

        path.reset();
        path.moveTo(left + topRadius, top);
        path.lineTo(right - topRadius, top);
        if (topRadius > 0.0f) {
            path.quadTo(right, top, right, top + topRadius);
        }
        else {
            path.lineTo(right, top);
        }
        path.lineTo(right, bottom - bottomRadius);
        if (bottomRadius > 0.0f) {
            path.quadTo(right, bottom, right - bottomRadius, bottom);
            path.lineTo(left + bottomRadius, bottom);
            path.quadTo(left, bottom, left, bottom - bottomRadius);
        }
        else {
            path.lineTo(right, bottom);
            path.lineTo(left, bottom);
        }
        path.lineTo(left, top + topRadius);
        if (topRadius > 0.0f) {
            path.quadTo(left, top, left + topRadius, top);
        }
        else {
            path.lineTo(left, top);
        }
        path.close();
    }

    private static void setHorizontalRangeOutlinePath(Path path, Rect boundingBox, float radius, float skipLeft, float skipRight) {
        float left = boundingBox.left;
        float top = boundingBox.top;
        float right = boundingBox.right;
        float bottom = boundingBox.bottom;

        path.reset();
        if (skipLeft > left) {
            float stop = Math.min(skipLeft, right);
            path.moveTo(left + radius, top);
            path.lineTo(Math.max(left + radius, stop), top);
            path.moveTo(left + radius, bottom);
            path.lineTo(Math.max(left + radius, stop), bottom);
            path.moveTo(left + radius, top);
            path.quadTo(left, top, left, top + radius);
            path.lineTo(left, bottom - radius);
            path.quadTo(left, bottom, left + radius, bottom);
        }

        if (skipRight < right) {
            float start = Math.max(skipRight, left);
            path.moveTo(Math.min(right - radius, start), top);
            path.lineTo(right - radius, top);
            path.quadTo(right, top, right, top + radius);
            path.lineTo(right, bottom - radius);
            path.quadTo(right, bottom, right - radius, bottom);
            path.moveTo(Math.min(right - radius, start), bottom);
            path.lineTo(right - radius, bottom);
        }
    }

    private static void setVerticalRangeOutlinePath(Path path, Rect boundingBox, float radius, float skipTop, float skipBottom) {
        float left = boundingBox.left;
        float top = boundingBox.top;
        float right = boundingBox.right;
        float bottom = boundingBox.bottom;

        path.reset();
        if (skipTop > top) {
            float stop = Math.min(skipTop, bottom);
            path.moveTo(left + radius, top);
            path.lineTo(right - radius, top);
            path.quadTo(right, top, right, top + radius);
            path.moveTo(left + radius, top);
            path.quadTo(left, top, left, top + radius);
            path.lineTo(left, Math.max(top + radius, stop));
            path.moveTo(right, top + radius);
            path.lineTo(right, Math.max(top + radius, stop));
        }

        if (skipBottom < bottom) {
            float start = Math.max(skipBottom, top);
            path.moveTo(left, Math.min(bottom - radius, start));
            path.lineTo(left, bottom - radius);
            path.quadTo(left, bottom, left + radius, bottom);
            path.lineTo(right - radius, bottom);
            path.quadTo(right, bottom, right, bottom - radius);
            path.moveTo(right, Math.min(bottom - radius, start));
            path.lineTo(right, bottom - radius);
        }
    }

    public void draw(Canvas canvas) {
        int snappingSize = inputControlsView.getSnappingSize();
        Paint paint = inputControlsView.getPaint();
        boolean active = selected || currentPointerId != -1;
        boolean editSelected = selected && inputControlsView.isEditMode();
        int normalColor = getAppearanceDrawColor(false);
        int activeColor = editSelected ? getEditorSelectionDrawColor() : getAppearanceDrawColor(true);
        if (selected && !editSelected) activeColor = getRuntimeSelectedDrawColor();
        int primaryColor = active ? activeColor : normalColor;
        int contentColor = selected && !buttonActiveColorCustom ? normalColor : primaryColor;

        paint.setColor(primaryColor);
        paint.setStyle(Paint.Style.STROKE);
        float strokeWidth = snappingSize * 0.25f * buttonStrokeScale;
        paint.setStrokeWidth(strokeWidth);
        Rect boundingBox = getBoundingBox();

        switch (type) {
            case BUTTON: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();

                switch (shape) {
                    case CIRCLE:
                        canvas.drawCircle(cx, cy, boundingBox.width() * 0.5f, paint);
                        break;
                    case RECT:
                        canvas.drawRect(boundingBox, paint);
                        break;
                    case ROUND_RECT: {
                        float radius = boundingBox.height() * 0.5f;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                    case SQUARE: {
                        float radius = snappingSize * 0.75f * scale;
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                        break;
                    }
                }

                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, contentColor);
                }
                else {
                    String text = getDisplayText();
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(contentColor);
                    canvas.drawText(text, x, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                }
                break;
            }
            case D_PAD: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float offsetX = snappingSize * 2 * scale;
                float offsetY = snappingSize * 3 * scale;
                float start = snappingSize * scale;
                Path path = inputControlsView.getPath();

                for (byte i = 0; i < 4; i++) {
                    setDPadDirectionPath(path, i, boundingBox, cx, cy, offsetX, offsetY, start);
                    boolean directionActive = editSelected || states[i];

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(strokeWidth);
                    paint.setColor(directionActive ? activeColor : normalColor);
                    canvas.drawPath(path, paint);
                }
                break;
            }
            case RANGE_BUTTON: {
                Range range = getRange();
                int oldColor = editSelected ? activeColor : normalColor;
                int activeRangeIndex = editSelected ? -1 : scroller.getActiveIndex();
                float radius = snappingSize * 0.75f * scale;
                float activeStrokeWidth = strokeWidth * 1.5f;
                float activeRadius = radius + (activeStrokeWidth - strokeWidth) * 0.5f;
                float elementSize = scroller.getElementSize();
                float minTextSize = snappingSize * 2 * scale;
                float scrollOffset = scroller.getScrollOffset();
                byte[] rangeIndex = scroller.getRangeIndex();
                Path path = inputControlsView.getPath();
                path.reset();
                paint.setColor(oldColor);
                paint.setStrokeWidth(strokeWidth);

                if (orientation == 0) {
                    float lineTop = boundingBox.top + strokeWidth * 0.5f;
                    float lineBottom = boundingBox.bottom - strokeWidth * 0.5f;
                    float startX = boundingBox.left;
                    boolean drawActiveSegmentOutline = false;
                    float activeSegmentLeft = 0;
                    float activeSegmentRight = 0;

                    canvas.save();
                    path.addRoundRect(startX, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                    startX -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        float segmentLeft = startX;
                        float segmentRight = startX + elementSize;
                        boolean segmentActive = index == activeRangeIndex;
                        boolean segmentVisible = segmentLeft < boundingBox.right && segmentRight > boundingBox.left;

                        paint.setStyle(Paint.Style.STROKE);
                        int previousIndex = (index - 1 + range.max) % range.max;
                        boolean activeBoundary = index == activeRangeIndex || previousIndex == activeRangeIndex;
                        paint.setColor(activeBoundary ? activeColor : oldColor);
                        paint.setStrokeWidth(activeBoundary ? activeStrokeWidth : strokeWidth);

                        if (!activeBoundary && startX > boundingBox.left && startX < boundingBox.right) canvas.drawLine(startX, lineTop, startX, lineBottom, paint);
                        if (segmentActive && segmentVisible) {
                            drawActiveSegmentOutline = true;
                            activeSegmentLeft = segmentLeft;
                            activeSegmentRight = segmentRight;
                        }
                        paint.setStrokeWidth(strokeWidth);
                        String text = getRangeTextForIndex(range, index);

                        if (startX < boundingBox.right && startX + elementSize > boundingBox.left) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(segmentActive ? activeColor : oldColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, elementSize - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, startX + elementSize * 0.5f, (y - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                        }
                        startX += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    paint.setStrokeWidth(strokeWidth);
                    if (drawActiveSegmentOutline) {
                        setHorizontalRangeOutlinePath(path, boundingBox, radius, activeSegmentLeft, activeSegmentRight);
                        canvas.drawPath(path, paint);
                    }
                    else {
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                    }

                    if (drawActiveSegmentOutline) {
                        setHorizontalRangeSegmentPath(path, boundingBox, activeSegmentLeft, activeSegmentRight, activeRadius);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(activeColor);
                        paint.setStrokeWidth(activeStrokeWidth);
                        canvas.drawPath(path, paint);
                        paint.setStrokeWidth(strokeWidth);
                    }
                }
                else {
                    float lineLeft = boundingBox.left + strokeWidth * 0.5f;
                    float lineRight = boundingBox.right - strokeWidth * 0.5f;
                    float startY = boundingBox.top;
                    boolean drawActiveSegmentOutline = false;
                    float activeSegmentTop = 0;
                    float activeSegmentBottom = 0;

                    canvas.save();
                    path.addRoundRect(boundingBox.left, startY, boundingBox.right, boundingBox.bottom, radius, radius, Path.Direction.CW);
                    canvas.clipPath(path);
                    startY -= scrollOffset % elementSize;

                    for (byte i = rangeIndex[0]; i < rangeIndex[1]; i++) {
                        int index = i % range.max;
                        float segmentTop = startY;
                        float segmentBottom = startY + elementSize;
                        boolean segmentActive = index == activeRangeIndex;
                        boolean segmentVisible = segmentTop < boundingBox.bottom && segmentBottom > boundingBox.top;

                        paint.setStyle(Paint.Style.STROKE);
                        int previousIndex = (index - 1 + range.max) % range.max;
                        boolean activeBoundary = index == activeRangeIndex || previousIndex == activeRangeIndex;
                        paint.setColor(activeBoundary ? activeColor : oldColor);
                        paint.setStrokeWidth(activeBoundary ? activeStrokeWidth : strokeWidth);

                        if (!activeBoundary && startY > boundingBox.top && startY < boundingBox.bottom) canvas.drawLine(lineLeft, startY, lineRight, startY, paint);
                        if (segmentActive && segmentVisible) {
                            drawActiveSegmentOutline = true;
                            activeSegmentTop = segmentTop;
                            activeSegmentBottom = segmentBottom;
                        }
                        paint.setStrokeWidth(strokeWidth);
                        String text = getRangeTextForIndex(range, index);

                        if (startY < boundingBox.bottom && startY + elementSize > boundingBox.top) {
                            paint.setStyle(Paint.Style.FILL);
                            paint.setColor(segmentActive ? activeColor : oldColor);
                            paint.setTextSize(Math.min(getTextSizeForWidth(paint, text, boundingBox.width() - strokeWidth * 2), minTextSize));
                            paint.setTextAlign(Paint.Align.CENTER);
                            canvas.drawText(text, x, startY + elementSize * 0.5f - ((paint.descent() + paint.ascent()) * 0.5f), paint);
                        }
                        startY += elementSize;
                    }

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    canvas.restore();

                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(oldColor);
                    paint.setStrokeWidth(strokeWidth);
                    if (drawActiveSegmentOutline) {
                        setVerticalRangeOutlinePath(path, boundingBox, radius, activeSegmentTop, activeSegmentBottom);
                        canvas.drawPath(path, paint);
                    }
                    else {
                        canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                    }

                    if (drawActiveSegmentOutline) {
                        setVerticalRangeSegmentPath(path, boundingBox, activeSegmentTop, activeSegmentBottom, activeRadius);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(activeColor);
                        paint.setStrokeWidth(activeStrokeWidth);
                        canvas.drawPath(path, paint);
                        paint.setStrokeWidth(strokeWidth);
                    }
                }

                if (editSelected) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(activeColor);
                    paint.setStrokeWidth(activeStrokeWidth);
                    canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, activeRadius, activeRadius, paint);
                    paint.setStrokeWidth(strokeWidth);
                }
                break;
            }
            case STICK: {
                int cx = boundingBox.centerX();
                int cy = boundingBox.centerY();
                int oldColor = paint.getColor();
                canvas.drawCircle(cx, cy, boundingBox.height() * 0.5f, paint);

                float thumbstickX = currentPosition != null ? currentPosition.x : cx;
                float thumbstickY = currentPosition != null ? currentPosition.y : cy;

                short thumbRadius = (short) (snappingSize * 3.5f * scale);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(ColorUtils.setAlphaComponent(contentColor, Math.min(50, contentColor >>> 24)));
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(oldColor);
                canvas.drawCircle(thumbstickX, thumbstickY, thumbRadius + strokeWidth * 0.5f, paint);
                break;
            }
            case TRACKPAD: {
                float radius = boundingBox.height() * 0.15f;
                canvas.drawRoundRect(boundingBox.left, boundingBox.top, boundingBox.right, boundingBox.bottom, radius, radius, paint);
                float offset = strokeWidth * 2.5f;
                float innerStrokeWidth = strokeWidth * 2;
                float innerHeight = boundingBox.height() - offset * 2;
                radius = (innerHeight / boundingBox.height()) * radius - (innerStrokeWidth * 0.5f + strokeWidth * 0.5f);
                paint.setStrokeWidth(innerStrokeWidth);
                canvas.drawRoundRect(boundingBox.left + offset, boundingBox.top + offset, boundingBox.right - offset, boundingBox.bottom - offset, radius, radius, paint);
                break;
            }
            case SHOOTER_MODE: {
                float cx = boundingBox.centerX();
                float cy = boundingBox.centerY();
                float halfW = boundingBox.width() * 0.5f;

                if (selected) {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(ColorUtils.setAlphaComponent(primaryColor, Math.min(80, primaryColor >>> 24)));
                    canvas.drawCircle(cx, cy, halfW, paint);
                }

                // Draw outline
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(primaryColor);
                paint.setStrokeWidth(strokeWidth);
                canvas.drawCircle(cx, cy, halfW, paint);

                // Draw icon or fallback text
                if (iconId > 0) {
                    drawIcon(canvas, cx, cy, boundingBox.width(), boundingBox.height(), iconId, contentColor);
                } else {
                    String displayText = (text != null && !text.isEmpty()) ? text : "DJ";
                    paint.setTextSize(Math.min(getTextSizeForWidth(paint, displayText, boundingBox.width() - strokeWidth * 2), snappingSize * 2 * scale));
                    paint.setTextAlign(Paint.Align.CENTER);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(contentColor);
                    canvas.drawText(displayText, cx, (cy - ((paint.descent() + paint.ascent()) * 0.5f)), paint);
                }
                break;
            }
        }
    }

    private void drawIcon(Canvas canvas, float cx, float cy, float width, float height, int iconId, int tintColor) {
        Paint paint = inputControlsView.getPaint();
        Bitmap icon = inputControlsView.getIcon((byte)iconId);
        paint.setColorFilter(new PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN));
        int margin = (int)(inputControlsView.getSnappingSize() * (shape == Shape.CIRCLE || shape == Shape.SQUARE ? 2.0f : 1.0f) * scale);
        int halfSize = (int)((Math.min(width, height) - margin) * 0.5f);

        Rect srcRect = new Rect(0, 0, icon.getWidth(), icon.getHeight());
        Rect dstRect = new Rect((int)(cx - halfSize), (int)(cy - halfSize), (int)(cx + halfSize), (int)(cy + halfSize));
        canvas.drawBitmap(icon, srcRect, dstRect, paint);
        paint.setColorFilter(null);
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject elementJSONObject = new JSONObject();
            elementJSONObject.put("type", type.name());
            elementJSONObject.put("shape", shape.name());

            JSONArray bindingsJSONArray = new JSONArray();
            for (Binding binding : bindings) bindingsJSONArray.put(binding.name());

            elementJSONObject.put("bindings", bindingsJSONArray);
            elementJSONObject.put("scale", Float.valueOf(scale));
            elementJSONObject.put("x", (float)x / inputControlsView.getMaxWidth());
            elementJSONObject.put("y", (float)y / inputControlsView.getMaxHeight());
            elementJSONObject.put("toggleSwitch", toggleSwitch);
            elementJSONObject.put("text", text);
            elementJSONObject.put("iconId", iconId);

            if (type == Type.RANGE_BUTTON && range != null) {
                elementJSONObject.put("range", range.name());
                if (orientation != 0) elementJSONObject.put("orientation", orientation);
                if (scrollLocked) elementJSONObject.put("scrollLocked", true);
            }

            if (type == Type.SHOOTER_MODE) {
                elementJSONObject.put("shooterMovementType", shooterMovementType);
                elementJSONObject.put("shooterLookType", shooterLookType);
                elementJSONObject.put("shooterLookSensitivity", (double) shooterLookSensitivity);
                elementJSONObject.put("shooterJoystickSize", (double) shooterJoystickSize);
            }

            if (buttonColor != DEFAULT_BUTTON_COLOR) elementJSONObject.put("buttonColor", formatRgbColor(buttonColor));
            if (buttonActiveColorCustom || buttonActiveColor != DEFAULT_BUTTON_ACTIVE_COLOR) elementJSONObject.put("buttonActiveColor", formatRgbColor(buttonActiveColor));
            if (buttonOpacity >= 0) elementJSONObject.put("buttonOpacity", (double)buttonOpacity);
            if (buttonStrokeScale != DEFAULT_BUTTON_STROKE_SCALE) elementJSONObject.put("buttonStrokeScale", (double)buttonStrokeScale);
            if (type == Type.BUTTON && !shooterLookThrough) elementJSONObject.put("shooterLookThrough", false);

            return elementJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    public boolean containsPoint(float x, float y) {
        return getBoundingBox().contains((int)(x + 0.5f), (int)(y + 0.5f));
    }

    private boolean isKeepButtonPressedAfterMinTime() {
        Binding binding = getBindingAt(0);
        return !toggleSwitch && (binding == Binding.GAMEPAD_BUTTON_L3 || binding == Binding.GAMEPAD_BUTTON_R3);
    }

    public boolean handleTouchDown(int pointerId, float x, float y) {
        if (currentPointerId == -1 && containsPoint(x, y)) {
            currentPointerId = pointerId;
            inputControlsView.invalidate();
            if (type == Type.BUTTON) {
                if (isKeepButtonPressedAfterMinTime()) touchTime = System.currentTimeMillis();
                if (!toggleSwitch || !selected) {
                    inputControlsView.handleInputEvent(getBindingAt(0), true);
                    inputControlsView.handleInputEvent(getBindingAt(1), true);
                }
                inputControlsView.invalidate();
                return true;
            }
            else if (type == Type.SHOOTER_MODE) {
                // Toggle handled on touch up
                return true;
            }
            else if (type == Type.RANGE_BUTTON) {
                scroller.handleTouchDown(x, y);
                return true;
            }
            else {
                if (type == Type.TRACKPAD) {
                    if (currentPosition == null) currentPosition = new PointF();
                    currentPosition.set(x, y);
                }
                return handleTouchMove(pointerId, x, y);
            }
        }
        else return false;
    }

    public boolean handleTouchMove(int pointerId, float x, float y) {
        if (pointerId == currentPointerId && (type == Type.BUTTON || type == Type.SHOOTER_MODE)) {
            return true;
        }

        if (pointerId == currentPointerId && (type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD)) {
            float deltaX, deltaY;
            Rect boundingBox = getBoundingBox();
            float radius = boundingBox.width() * 0.5f;
            TouchpadView touchpadView =  inputControlsView.getTouchpadView();

            if (type == Type.TRACKPAD) {
                if (currentPosition == null) currentPosition = new PointF();
                float[] deltaPoint = touchpadView.computeDeltaPoint(currentPosition.x, currentPosition.y, x, y);
                deltaX = deltaPoint[0];
                deltaY = deltaPoint[1];
                currentPosition.set(x, y);
            }
            else {
                float localX = x - boundingBox.left;
                float localY = y - boundingBox.top;
                float offsetX = localX - radius;
                float offsetY = localY - radius;

                float distance = Mathf.lengthSq(radius - localX, radius - localY);
                if (distance > radius * radius) {
                    float angle = (float)Math.atan2(offsetY, offsetX);
                    offsetX = (float)(Math.cos(angle) * radius);
                    offsetY = (float)(Math.sin(angle) * radius);
                }

                deltaX = Mathf.clamp(offsetX / radius, -1, 1);
                deltaY = Mathf.clamp(offsetY / radius, -1, 1);
            }

            if (type == Type.STICK) {
                if (currentPosition == null) currentPosition = new PointF();
                currentPosition.x = boundingBox.left + deltaX * radius + radius;
                currentPosition.y = boundingBox.top + deltaY * radius + radius;
                final boolean[] states = {deltaY <= -STICK_DEAD_ZONE, deltaX >= STICK_DEAD_ZONE, deltaY >= STICK_DEAD_ZONE, deltaX <= -STICK_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        value = Mathf.clamp(Math.max(0, Math.abs(value) - 0.01f) * Mathf.sign(value) * STICK_SENSITIVITY, -1, 1);
                        inputControlsView.handleInputEvent(binding, true, value);
                        this.states[i] = true;
                    }
                    else {
                        boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                        inputControlsView.handleInputEvent(binding, state, value);
                        this.states[i] = state;
                    }
                }

                inputControlsView.invalidate();
            }
            else if (type == Type.TRACKPAD) {
                final boolean[] states = {deltaY <= -TRACKPAD_MIN_SPEED, deltaX >= TRACKPAD_MIN_SPEED, deltaY >= TRACKPAD_MIN_SPEED, deltaX <= -TRACKPAD_MIN_SPEED};
                int cursorDx = 0;
                int cursorDy = 0;

                for (byte i = 0; i < 4; i++) {
                    float value = (i == 1 || i == 3 ? deltaX : deltaY);
                    Binding binding = getBindingAt(i);
                    if (binding.isGamepad()) {
                        if (interpolator == null) interpolator = new CubicBezierInterpolator();
                        if (Math.abs(value) > TRACKPAD_ACCELERATION_THRESHOLD) value *= STICK_SENSITIVITY;
                        interpolator.set(0.075f, 0.95f, 0.45f, 0.95f);
                        float interpolatedValue = interpolator.getInterpolation(Math.min(1.0f, Math.abs(value / TRACKPAD_MAX_SPEED)));
                        inputControlsView.handleInputEvent(binding, true, Mathf.clamp(interpolatedValue * Mathf.sign(value), -1, 1));
                        this.states[i] = true;
                    }
                    else {
                        if (Math.abs(value) > TouchpadView.CURSOR_ACCELERATION_THRESHOLD) value *= TouchpadView.CURSOR_ACCELERATION;
                        if (binding == Binding.MOUSE_MOVE_LEFT || binding == Binding.MOUSE_MOVE_RIGHT) {
                            cursorDx = Mathf.roundPoint(value);
                        }
                        else if (binding == Binding.MOUSE_MOVE_UP || binding == Binding.MOUSE_MOVE_DOWN) {
                            cursorDy = Mathf.roundPoint(value);
                        }
                        else {
                            inputControlsView.handleInputEvent(binding, states[i], value);
                            this.states[i] = states[i];
                        }
                    }
                }

                if (cursorDx != 0 || cursorDy != 0) inputControlsView.getXServer().injectPointerMoveDelta(cursorDx, cursorDy);
            }
            else {
                final boolean[] states = {deltaY <= -DPAD_DEAD_ZONE, deltaX >= DPAD_DEAD_ZONE, deltaY >= DPAD_DEAD_ZONE, deltaX <= -DPAD_DEAD_ZONE};

                for (byte i = 0; i < 4; i++) {
                    float value = i == 1 || i == 3 ? deltaX : deltaY;
                    Binding binding = getBindingAt(i);
                    boolean state = binding.isMouseMove() ? (states[i] || states[(i+2)%4]) : states[i];
                    inputControlsView.handleInputEvent(binding, state, value);
                    this.states[i] = state;
                }

                inputControlsView.invalidate();
            }

            return true;
        }
        else if (pointerId == currentPointerId && type == Type.RANGE_BUTTON) {
            scroller.handleTouchMove(x, y);
            return true;
        }
        else return false;
    }

    public boolean handleTouchUp(int pointerId) {
        if (pointerId == currentPointerId) {
            if (type == Type.BUTTON) {
                if (isKeepButtonPressedAfterMinTime() && touchTime != null) {
                    selected = (System.currentTimeMillis() - (long)touchTime) > BUTTON_MIN_TIME_TO_KEEP_PRESSED;
                    if (!selected) {
                        inputControlsView.handleInputEvent(getBindingAt(0), false);
                        inputControlsView.handleInputEvent(getBindingAt(1), false);
                    }
                    touchTime = null;
                    inputControlsView.invalidate();
                }
                else if (!toggleSwitch || selected) {
                    inputControlsView.handleInputEvent(getBindingAt(0), false);
                    inputControlsView.handleInputEvent(getBindingAt(1), false);
                }

                if (toggleSwitch) {
                    selected = !selected;
                    inputControlsView.invalidate();
                }
                else {
                    inputControlsView.invalidate();
                }
            }
            else if (type == Type.RANGE_BUTTON || type == Type.D_PAD || type == Type.STICK || type == Type.TRACKPAD) {
                for (byte i = 0; i < states.length; i++) {
                    if (states[i]) inputControlsView.handleInputEvent(getBindingAt(i), false);
                    states[i] = false;
                }

                if (type == Type.RANGE_BUTTON) {
                    scroller.handleTouchUp();
                }
                else if (type == Type.STICK) {
                    inputControlsView.invalidate();
                }

                if (currentPosition != null) currentPosition = null;
            }
            else if (type == Type.SHOOTER_MODE) {
                selected = !selected;
                inputControlsView.setShooterModeActive(selected);
                inputControlsView.invalidate();
            }
            currentPointerId = -1;
            inputControlsView.invalidate();
            return true;
        }
        return false;
    }
}
