package com.winlator.inputcontrols;

import android.view.InputDevice;
import android.view.KeyEvent;

import java.util.Collection;

/** Compatibility helpers for Nintendo Switch Joy-Con halves. */
public final class JoyConSupport {
    public static final int NINTENDO_VENDOR_ID = 0x057e;
    public static final int JOY_CON_LEFT_PRODUCT_ID = 0x2006;
    public static final int JOY_CON_RIGHT_PRODUCT_ID = 0x2007;
    public static final String PAIRED_IDENTIFIER = "nintendo_joycon_pair";

    private JoyConSupport() {}

    public static boolean isJoyCon(InputDevice device) {
        return device != null && isJoyCon(device.getVendorId(), device.getProductId());
    }

    static boolean isJoyCon(int vendorId, int productId) {
        return vendorId == NINTENDO_VENDOR_ID
                && (productId == JOY_CON_LEFT_PRODUCT_ID || productId == JOY_CON_RIGHT_PRODUCT_ID);
    }

    /** Returns true only when the connected topology contains one left and one right Joy-Con. */
    public static boolean hasExactlyOnePair(Collection<int[]> connectedDevices) {
        int leftCount = 0;
        int rightCount = 0;
        for (int[] ids : connectedDevices) {
            if (ids == null || ids.length < 2 || ids[0] != NINTENDO_VENDOR_ID) continue;
            if (ids[1] == JOY_CON_LEFT_PRODUCT_ID) leftCount++;
            if (ids[1] == JOY_CON_RIGHT_PRODUCT_ID) rightCount++;
        }
        return leftCount == 1 && rightCount == 1;
    }

    public static int remapKeyCode(InputDevice device, KeyEvent event) {
        if (device == null || event == null) {
            return event != null ? event.getKeyCode() : KeyEvent.KEYCODE_UNKNOWN;
        }
        return remapKeyCode(
                device.getVendorId(), device.getProductId(), event.getScanCode(), event.getKeyCode());
    }

    static int remapKeyCode(int vendorId, int productId, int scanCode, int fallbackKeyCode) {
        if (vendorId != NINTENDO_VENDOR_ID) return fallbackKeyCode;
        if (productId == JOY_CON_LEFT_PRODUCT_ID) {
            switch (scanCode) {
                case 544: return KeyEvent.KEYCODE_DPAD_UP;
                case 545: return KeyEvent.KEYCODE_DPAD_DOWN;
                case 546: return KeyEvent.KEYCODE_DPAD_LEFT;
                case 547: return KeyEvent.KEYCODE_DPAD_RIGHT;
                case 309: return KeyEvent.KEYCODE_BUTTON_MODE;
                case 310: return KeyEvent.KEYCODE_BUTTON_L1;
                case 312: return KeyEvent.KEYCODE_BUTTON_L2;
                case 314: return KeyEvent.KEYCODE_BUTTON_SELECT;
                case 317: return KeyEvent.KEYCODE_BUTTON_THUMBL;
                default: return fallbackKeyCode;
            }
        }
        if (productId == JOY_CON_RIGHT_PRODUCT_ID) {
            switch (scanCode) {
                case 304: return KeyEvent.KEYCODE_BUTTON_A;
                case 305: return KeyEvent.KEYCODE_BUTTON_B;
                case 307: return KeyEvent.KEYCODE_BUTTON_Y;
                case 308: return KeyEvent.KEYCODE_BUTTON_X;
                case 311: return KeyEvent.KEYCODE_BUTTON_R1;
                case 313: return KeyEvent.KEYCODE_BUTTON_R2;
                case 315: return KeyEvent.KEYCODE_BUTTON_START;
                case 316: return KeyEvent.KEYCODE_BUTTON_MODE;
                case 318: return KeyEvent.KEYCODE_BUTTON_THUMBR;
                default: return fallbackKeyCode;
            }
        }
        return fallbackKeyCode;
    }

    static float axisValue(boolean reported, float retained, float current) {
        return reported ? current : retained;
    }
}
