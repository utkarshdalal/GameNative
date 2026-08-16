package app.gamenative.powercontrol.autotuning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for DeviceGate.isDeviceSupported() covering device detection logic and normalization.
 * This gate controls runtime defaults for enableAutoTuning, enableAdaptiveFpsCap,
 * enablePerClusterTuning, enableGamePinning, and enableFanControl in PServerDriver.getDefaultProfile().
 */
class DeviceGateTest {

    @Test
    fun `retroid pocket 6 is supported with exact lowercase match`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid pocket 6"))
    }

    @Test
    fun `retroid pocket 6 is supported with mixed case`() {
        assertTrue(DeviceGate.isDeviceSupported("Retroid Pocket 6"))
    }

    @Test
    fun `retroid pocket 6 is supported with uppercase`() {
        assertTrue(DeviceGate.isDeviceSupported("RETROID POCKET 6"))
    }

    @Test
    fun `retroid pocket 6 is supported with underscores`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid_pocket_6"))
    }

    @Test
    fun `retroid pocket 6 is supported with hyphens`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid-pocket-6"))
    }

    @Test
    fun `retroid pocket 6 is supported with extra whitespace`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid  pocket   6"))
    }

    @Test
    fun `retroid pocket 6 is supported with leading and trailing whitespace`() {
        assertTrue(DeviceGate.isDeviceSupported("  retroid pocket 6  "))
    }

    @Test
    fun `odin3 is supported with exact lowercase match`() {
        assertTrue(DeviceGate.isDeviceSupported("odin3"))
    }

    @Test
    fun `odin3 is supported with mixed case`() {
        assertTrue(DeviceGate.isDeviceSupported("Odin3"))
    }

    @Test
    fun `odin3 is supported with uppercase`() {
        assertTrue(DeviceGate.isDeviceSupported("ODIN3"))
    }

    @Test
    fun `odin_3 is not supported because normalization adds space`() {
        assertFalse(DeviceGate.isDeviceSupported("odin_3"))
    }

    @Test
    fun `odin-3 is not supported because normalization adds space`() {
        assertFalse(DeviceGate.isDeviceSupported("odin-3"))
    }

    @Test
    fun `odin 3 with space is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("odin  3"))
    }

    @Test
    fun `odin3 is supported with leading and trailing whitespace`() {
        assertTrue(DeviceGate.isDeviceSupported("  odin3  "))
    }

    @Test
    fun `odin3 is supported as substring in longer model name`() {
        assertTrue(DeviceGate.isDeviceSupported("AYN Odin3 Pro"))
    }

    @Test
    fun `odin3 is supported with manufacturer prefix`() {
        assertTrue(DeviceGate.isDeviceSupported("AYN_Odin3"))
    }

    @Test
    fun `generic android device is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("Generic Android Device"))
    }

    @Test
    fun `samsung galaxy is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("Samsung Galaxy S23"))
    }

    @Test
    fun `pixel device is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("Pixel 8 Pro"))
    }

    @Test
    fun `empty string is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported(""))
    }

    @Test
    fun `whitespace only is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("   "))
    }

    @Test
    fun `retroid pocket 5 is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("retroid pocket 5"))
    }

    @Test
    fun `odin2 is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("odin2"))
    }

    @Test
    fun `partial match retroid is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("retroid"))
    }

    @Test
    fun `partial match pocket is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("pocket"))
    }

    @Test
    fun `normalization collapses multiple spaces`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid     pocket     6"))
    }

    @Test
    fun `normalization handles mixed delimiters`() {
        assertTrue(DeviceGate.isDeviceSupported("retroid_pocket-6"))
    }

    @Test
    fun `normalization handles complex manufacturer prefix`() {
        assertTrue(DeviceGate.isDeviceSupported("AYN-Technologies_Odin3-Gaming"))
    }
}
