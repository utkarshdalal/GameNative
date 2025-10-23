package com.winlator.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.Iterator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.winlator.PrefManager;
import com.winlator.winhandler.WinHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class ExternalController {
    public static final float STICK_DEAD_ZONE = 0.15f;
    public static final byte IDX_BUTTON_A = 0;
    public static final byte IDX_BUTTON_B = 1;
    public static final byte IDX_BUTTON_X = 2;
    public static final byte IDX_BUTTON_Y = 3;
    public static final byte IDX_BUTTON_L1 = 4;
    public static final byte IDX_BUTTON_R1 = 5;
    public static final byte IDX_BUTTON_SELECT = 6;
    public static final byte IDX_BUTTON_START = 7;
    public static final byte IDX_BUTTON_L3 = 8;
    public static final byte IDX_BUTTON_R3 = 9;
    public static final byte IDX_BUTTON_L2 = 10;
    public static final byte IDX_BUTTON_R2 = 11;
    public static final byte TRIGGER_IS_BUTTON = 0;
    public static final byte TRIGGER_IS_AXIS = 1;
    public static final byte TRIGGER_IS_BOTH = 2;

    private String id;
    private String name;
    private int deviceId = -1;
    private byte triggerType = TRIGGER_IS_AXIS;
    private final ArrayList<ExternalControllerBinding> controllerBindings = new ArrayList<>();
    public final GamepadState state = new GamepadState();
    private boolean processTriggerButtonOnMotionEvent = true;

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public byte getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(byte mode) {
        triggerType = mode;
    }

    private Context context;

    public void setContext(Context context) {
        this.context = context;
        PrefManager.init(context);
    }


    public int getDeviceId() {
        if (this.deviceId == -1) {
            int[] deviceIds = InputDevice.getDeviceIds();
            int length = deviceIds.length;
            int i = 0;
            while (true) {
                if (i < length) {
                    int deviceId = deviceIds[i];
                InputDevice device = InputDevice.getDevice(deviceId);
                    if (device == null || !device.getDescriptor().equals(this.id)) {
                        i++;
                    } else {
                    this.deviceId = deviceId;
                    break;
                    }
                } else {
                    break;
                }
            }
        }
        return this.deviceId;
    }

    public boolean isConnected() {
        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.getDescriptor().equals(this.id)) {
                return true;
            }
        }
        return false;
    }

    public ExternalControllerBinding getControllerBinding(int keyCode) {
        Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
        while (it.hasNext()) {
            ExternalControllerBinding controllerBinding = it.next();
            if (controllerBinding.getKeyCodeForAxis() == keyCode) {
                return controllerBinding;
            }
        }
        return null;
    }

    public ExternalControllerBinding getControllerBindingAt(int index) {
        return this.controllerBindings.get(index);
    }

    public void addControllerBinding(ExternalControllerBinding controllerBinding) {
        if (getControllerBinding(controllerBinding.getKeyCodeForAxis()) == null) {
            this.controllerBindings.add(controllerBinding);
        }
    }

    public int getPosition(ExternalControllerBinding controllerBinding) {
        return this.controllerBindings.indexOf(controllerBinding);
    }

    public void removeControllerBinding(ExternalControllerBinding controllerBinding) {
        this.controllerBindings.remove(controllerBinding);
    }

    public int getControllerBindingCount() {
        return this.controllerBindings.size();
    }

    public JSONObject toJSONObject() {
        try {
            if (this.controllerBindings.isEmpty()) {
                return null;
            }
            JSONObject controllerJSONObject = new JSONObject();
            controllerJSONObject.put("id", this.id);
            controllerJSONObject.put("name", this.name);
            JSONArray controllerBindingsJSONArray = new JSONArray();
            Iterator<ExternalControllerBinding> it = this.controllerBindings.iterator();
            while (it.hasNext()) {
                ExternalControllerBinding controllerBinding = it.next();
                controllerBindingsJSONArray.put(controllerBinding.toJSONObject());
            }
            controllerJSONObject.put("controllerBindings", controllerBindingsJSONArray);
            return controllerJSONObject;
        } catch (JSONException e) {
            return null;
        }
    }

    public boolean equals(Object obj) {
        return obj instanceof ExternalController ? ((ExternalController)obj).id.equals(this.id) : super.equals(obj);
    }
    @NonNull
    @Override
    public String toString() {
        return getDeviceId() + " | " + getName();
    }

    private void processJoystickInput(MotionEvent event, int historyPos) {
        boolean z = false;
        this.state.thumbLX = getCenteredAxis(event, MotionEvent.AXIS_X, historyPos);
        this.state.thumbLY = getCenteredAxis(event, MotionEvent.AXIS_Y, historyPos);
        this.state.thumbRX = getCenteredAxis(event, MotionEvent.AXIS_Z, historyPos);
        this.state.thumbRY = getCenteredAxis(event, MotionEvent.AXIS_RZ, historyPos);
        if (historyPos == -1) {
            float axisX = getCenteredAxis(event, MotionEvent.AXIS_HAT_X, historyPos);
            float axisY = getCenteredAxis(event, MotionEvent.AXIS_HAT_Y, historyPos);
            GamepadState gamepadState = this.state;
            gamepadState.dpad[0] = axisY == -1.0f && Math.abs(gamepadState.thumbLY) < STICK_DEAD_ZONE;
            GamepadState gamepadState2 = this.state;
            gamepadState2.dpad[1] = axisX == 1.0f && Math.abs(gamepadState2.thumbLX) < STICK_DEAD_ZONE;
            GamepadState gamepadState3 = this.state;
            gamepadState3.dpad[2] = axisY == 1.0f && Math.abs(gamepadState3.thumbLY) < STICK_DEAD_ZONE;
            GamepadState gamepadState4 = this.state;
            boolean[] zArr = gamepadState4.dpad;
            if (axisX == -1.0f && Math.abs(gamepadState4.thumbLX) < STICK_DEAD_ZONE) {
                z = true;
            }
            zArr[3] = z;
        }
    }

    private void processTriggerButton(MotionEvent event) {
        float l = event.getAxisValue(event.getAxisValue(MotionEvent.AXIS_LTRIGGER) == 0.0f ? MotionEvent.AXIS_BRAKE : MotionEvent.AXIS_LTRIGGER);
        float r = event.getAxisValue(event.getAxisValue(MotionEvent.AXIS_RTRIGGER) == 0.0f ? MotionEvent.AXIS_GAS : MotionEvent.AXIS_RTRIGGER);
        l = (l <= 0.01f) ? 0.0f : Math.min(1.0f, Math.max(0.0f, l));
        r = (r <= 0.01f) ? 0.0f : Math.min(1.0f, Math.max(0.0f, r));
        this.state.triggerL = l;
        this.state.triggerR = r;
        this.state.setPressed(IDX_BUTTON_L2, l > 0.0f);
        this.state.setPressed(IDX_BUTTON_R2, r > 0.0f);
    }

    public boolean updateStateFromMotionEvent(MotionEvent event) {
        if (isJoystickDevice(event)) {
            if (this.processTriggerButtonOnMotionEvent) {
                processTriggerButton(event);
            }
            int historySize = event.getHistorySize();
            for (int i = 0; i < historySize; i++) {
                processJoystickInput(event, i);
            }
            processJoystickInput(event, -1);
            return true;
        }
        return false;
    }

    public boolean updateStateFromKeyEvent(KeyEvent event) {
        boolean z = false;
        boolean pressed = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        int buttonIdx = getButtonIdxByKeyCode(keyCode);
        if (buttonIdx != -1) {
            if (buttonIdx == IDX_BUTTON_L2 || buttonIdx == IDX_BUTTON_R2) {
                this.processTriggerButtonOnMotionEvent = false;
            }
            this.state.setPressed(buttonIdx, pressed);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                GamepadState gamepadState = this.state;
                gamepadState.dpad[0] = pressed && Math.abs(gamepadState.thumbLY) < STICK_DEAD_ZONE;
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                GamepadState gamepadState2 = this.state;
                boolean[] zArr = gamepadState2.dpad;
                if (pressed && Math.abs(gamepadState2.thumbLY) < STICK_DEAD_ZONE) {
                    z = true;
                }
                zArr[2] = z;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                GamepadState gamepadState3 = this.state;
                boolean[] zArr2 = gamepadState3.dpad;
                if (pressed && Math.abs(gamepadState3.thumbLX) < STICK_DEAD_ZONE) {
                    z = true;
                }
                zArr2[3] = z;
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                GamepadState gamepadState4 = this.state;
                boolean[] zArr3 = gamepadState4.dpad;
                if (pressed && Math.abs(gamepadState4.thumbLX) < STICK_DEAD_ZONE) {
                    z = true;
                }
                zArr3[1] = z;
                return true;
            default:
                return false;
        }
    }

    public static ArrayList<ExternalController> getControllers() {
        int[] deviceIds = InputDevice.getDeviceIds();
        ArrayList<ExternalController> controllers = new ArrayList<>();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            InputDevice device = InputDevice.getDevice(deviceIds[i]);
            if (isGameController(device)) {
                ExternalController controller = new ExternalController();
                controller.setId(device.getDescriptor());
                controller.setName(device.getName());
                controllers.add(controller);
            }
        }
        return controllers;
    }

    public static ExternalController getController(String id) {
        Iterator<ExternalController> it = getControllers().iterator();
        while (it.hasNext()) {
            ExternalController controller = it.next();
            if (controller.getId().equals(id)) {
                return controller;
            }
        }
        return null;
    }

    public static ExternalController getController(int deviceId) {
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int i = deviceIds.length-1; i >= 0; i--) {
            if (deviceIds[i] == deviceId || deviceId == 0) {
                InputDevice device = InputDevice.getDevice(deviceIds[i]);
                if (isGameController(device)) {
                    ExternalController controller = new ExternalController();
                    controller.setId(device.getDescriptor());
                    controller.setName(device.getName());
                    controller.deviceId = deviceIds[i];
                    return controller;
                }
            }
        }
        return null;
    }

    public static boolean isGameController(InputDevice device) {
        if (device == null) {
            return false;
        }
        int sources = device.getSources();
        if (device.isVirtual()) {
            return false;
    }
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    public static float getCenteredAxis(MotionEvent event, int axis, int historyPos) {
        if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
            float value = event.getAxisValue(axis);
            if (Math.abs(value) == 1.0f) {
                return value;
        }
            return 0.0f;
        }
        InputDevice device = event.getDevice();
        InputDevice.MotionRange range = device.getMotionRange(axis, event.getSource());
        if (range != null) {
            float flat = range.getFlat();
            float value2 = historyPos < 0 ? event.getAxisValue(axis) : event.getHistoricalAxisValue(axis, historyPos);
            if (Math.abs(value2) > flat) {
                return value2;
            }
            return 0.0f;
        }
        return 0.0f;
    }

    public static boolean isJoystickDevice(MotionEvent event) {
        return (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.getAction() == MotionEvent.ACTION_MOVE;
    }

    public static int getButtonIdxByKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                return IDX_BUTTON_A;
            case KeyEvent.KEYCODE_BUTTON_B:
                return IDX_BUTTON_B;
            case KeyEvent.KEYCODE_BUTTON_C:
            case KeyEvent.KEYCODE_BUTTON_Z:
            default:
                return -1;
            case KeyEvent.KEYCODE_BUTTON_X:
                return IDX_BUTTON_X;
            case KeyEvent.KEYCODE_BUTTON_Y:
                return IDX_BUTTON_Y;
            case KeyEvent.KEYCODE_BUTTON_L1:
                return IDX_BUTTON_L1;
            case KeyEvent.KEYCODE_BUTTON_R1:
                return IDX_BUTTON_R1;
            case KeyEvent.KEYCODE_BUTTON_L2:
                return IDX_BUTTON_L2;
            case KeyEvent.KEYCODE_BUTTON_R2:
                return IDX_BUTTON_R2;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return IDX_BUTTON_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
                return IDX_BUTTON_R3;
            case KeyEvent.KEYCODE_BUTTON_START:
                return IDX_BUTTON_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return IDX_BUTTON_SELECT;
        }
    }
}
