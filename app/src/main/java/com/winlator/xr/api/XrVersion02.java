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

public class XrVersion02 extends XrVersion01 {

    public XrVersion02() {
        super(null);
    }

    @Override
    public void dataReceived(PortIntent intent, @NonNull String message) {
        if (intent == PortIntent.HMD_STATE) {
            try {
                String[] parts = message.split("\\s+");
                for (int i = 0; i < parts.length; i++) {
                    float value = Float.parseFloat(parts[i]);
                    if ((value > 0) || (i >= 2)) {
                        input[i] = value;
                    }
                }
            } catch (NumberFormatException e) {
                System.err.println("Error parsing float values: " + e.getMessage());
            }
        }
    }

    @Override
    public float getValue(@NonNull AppInput index) {
        return input[index.ordinal()];
    }
}
