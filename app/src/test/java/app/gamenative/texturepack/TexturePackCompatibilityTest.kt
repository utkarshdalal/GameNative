package app.gamenative.texturepack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TexturePackCompatibilityTest {

    @Test
    fun wrapperGamenativeIsCompatible() {
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "auto"))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "full"))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", "partial"))
    }

    @Test
    fun stockWrapperIsNotCompatible() {
        assertFalse(TexturePackGate.compatible("wrapper", "auto"))
        assertFalse(TexturePackGate.compatible("Wrapper", "auto"))
        assertFalse(TexturePackGate.compatible(null, "auto"))
        assertFalse(TexturePackGate.compatible("", "auto"))
    }

    @Test
    fun driverMatchIsCaseInsensitive() {
        assertTrue(TexturePackGate.compatible("Wrapper-GameNative", "auto"))
        assertTrue(TexturePackGate.compatible("WRAPPER-GAMENATIVE", "auto"))
    }

    @Test
    fun bcnEmulationNoneIsNotCompatible() {
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "none"))
        assertFalse(TexturePackGate.compatible("wrapper-gamenative", "None"))
    }

    @Test
    fun absentEmulationKeyFollowsRuntimeDefault() {
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", ""))
        assertTrue(TexturePackGate.compatible("wrapper-gamenative", null))
    }
}
