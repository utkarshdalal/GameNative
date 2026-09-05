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
    fun `mouse look enables Win32 relative input while container shooter mode is active`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setContainerShooterMode(true)
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
    fun `disabling container shooter mode restores normal pointer input`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setContainerShooterMode(true)
        view.setShooterModeConfig(
            ShooterModeConfig(
                lookType = ShooterModeConfig.LOOK_MOUSE,
                win32RelativeMouseInput = true,
            ),
        )
        view.setXServer(xServer)

        view.setContainerShooterMode(false)

        verify { xServer.setRelativeMouseMovement(true) }
        verify { xServer.setRelativeMouseMovement(false) }
    }

    @Test
    fun `disabling shooter mode control restores normal pointer input`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setShooterModeActive(true)
        view.setShooterModeConfig(
            ShooterModeConfig(
                lookType = ShooterModeConfig.LOOK_MOUSE,
                win32RelativeMouseInput = true,
            ),
        )
        view.setXServer(xServer)

        view.setShooterModeActive(false)

        verify { xServer.setRelativeMouseMovement(true) }
        verify { xServer.setRelativeMouseMovement(false) }
    }

    @Test
    fun `right stick look keeps Win32 relative input disabled`() {
        val xServer = mockk<XServer>(relaxed = true)
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setContainerShooterMode(true)
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
