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
package com.winlator.xr.api;

import androidx.annotation.NonNull;

import com.winlator.xserver.XServer;

public interface XrInterface {

    enum AppInput {
        L_HAPTICS, R_HAPTICS, MODE_VR, MODE_3D, HMD_FOVX, HMD_FOVY
    }

    // Order of the enum has to be the as in libxr.so
    enum ControllerAxis {
        L_PITCH, L_YAW, L_ROLL, L_QX, L_QY, L_QZ, L_QW, L_THUMBSTICK_X, L_THUMBSTICK_Y, L_X, L_Y, L_Z,
        R_PITCH, R_YAW, R_ROLL, R_QX, R_QY, R_QZ, R_QW, R_THUMBSTICK_X, R_THUMBSTICK_Y, R_X, R_Y, R_Z,
        HMD_PITCH, HMD_YAW, HMD_ROLL, HMD_QX, HMD_QY, HMD_QZ, HMD_QW, HMD_X, HMD_Y, HMD_Z,
        HMD_IPD, HMD_FOVX, HMD_FOVY, HMD_SYNC
    }

    // Order of the enum has to be the as in libxr.so
    enum ControllerButton {
        L_GRIP,  L_MENU, L_THUMBSTICK_PRESS, L_THUMBSTICK_LEFT, L_THUMBSTICK_RIGHT, L_THUMBSTICK_UP, L_THUMBSTICK_DOWN, L_TRIGGER, L_X, L_Y,
        R_A, R_B, R_GRIP, R_THUMBSTICK_PRESS, R_THUMBSTICK_LEFT, R_THUMBSTICK_RIGHT, R_THUMBSTICK_UP, R_THUMBSTICK_DOWN, R_TRIGGER,
    }

    enum PortIntent {
        HMD_STATE,
        XSERVER_INPUT
    }

    void consumeInputs(XServer xServer);
    void dataReceived(PortIntent intent, @NonNull String message);
    String encode(@NonNull float[] axes, @NonNull boolean[] buttons, int clientIndex);
    String getFlags();
    int getPortIn(PortIntent intent);
    int[] getPortsOut();
    float getValue(@NonNull AppInput index);
    void setValue(@NonNull AppInput index, float value);
}
