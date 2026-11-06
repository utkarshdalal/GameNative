package com.winlator.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerDefaultDisplayServerTest {

    @Test
    fun `adreno on API 29 or newer defaults to gamescope`() {
        assertEquals(Container.DISPLAY_SERVER_GAMESCOPE, Container.defaultDisplayServer(true, 29))
        assertEquals(Container.DISPLAY_SERVER_GAMESCOPE, Container.defaultDisplayServer(true, 36))
    }

    @Test
    fun `adreno below API 29 defaults to x11 because ASurfaceControl is missing`() {
        assertEquals(Container.DISPLAY_SERVER_X11, Container.defaultDisplayServer(true, 28))
        assertEquals(Container.DISPLAY_SERVER_X11, Container.defaultDisplayServer(true, 26))
    }

    @Test
    fun `non-adreno defaults to x11 at every API level`() {
        assertEquals(Container.DISPLAY_SERVER_X11, Container.defaultDisplayServer(false, 28))
        assertEquals(Container.DISPLAY_SERVER_X11, Container.defaultDisplayServer(false, 36))
    }

    @Test
    fun `gamescope support follows the ASurfaceControl API floor`() {
        assertFalse(Container.isGamescopeSupported(28))
        assertTrue(Container.isGamescopeSupported(29))
    }
}
