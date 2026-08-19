package app.gamenative.ui.screen.xserver

import android.graphics.PointF
import android.os.Looper
import android.view.KeyEvent
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.BindingCombo
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit.MILLISECONDS

@RunWith(RobolectricTestRunner::class)
class PhysicalControllerHandlerTest {
    @Test
    fun `sequence releases mouse movement without another motion event`() {
        val keyCode = KeyEvent.KEYCODE_BUTTON_A
        val sequenceDelayMs = 150
        val controllerBinding = ExternalControllerBinding().apply {
            setKeyCode(keyCode)
            setBindingCombo(
                BindingCombo.fromBindings(
                    listOf(Binding.MOUSE_MOVE_RIGHT, Binding.KEY_E),
                    BindingCombo.Mode.SEQUENCE,
                    sequenceDelayMs,
                ),
            )
        }
        val controller = mock<ExternalController>()
        whenever(controller.getControllerBinding(keyCode)).thenReturn(controllerBinding)
        val profile = mock<ControlsProfile>()
        whenever(profile.getController(KeyEvent(KeyEvent.ACTION_DOWN, keyCode).deviceId)).thenReturn(controller)
        whenever(profile.cursorSpeed).thenReturn(1f)
        val handler = PhysicalControllerHandler(profile, mock<XServer>())

        try {
            handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            shadowOf(Looper.getMainLooper()).idleFor(1, MILLISECONDS)
            assertEquals(1f, mouseMoveOffset(handler).x, 0f)

            shadowOf(Looper.getMainLooper()).idleFor(sequenceDelayMs.toLong() - 1, MILLISECONDS)
            assertEquals(0f, mouseMoveOffset(handler).x, 0f)
            assertNull(privateField(handler, "mouseMoveTimer"))
        } finally {
            handler.cleanup()
        }
    }

    private fun mouseMoveOffset(handler: PhysicalControllerHandler): PointF {
        return privateField(handler, "mouseMoveOffset") as PointF
    }

    private fun privateField(handler: PhysicalControllerHandler, name: String): Any? {
        val field = PhysicalControllerHandler::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(handler)
    }
}
