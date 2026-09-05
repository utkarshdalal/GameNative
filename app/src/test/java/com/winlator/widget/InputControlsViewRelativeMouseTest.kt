package com.winlator.widget

import androidx.test.core.app.ApplicationProvider
import app.gamenative.data.ShooterModeConfig
import com.winlator.xserver.XServer
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputControlsViewRelativeMouseTest {
    @Test
    fun `mouse look can enable Win32 relative input`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setShooterModeConfig(
            ShooterModeConfig(
                lookType = ShooterModeConfig.LOOK_MOUSE,
                win32RelativeMouseInput = true,
            ),
        )
        view.setXServer(xServer)

        verify { xServer.setRelativeMouseMovement(true) }
    }

    @Test
    fun `right stick look keeps Win32 relative input disabled`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setShooterModeConfig(
            ShooterModeConfig(
                lookType = ShooterModeConfig.LOOK_GAMEPAD_RIGHT_STICK,
                win32RelativeMouseInput = true,
            ),
        )
        view.setXServer(xServer)

        verify(atLeast = 1) { xServer.setRelativeMouseMovement(false) }
        verify(exactly = 0) { xServer.setRelativeMouseMovement(true) }
    }
}
