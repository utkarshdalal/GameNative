package com.winlator.winhandler

import android.app.Application
import android.view.KeyEvent
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.ExternalController
import com.winlator.widget.XServerRendererView
import com.winlator.xserver.XServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Exercise real key decoding and Wine shared-memory output, not just the routing decision. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [ShadowWinHandlerNative::class])
class WinHandlerControllerInputTest {
    @Test
    fun `second controller face and shoulder buttons reach Wine without changing player one`() {
        val devices = listOf(41, 42)
        val manager = mock<ControllerManager>()
        devices.forEachIndexed { slot, id -> whenever(manager.getSlotForDevice(id)).thenReturn(slot) }
        val view = mock<XServerRendererView>()
        whenever(view.context).thenReturn(RuntimeEnvironment.getApplication())
        val handler = WinHandler(mock<XServer>(), view)
        setField(handler, "controllerManager", manager)
        val controllers = devices.map { id ->
            object : ExternalController() {
                override fun getDeviceId(): Int = id
            }
        }
        setField(handler, "currentController", controllers[0])
        val extras = field(handler, "extraControllers") as Array<ExternalController?>
        extras[0] = controllers[1]

        val directory = RuntimeEnvironment.getApplication().cacheDir
        RandomAccessFile(java.io.File(directory, "p1.mem"), "rw").use { p1 ->
            RandomAccessFile(java.io.File(directory, "p2.mem"), "rw").use { p2 ->
                val buffers = listOf(p1, p2).map { file ->
                    file.setLength(64)
                    file.channel.map(FileChannel.MapMode.READ_WRITE, 0, 64).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                    }
                }
                setField(handler, "gamepadBuffer", buffers[0])
                (field(handler, "extraGamepadBuffers") as Array<MappedByteBuffer?>)[0] = buffers[1]

                for ((keyCode, sdlIndex) in listOf(
                    KeyEvent.KEYCODE_BUTTON_A to 0,
                    KeyEvent.KEYCODE_BUTTON_L1 to 9,
                    KeyEvent.KEYCODE_BUTTON_R1 to 10,
                )) {
                    assertTrue(handler.onKeyEvent(key(devices[0], keyCode, KeyEvent.ACTION_DOWN)))
                    assertEquals("P1 button $keyCode", 1, buffers[0].get(16 + sdlIndex).toInt())
                    assertEquals("P2 must remain untouched", 0, buffers[1].get(16 + sdlIndex).toInt())
                    assertTrue(handler.onKeyEvent(key(devices[1], keyCode, KeyEvent.ACTION_DOWN)))
                    assertEquals("P2 button $keyCode", 1, buffers[1].get(16 + sdlIndex).toInt())
                    assertTrue(handler.onKeyEvent(key(devices[1], keyCode, KeyEvent.ACTION_UP)))
                    assertEquals("P2 release $keyCode", 0, buffers[1].get(16 + sdlIndex).toInt())
                    assertEquals("P2 release must not release P1", 1, buffers[0].get(16 + sdlIndex).toInt())
                    assertTrue(handler.onKeyEvent(key(devices[0], keyCode, KeyEvent.ACTION_UP)))
                }
            }
        }
    }

    private fun key(deviceId: Int, keyCode: Int, action: Int): KeyEvent = mock<KeyEvent>().also {
        whenever(it.deviceId).thenReturn(deviceId)
        whenever(it.keyCode).thenReturn(keyCode)
        whenever(it.action).thenReturn(action)
    }

    private fun field(handler: WinHandler, name: String): Any? =
        WinHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.get(handler)

    private fun setField(handler: WinHandler, name: String, value: Any?) {
        WinHandler::class.java.getDeclaredField(name).apply { isAccessible = true }.set(handler, value)
    }
}

/** Only replace JNI loading/notification; all routing, state and serialization stay real. */
@Implements(WinHandler::class)
class ShadowWinHandlerNative {
    companion object {
        @JvmStatic
        @Implementation
        fun __staticInitializer__() = Unit

        @JvmStatic
        @Implementation
        fun notifyStateChanged(playerIndex: Int) = Unit
    }
}
