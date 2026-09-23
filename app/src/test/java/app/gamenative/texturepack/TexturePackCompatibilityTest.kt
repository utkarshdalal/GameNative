package app.gamenative.texturepack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePackCompatibilityTest {

    @Test
    fun wrapperGamenativeWithSoftwareIsCompatible() {
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "auto", "software"))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "full", "software"))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "partial", "software"))
    }

    @Test
    fun stockWrapperIsNotCompatible() {
        assertFalse(TexturePackGate.compatible("wrapper", "auto", "software"))
        assertFalse(TexturePackGate.compatible("Wrapper", "auto", "software"))
        assertFalse(TexturePackGate.compatible(null, "auto", "software"))
        assertFalse(TexturePackGate.compatible("", "auto", "software"))
    }

    @Test
    fun computeDecodingIsNotCompatible() {
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "auto", "compute"))
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "full", "Compute"))
    }

    @Test
    fun driverMatchIsCaseInsensitive() {
        assertTrue(TexturePackGate.compatible("Wrapper-GameNative", "auto", "software"))
        assertTrue(TexturePackGate.compatible("WRAPPER-GAMENATIVE", "auto", "software"))
    }

    @Test
    fun bcnEmulationNoneIsNotCompatible() {
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "none", "software"))
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "None", "software"))
    }

    @Test
    fun absentEmulationKeysFollowRuntimeDefaults() {
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "", ""))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", null, null))
    }
}
