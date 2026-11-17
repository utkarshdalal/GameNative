package app.gamenative.powercontrol.autotuning

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for DeviceGate.isDeviceSupported() covering device detection logic and normalization.
 * Software frame-pacing eligibility is intentionally distinct from validated hardware
 * power-control defaults. SoC-family matching is tested with injected Build identifiers.
 */
class DeviceGateTest {

    @Test
    fun `MediaTek MT6789 SoC model identifies G99 G100 family`() {
        assertTrue(DeviceGate.isMediaTekG99G100(socModel = "MT6789", hardware = "", board = ""))
    }

    @Test
    fun `MediaTek MT6789 hardware identifier identifies G99 G100 family`() {
        assertTrue(DeviceGate.isMediaTekG99G100(socModel = "", hardware = "mt6789", board = ""))
    }

    @Test
    fun `MediaTek identifier matching is case insensitive`() {
        assertTrue(DeviceGate.isMediaTekG99G100(socModel = "MT6789V", hardware = "", board = ""))
    }

    @Test
    fun `unrelated Qualcomm identifier is not G99 G100`() {
        assertFalse(DeviceGate.isMediaTekG99G100(socModel = "SM8550", hardware = "qcom", board = "kalama"))
    }

    @Test
    fun `G99 G100 detection does not validate hardware clock presets`() {
        assertFalse(DeviceGate.isPowerControlPresetValidated("Infinix Hot 50"))
    }

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
    fun `odin3 is not supported`() {
        assertFalse(DeviceGate.isDeviceSupported("odin3"))
    }

    @Test
    fun `odin3 is not supported as substring in longer model name`() {
        assertFalse(DeviceGate.isDeviceSupported("AYN Odin3 Pro"))
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
        assertTrue(DeviceGate.isDeviceSupported("Moorechip_Retroid-Pocket_6-Gaming"))
    }
}
