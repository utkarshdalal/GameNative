package com.winlator.widget

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.winlator.inputcontrols.Binding
import com.winlator.xserver.XServer
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class InputControlsViewMouseLookTest {
    @Test
    fun `on-screen mouse binding moves the pointer on display frames and stops on release`() {
        val xServer = mock<XServer>()
        val view = InputControlsView(ApplicationProvider.getApplicationContext())
        view.setXServer(xServer)

        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, true, 1f)
        shadowOf(Looper.getMainLooper()).idleFor(50, MILLISECONDS)
        val dx = argumentCaptor<Int>()
        verify(xServer, atLeastOnce()).injectPointerMoveDelta(dx.capture(), eq(0))
        assertTrue("expected a rightward move", dx.allValues.any { it > 0 })

        view.handleInputEvent(Binding.MOUSE_MOVE_RIGHT, false, 0f)
        clearInvocations(xServer)
        shadowOf(Looper.getMainLooper()).idleFor(50, MILLISECONDS)
        verify(xServer, never()).injectPointerMoveDelta(anyInt(), anyInt())
    }
}
