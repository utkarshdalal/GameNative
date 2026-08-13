package app.gamenative.shaders

import app.gamenative.shaders.ShaderDoubleClickLogic.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec 2026-08-12, §5.2: double-click decision table. */
class ShaderDoubleClickLogicTest {

    @Test
    fun `second quick press on the same row confirms and closes`() {
        assertEquals(
            Action.ConfirmAndClose,
            ShaderDoubleClickLogic.decide("crt/crt-easymode.slangp", 1000L, "crt/crt-easymode.slangp", 1200L),
        )
    }

    @Test
    fun `quick press on a different row activates (switches shader)`() {
        assertEquals(
            Action.Activate,
            ShaderDoubleClickLogic.decide("crt/crt-easymode.slangp", 1000L, "film/technicolor.slangp", 1200L),
        )
    }

    @Test
    fun `same path outside the window activates (no close)`() {
        assertEquals(
            Action.Activate,
            ShaderDoubleClickLogic.decide("crt/crt-easymode.slangp", 1000L, "crt/crt-easymode.slangp", 1450L),
        )
    }

    @Test
    fun `nothing armed - first press always activates`() {
        assertEquals(Action.Activate, ShaderDoubleClickLogic.decide(null, 0L, "crt/crt-easymode.slangp", 99999L))
    }

    @Test
    fun `exact boundary of the window confirms`() {
        assertEquals(
            Action.ConfirmAndClose,
            ShaderDoubleClickLogic.decide("crt/crt-easymode.slangp", 1000L, "crt/crt-easymode.slangp", 1400L),
        )
    }

    @Test
    fun `clock going backwards never confirms`() {
        assertEquals(
            Action.Activate,
            ShaderDoubleClickLogic.decide("crt/crt-easymode.slangp", 1000L, "crt/crt-easymode.slangp", 900L),
        )
    }
}
