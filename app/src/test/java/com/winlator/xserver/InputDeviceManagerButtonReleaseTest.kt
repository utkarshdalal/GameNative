package com.winlator.xserver

import android.app.Application
import com.winlator.xconnector.XOutputStream
import com.winlator.xconnector.XStreamLock
import com.winlator.xserver.events.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], shadows = [InputDeviceManagerButtonReleaseTest.HeadlessDrawable::class])
class InputDeviceManagerButtonReleaseTest {
    @Test
    fun `button-only client receives release after window geometry changes`() {
        val server = XServer(ScreenInfo(640, 480), false)
        val output = object : XOutputStream(1024) {
            // Retain real serialized events instead of flushing them to a native socket.
            override fun lock(): XStreamLock = XStreamLock {}
        }
        output.setByteOrder(ByteOrder.LITTLE_ENDIAN)
        val client = XClient(server, null, output)
        val window = server.windowManager.createWindow(
            42, server.windowManager.rootWindow, 0, 0, 200, 200,
            WindowAttributes.WindowClass.INPUT_ONLY, null, 0, client,
        )
        client.setEventListenerForWindow(window, Bitmask((Event.BUTTON_PRESS or Event.BUTTON_RELEASE).toLong()))
        server.windowManager.mapWindow(window)
        server.pointer.setPosition(10, 10)
        server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        assertEquals(4, output.buffer.get(0).toInt())

        // A window change moves the pointer off the original recipient during its implicit grab.
        window.setX(100)
        window.setWidth(400)
        server.inputDeviceManager.onUpdateWindowGeometry(window, true)
        server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)

        assertEquals("Wine must receive ButtonRelease without selecting pointer motion", 5, output.buffer.get(32).toInt())
        assertEquals(1, output.buffer.get(33).toInt())
        assertEquals(42, output.buffer.getInt(44))
        assertEquals("X11 release state describes the buttons before release", 0x100, output.buffer.getShort(60).toInt())
        assertNull(server.grabManager.window)
    }

    // Input routing needs real Java drawables, but never calls the Android rasterizer.
    @Implements(value = Drawable::class, isInAndroidSdk = false)
    class HeadlessDrawable {
        companion object {
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }
}
