package com.winlator.xserver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerExtensionPolicyTest {
    @Test
    fun `targeted Bionic compatibility advertises XGE and XI2`() {
        assertTrue(XServer.supportsXInput2(false))
        assertTrue(XServer.shouldAdvertiseGenericEvents(false, true))
    }

    @Test
    fun `ordinary Bionic behavior does not advertise XGE`() {
        assertTrue(XServer.supportsXInput2(false))
        assertFalse(XServer.shouldAdvertiseGenericEvents(false, false))
    }

    @Test
    fun `glibc ignores mouse compatibility and preserves disabled XI2 behavior`() {
        assertFalse(XServer.supportsXInput2(true))
        assertFalse(XServer.shouldAdvertiseGenericEvents(true, true))
    }
}
