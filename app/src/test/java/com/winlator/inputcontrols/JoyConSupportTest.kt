package com.winlator.inputcontrols

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoyConSupportTest {
    @Test
    fun `shares identity only for exactly one left and one right Joy-Con`() {
        assertTrue(JoyConSupport.hasExactlyOnePair(listOf(
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_LEFT_PRODUCT_ID),
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID),
        )))
        assertFalse(JoyConSupport.hasExactlyOnePair(listOf(
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_LEFT_PRODUCT_ID),
        )))
        assertFalse(JoyConSupport.hasExactlyOnePair(listOf(
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_LEFT_PRODUCT_ID),
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_LEFT_PRODUCT_ID),
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID),
        )))
        assertFalse(JoyConSupport.hasExactlyOnePair(listOf(
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_LEFT_PRODUCT_ID),
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID),
            ids(JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID),
        )))
    }

    @Test
    fun `maps Joy-Con Linux scan codes and passes other keys through`() {
        mapOf(
            544 to KeyEvent.KEYCODE_DPAD_UP,
            545 to KeyEvent.KEYCODE_DPAD_DOWN,
            546 to KeyEvent.KEYCODE_DPAD_LEFT,
            547 to KeyEvent.KEYCODE_DPAD_RIGHT,
            309 to KeyEvent.KEYCODE_BUTTON_MODE,
            310 to KeyEvent.KEYCODE_BUTTON_L1,
            312 to KeyEvent.KEYCODE_BUTTON_L2,
            314 to KeyEvent.KEYCODE_BUTTON_SELECT,
            317 to KeyEvent.KEYCODE_BUTTON_THUMBL,
        ).forEach { (scanCode, expected) ->
            assertEquals(expected, JoyConSupport.remapKeyCode(
                JoyConSupport.NINTENDO_VENDOR_ID,
                JoyConSupport.JOY_CON_LEFT_PRODUCT_ID,
                scanCode,
                KeyEvent.KEYCODE_UNKNOWN,
            ))
        }

        mapOf(
            304 to KeyEvent.KEYCODE_BUTTON_A,
            305 to KeyEvent.KEYCODE_BUTTON_B,
            307 to KeyEvent.KEYCODE_BUTTON_Y,
            308 to KeyEvent.KEYCODE_BUTTON_X,
            311 to KeyEvent.KEYCODE_BUTTON_R1,
            313 to KeyEvent.KEYCODE_BUTTON_R2,
            315 to KeyEvent.KEYCODE_BUTTON_START,
            316 to KeyEvent.KEYCODE_BUTTON_MODE,
            318 to KeyEvent.KEYCODE_BUTTON_THUMBR,
        ).forEach { (scanCode, expected) ->
            assertEquals(expected, JoyConSupport.remapKeyCode(
                JoyConSupport.NINTENDO_VENDOR_ID,
                JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID,
                scanCode,
                KeyEvent.KEYCODE_UNKNOWN,
            ))
        }

        assertEquals(KeyEvent.KEYCODE_BUTTON_X, JoyConSupport.remapKeyCode(
            0x045e, 0x0b13, 308, KeyEvent.KEYCODE_BUTTON_X,
        ))
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, JoyConSupport.remapKeyCode(
            JoyConSupport.NINTENDO_VENDOR_ID, JoyConSupport.JOY_CON_RIGHT_PRODUCT_ID,
            999, KeyEvent.KEYCODE_BUTTON_Y,
        ))
    }

    @Test
    fun `missing axes preserve retained values`() {
        assertEquals(0.65f, JoyConSupport.axisValue(false, 0.65f, 0f), 0f)
        assertEquals(-0.4f, JoyConSupport.axisValue(true, 0.65f, -0.4f), 0f)
    }

    private fun ids(vendorId: Int, productId: Int) = intArrayOf(vendorId, productId)
}
