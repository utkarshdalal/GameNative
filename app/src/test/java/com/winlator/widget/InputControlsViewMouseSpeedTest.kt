package com.winlator.widget

import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import java.util.Timer
import java.util.TimerTask

@RunWith(RobolectricTestRunner::class)
class InputControlsViewMouseSpeedTest {
    @Test
    fun `fractional movement accumulates and uses updated profile speed`() = withTimer { view, profile, xServer, tasks ->
        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 0.05f)
        val task = tasks.single()

        task.run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())
        task.run()
        verify(xServer).injectPointerMoveDelta(1, 0)

        profile.cursorSpeed = 2f
        task.run()
        verify(xServer, org.mockito.kotlin.times(2)).injectPointerMoveDelta(1, 0)
        verify(xServer, never()).injectPointerMoveDelta(0, 0)
    }

    @Test
    fun `releasing an axis discards its pending fraction before another press`() = withTimer { view, _, xServer, tasks ->
        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 0.05f)
        tasks.single().run()
        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, false)
        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 0.05f)

        tasks.single().run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())
        tasks.single().run()
        verify(xServer).injectPointerMoveDelta(1, 0)
    }

    @Test
    fun `switching profiles clears movement and restarts with the new speed`() = withTimer { view, _, xServer, tasks ->
        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 0.05f)
        val oldTask = tasks.single()
        oldTask.run()

        val newProfile = ControlsProfile(ApplicationProvider.getApplicationContext(), 2).apply { cursorSpeed = 2f }
        view.setProfile(newProfile)
        oldTask.run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())

        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 0.025f)
        assertEquals(2, tasks.size)
        tasks.last().run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())
        tasks.last().run()
        verify(xServer).injectPointerMoveDelta(1, 0)
    }

    @Test
    fun `hiding and re-enabling controls restarts without stale movement`() = withTimer { view, profile, xServer, tasks ->
        view.handleInputEvent(Binding.MOUSE_MOVE_DOWN, true, 0.05f)
        val oldTask = tasks.single()
        oldTask.run()
        view.hideProfileForOverlay()
        oldTask.run()
        view.setProfile(profile)
        oldTask.run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())

        view.handleInputEvent(Binding.MOUSE_MOVE_DOWN, true, 0.05f)
        assertEquals(2, tasks.size)
        tasks.last().run()
        verify(xServer, never()).injectPointerMoveDelta(any(), any())
        tasks.last().run()
        verify(xServer).injectPointerMoveDelta(0, 1)
    }

    private fun withTimer(block: (InputControlsView, ControlsProfile, XServer, List<TimerTask>) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = InputControlsView(context)
        val profile = ControlsProfile(context, 1)
        val xServer = mock<XServer>()
        val tasks = mutableListOf<TimerTask>()
        view.setXServer(xServer)
        mockConstruction(Timer::class.java) { timer, _ ->
            doAnswer { invocation ->
                tasks.add(invocation.getArgument(0))
                null
            }.`when`(timer).schedule(any<TimerTask>(), eq(0L), eq((1000 / 60).toLong()))
        }.use {
            try {
                view.setProfile(profile)
                block(view, profile, xServer, tasks)
            } finally {
                view.setProfile(null)
            }
        }
    }
}
