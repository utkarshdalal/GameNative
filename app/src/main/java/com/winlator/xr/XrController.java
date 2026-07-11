/*
 * Copyright (C) 2024-2026 WinlatorXR
 *
 * This file is part of WinlatorXR.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.winlator.xr;

import android.content.Intent;
import android.util.Pair;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import app.gamenative.ui.XrMenuBridge;

import com.winlator.xr.api.XrAPI;
import com.winlator.xr.api.XrInterface;
import com.winlator.xr.ui.XrContentDialog;
import com.winlator.xr.ui.XrDialog;
import com.winlator.xserver.Keyboard;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XKeycode;

import java.util.Vector;

public class XrController {

    private final XrActivity instance;
    private boolean[] currentButtons = new boolean[XrInterface.ControllerButton.values().length];
    private final float[] lastAxes = new float[XrInterface.ControllerAxis.values().length];
    private final boolean[] lastButtons = new boolean[XrInterface.ControllerButton.values().length];
    private long lastDialogShown = 0;
    private int inputTick = 0;
    private long lastMouseUpdate = 0;
    private short lastMouseX = 0;
    private short lastMouseY = 0;
    private float mouseSpeed = 1;
    private final float[] smoothedMouse = new float[2];

    // Controller haptics
    private final float[] lastVibration = new float[2];

    public XrController() {
        instance = XrActivity.getInstance();
        mouseSpeed = PreferenceManager.getDefaultSharedPreferences(instance).getFloat("cursor_speed", 1.0f);
    }

    public void unload() {
    }

    public boolean updateAndroidInput(boolean[] buttons) {
        // DIAG heartbeat: prove updateAndroidInput is being called even when no button is down, and
        // report how many buttons the OpenXR runtime is reporting as pressed this frame.
        if ((++inputTick % 90) == 0) {
            int pressed = 0;
            for (boolean b : buttons) if (b) pressed++;
            android.util.Log.i("XrDiag", "input alive tick=" + inputTick + " len=" + buttons.length
                    + " pressed=" + pressed + " menuOpen=" + XrMenuBridge.menuOpen);
        }
        // DIAG: which controller buttons are actually reaching us (index into ControllerButton enum)
        StringBuilder dbg = new StringBuilder();
        for (int i = 0; i < buttons.length; i++) if (buttons[i]) dbg.append(i).append(",");
        if (dbg.length() > 0) {
            android.util.Log.i("XrDiag", "buttons=[" + dbg + "] R_THUMBSTICK_PRESS idx="
                    + XrInterface.ControllerButton.R_THUMBSTICK_PRESS.ordinal());
        }
        // Get OpenXR input
        XrInterface.ControllerButton primaryPress = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_PRESS : XrInterface.ControllerButton.R_THUMBSTICK_PRESS;
        XrInterface.ControllerButton primaryTrigger = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryUp = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton primaryLeft = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Menu open: route the controller to the REAL Compose QuickMenu via key events (Compose
        // focus nav) and don't drive the game. Primary press (thumbstick click) closes it.
        if (XrMenuBridge.menuOpen) {
            final java.util.function.IntConsumer sendKey = XrMenuBridge.sendKey;
            final Runnable toggle = XrMenuBridge.toggleMenu;
            if (getButtonClicked(buttons, primaryPress) && toggle != null) {
                instance.runOnUiThread(toggle);
            } else if (sendKey != null) {
                if (getButtonClicked(buttons, primaryUp)) instance.runOnUiThread(() -> sendKey.accept(KeyEvent.KEYCODE_DPAD_UP));
                if (getButtonClicked(buttons, primaryDown)) instance.runOnUiThread(() -> sendKey.accept(KeyEvent.KEYCODE_DPAD_DOWN));
                if (getButtonClicked(buttons, primaryLeft)) instance.runOnUiThread(() -> sendKey.accept(KeyEvent.KEYCODE_DPAD_LEFT));
                if (getButtonClicked(buttons, primaryRight)) instance.runOnUiThread(() -> sendKey.accept(KeyEvent.KEYCODE_DPAD_RIGHT));
                // Activate the focused item: most items are .clickable/.selectable (Compose activates
                // those on DPAD_CENTER/ENTER); the sliders lock/adjust on BUTTON_A. Send both to cover all.
                if (getButtonClicked(buttons, primaryTrigger)) instance.runOnUiThread(() -> {
                    sendKey.accept(KeyEvent.KEYCODE_DPAD_CENTER);
                    sendKey.accept(KeyEvent.KEYCODE_BUTTON_A);
                });
            }
            System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
            return false;
        } else if (getButtonClicked(buttons, primaryPress)) {
            final Runnable toggle = XrMenuBridge.toggleMenu;
            if (toggle != null) {
                instance.runOnUiThread(toggle);
            }
        }

        // Block input shortly after dialog closed
        if (System.currentTimeMillis() - lastDialogShown < 500) {
            System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
            return false;
        }
        // Keep edge-detection correct: without this, lastButtons never advances in the steady
        // no-dialog state, so getButtonClicked() misfires (repeats or never re-arms).
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
        return true;
    }

    public void updateHaptics(XrAPI xrAPI) {
        // Define haptics
        XrInterface.AppInput[] haptics = {XrInterface.AppInput.L_HAPTICS, XrInterface.AppInput.R_HAPTICS};
        for (int i = 0; i < haptics.length; i++) {
            XrInterface.AppInput haptic = haptics[i];
            float value = xrAPI.getValue(haptic);
            if (value > 0.0f) {
                // Controller haptics
                instance.vibrateController(1, i, value);
                xrAPI.setValue(haptic, value - 0.1f);
                lastVibration[i] = value;
            } else {
                xrAPI.setValue(haptic, 0.0f);
                lastVibration[i] = 0.0f;
            }
        }
    }

    public void updateKeyboardButtons(boolean[] buttons) {
        // Get OpenXR input
        XrInterface.ControllerButton secondaryGrip = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton secondaryTrigger = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton secondaryUp = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton secondaryDown = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;
        XrInterface.ControllerButton secondaryLeft = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton secondaryRight = !XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Pass the controller mapping into XServer
        currentButtons = buttons;
        mapKey(XrInterface.ControllerButton.L_MENU, XKeycode.KEY_ESC.getId());
        mapKey(XrInterface.ControllerButton.R_A, (byte)instance.container.getXrButtonA());
        mapKey(XrInterface.ControllerButton.R_B, (byte)instance.container.getXrButtonB());
        mapKey(XrInterface.ControllerButton.L_X, (byte)instance.container.getXrButtonX());
        mapKey(XrInterface.ControllerButton.L_Y, (byte)instance.container.getXrButtonY());
        mapKey(secondaryGrip, (byte)instance.container.getXrButtonGrip());
        mapKey(secondaryTrigger, (byte)instance.container.getXrButtonTrigger());
        mapKey(secondaryUp, (byte)instance.container.getXrThumbstickUp());
        mapKey(secondaryDown, (byte)instance.container.getXrThumbstickDown());
        mapKey(secondaryLeft, (byte)instance.container.getXrThumbstickLeft());
        mapKey(secondaryRight, (byte)instance.container.getXrThumbstickRight());
        System.arraycopy(buttons, 0, lastButtons, 0, buttons.length);
    }

    public void updateMouseAxes(float[] axes, boolean headMapping) {
        // Get OpenXR input
        XrInterface.ControllerAxis mouseAxisX = XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_X : XrInterface.ControllerAxis.R_X;
        XrInterface.ControllerAxis mouseAxisY = XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_Y : XrInterface.ControllerAxis.R_Y;

        // Mouse control with hand
        float f = 0.75f;
        float meter2px = instance.getXServer().screenInfo.width * 10.0f;
        float dx = (axes[mouseAxisX.ordinal()] - lastAxes[mouseAxisX.ordinal()]) * meter2px;
        float dy = (axes[mouseAxisY.ordinal()] - lastAxes[mouseAxisY.ordinal()]) * meter2px;
        if ((Math.abs(dx) > 300) || (Math.abs(dy) > 300)) {
            dx = 0;
            dy = 0;
        }

        // Mouse control with head
        Pointer mouse = instance.getXServer().pointer;
        if (headMapping) {
            float angle2px = instance.getXServer().screenInfo.width * 0.05f / f;
            dx = getAngleDiff(lastAxes[XrInterface.ControllerAxis.HMD_YAW.ordinal()], axes[XrInterface.ControllerAxis.HMD_YAW.ordinal()]) * angle2px;
            dy = getAngleDiff(lastAxes[XrInterface.ControllerAxis.HMD_PITCH.ordinal()], axes[XrInterface.ControllerAxis.HMD_PITCH.ordinal()]) * angle2px;
            if (Float.isNaN(dy)) {
                dy = 0;
            }
            smoothedMouse[0] = mouse.getClampedX() + 0.5f;
            smoothedMouse[1] = mouse.getClampedY() + 0.5f;
        }

        // Mouse smoothing
        dx *= mouseSpeed;
        dy *= mouseSpeed;
        smoothedMouse[0] = smoothedMouse[0] * f + (mouse.getClampedX() + 0.5f + dx) * (1 - f);
        smoothedMouse[1] = smoothedMouse[1] * f + (mouse.getClampedY() + 0.5f - dy) * (1 - f);

        System.arraycopy(axes, 0, lastAxes, 0, axes.length);
    }

    public void updateMouseLightgun(float[] axes, float distance) {
        // Get values
        float x = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_X.ordinal() : XrInterface.ControllerAxis.R_X.ordinal()] - axes[XrInterface.ControllerAxis.HMD_X.ordinal()];;
        float y = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_Y.ordinal() : XrInterface.ControllerAxis.R_Y.ordinal()] - axes[XrInterface.ControllerAxis.HMD_Y.ordinal()];;
        float yaw = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_YAW.ordinal() : XrInterface.ControllerAxis.R_YAW.ordinal()];
        float pitch = axes[XrActivity.mouseLeftHanded ? XrInterface.ControllerAxis.L_PITCH.ordinal() : XrInterface.ControllerAxis.R_PITCH.ordinal()];
        float cx = (float) instance.getXServer().windowManager.rootWindow.getWidth() / 2;
        float cy = (float) instance.getXServer().windowManager.rootWindow.getHeight() / 2;
        float aspect = (float) Math.pow(cx / cy, 0.15);

        //Positional mapping
        float amount = (cx + cy) / 2.0f;
        smoothedMouse[0] = cx + x * amount / aspect;
        smoothedMouse[1] = cy - y * amount;

        //Angular mapping
        amount = distance / 4.0f * (cx + cy) / 2;
        smoothedMouse[0] -= (float) (Math.tan(Math.toRadians(yaw) / aspect) * amount);
        smoothedMouse[1] += (float) (Math.tan(Math.toRadians(pitch)) * amount);
    }

    public void updateMouseSnapturn(boolean[] buttons, int step) {
        // Get OpenXR input
        XrInterface.ControllerButton primaryLeft = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_LEFT : XrInterface.ControllerButton.R_THUMBSTICK_LEFT;
        XrInterface.ControllerButton primaryRight = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_RIGHT : XrInterface.ControllerButton.R_THUMBSTICK_RIGHT;

        // Apply snapturn to the input
        if (getButtonClicked(buttons, primaryLeft)) {
            smoothedMouse[0] -= step;
        }
        if (getButtonClicked(buttons, primaryRight)) {
            smoothedMouse[0] += step;
        }
    }

    public void updateMouseState(boolean[] buttons, float fps) {
        // Get OpenXR input
        Pointer mouse = instance.getXServer().pointer;
        XrInterface.ControllerButton primaryGrip = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_GRIP : XrInterface.ControllerButton.R_GRIP;
        XrInterface.ControllerButton primaryTrigger = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_TRIGGER : XrInterface.ControllerButton.R_TRIGGER;
        XrInterface.ControllerButton primaryUp = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_UP : XrInterface.ControllerButton.R_THUMBSTICK_UP;
        XrInterface.ControllerButton primaryDown = XrActivity.mouseLeftHanded ? XrInterface.ControllerButton.L_THUMBSTICK_DOWN : XrInterface.ControllerButton.R_THUMBSTICK_DOWN;

        // Apply values
        currentButtons = buttons;
        mouse.setX((int) smoothedMouse[0]);
        mouse.setY((int) smoothedMouse[1]);
        mapButton(primaryTrigger, Pointer.Button.BUTTON_LEFT);
        mapButton(primaryGrip, Pointer.Button.BUTTON_RIGHT);
        mapButton(primaryUp, Pointer.Button.BUTTON_SCROLL_UP);
        mapButton(primaryDown, Pointer.Button.BUTTON_SCROLL_DOWN);

        // Limit cursor updates to the FPS (this prevents freezing)
        long timestamp = System.currentTimeMillis();
        if (timestamp - lastMouseUpdate > 1000 / Math.max(fps, 1)) {
            if ((lastMouseX != mouse.getX()) || (lastMouseY != mouse.getY())) {
                lastMouseUpdate = timestamp;
                lastMouseX = mouse.getX();
                lastMouseY = mouse.getY();
                mouse.triggerOnPointerMove(lastMouseX, lastMouseY);
            }
        }
    }

    public boolean getButtonClicked(boolean[] buttons, XrInterface.ControllerButton button) {
        return buttons[button.ordinal()] && !lastButtons[button.ordinal()];
    }

    private float getAngleDiff(float oldAngle, float newAngle) {
        float diff = oldAngle - newAngle;
        while (diff > 180) {
            diff -= 360;
        }
        while (diff < -180) {
            diff += 360;
        }
        return diff;
    }

    private void mapButton(XrInterface.ControllerButton xrButton, Pointer.Button button) {
        Pointer mouse = instance.getXServer().pointer;
        if (currentButtons[xrButton.ordinal()] != lastButtons[xrButton.ordinal()]) {
            mouse.setButton(button, currentButtons[xrButton.ordinal()]);
        }
    }

    private void mapKey(XrInterface.ControllerButton xrButton, byte xKeycode) {
        Keyboard keyboard = instance.getXServer().keyboard;
        if (currentButtons[xrButton.ordinal()] != lastButtons[xrButton.ordinal()]) {
            if (currentButtons[xrButton.ordinal()]) {
                keyboard.setKeyPress(xKeycode, 0);
            } else {
                keyboard.setKeyRelease(xKeycode);
            }
        }
    }
}
