package com.winlator.inputcontrols

import android.view.InputDevice
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that [ControllerManager.getDeviceIdentifier] returns the correct
 * identifier format per API level. Pre-Q (< 29) must use "vendor_X_product_Y";
 * Q+ must use the device descriptor. Changing this breaks persisted slot
 * assignments for existing installs.
 */
@RunWith(RobolectricTestRunner::class)
class ControllerManagerTest {

    @Test
    @Config(sdk = [28])
    fun `pre-Q device uses vendor_product format`() {
        val device = mockk<InputDevice> {
            every { vendorId } returns 0x045E
            every { productId } returns 0x028E
        }
        val id = ControllerManager.getDeviceIdentifier(device)
        assertEquals("vendor_1118_product_654", id)
    }

    @Test
    @Config(sdk = [29])
    fun `Q device uses descriptor`() {
        val descriptor = "usb-0000:00:14.0-2/input0"
        val device = mockk<InputDevice> {
            every { getDescriptor() } returns descriptor
        }
        val id = ControllerManager.getDeviceIdentifier(device)
        assertEquals(descriptor, id)
    }

    @Test
    @Config(sdk = [34])
    fun `post-Q device uses descriptor`() {
        val descriptor = "bluetooth-AB:CD:EF:12:34:56-input0"
        val device = mockk<InputDevice> {
            every { getDescriptor() } returns descriptor
        }
        val id = ControllerManager.getDeviceIdentifier(device)
        assertEquals(descriptor, id)
    }

    @Test
    fun `null device returns null`() {
        assertNull(ControllerManager.getDeviceIdentifier(null))
    }
}
