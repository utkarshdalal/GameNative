package app.gamenative.shaders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §4.2: pure pre-check decisions (space + metered) and the pinned-commit URL.
 */
class PackPrechecksTest {

    @Test
    fun `required free space is pack x2 plus headroom`() {
        assertEquals(
            53_113_982L * 2 + 16L * 1024 * 1024,
            PackPrechecks.requiredFreeBytes(53_113_982L),
        )
    }

    @Test
    fun `space check passes with enough and fails with too little`() {
        val packBytes = 53_113_982L
        val required = PackPrechecks.requiredFreeBytes(packBytes)
        assertTrue(PackPrechecks.hasEnoughSpace(packBytes, required))
        assertTrue(PackPrechecks.hasEnoughSpace(packBytes, required + 1))
        assertFalse(PackPrechecks.hasEnoughSpace(packBytes, required - 1))
        assertFalse(PackPrechecks.hasEnoughSpace(packBytes, 0))
    }

    @Test
    fun `space check with empty catalog still demands headroom`() {
        assertEquals(16L * 1024 * 1024, PackPrechecks.requiredFreeBytes(0))
        assertTrue(PackPrechecks.hasEnoughSpace(0, 16L * 1024 * 1024))
        assertFalse(PackPrechecks.hasEnoughSpace(0, 16L * 1024 * 1024 - 1))
    }

    @Test
    fun `metered network requires confirmation, unmetered does not`() {
        assertTrue(PackPrechecks.needsMeteredConfirmation(true))
        assertFalse(PackPrechecks.needsMeteredConfirmation(false))
    }

    @Test
    fun `raw file url uses the pinned commit and never a branch`() {
        assertFalse(ShaderPack.rawUrlFor("abc", "x.slangp").contains("refs/heads"))
    }
}
