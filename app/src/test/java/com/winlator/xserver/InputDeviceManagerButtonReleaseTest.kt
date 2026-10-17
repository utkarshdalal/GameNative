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
    /** A button-only client receives mouse-up with pre-transition state after its window moves. */
    @Test
    fun `button-only client receives release after window geometry changes`() {
        val server = XServer(ScreenInfo(640, 480), false)
        val output = RecordingOutput()
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

    /** Owner-events must fall back for a foreign recipient but preserve normal owner delivery. */
    @Test
    fun `owner-event release is delivered to the grab client when another client selects it`() {
        for ((ownerSelectsChild, ownerClearsChild, expectedRecipient) in listOf(
            Triple(false, false, 42), Triple(true, false, 43), Triple(true, true, 42),
        )) {
            val server = XServer(ScreenInfo(640, 480), false)
            val output = RecordingOutput()
            val client = XClient(server, null, output)
            val window = server.windowManager.createWindow(
                42, server.windowManager.rootWindow, 0, 0, 200, 200,
                WindowAttributes.WindowClass.INPUT_ONLY, null, 0, client,
            )
            client.setEventListenerForWindow(window, Bitmask(
                (Event.BUTTON_PRESS or Event.BUTTON_RELEASE or Event.OWNER_GRAB_BUTTON).toLong(),
            ))
            server.windowManager.mapWindow(window)
            server.pointer.setPosition(10, 10)
            server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
            assertEquals(4, output.buffer.get(0).toInt())

            val foreignOutput = RecordingOutput()
            val foreignClient = XClient(server, null, foreignOutput)
            val child = server.windowManager.createWindow(
                43, window, 0, 0, 100, 100,
                WindowAttributes.WindowClass.INPUT_ONLY, null, 0, foreignClient,
            )
            foreignClient.setEventListenerForWindow(child, Bitmask(Event.BUTTON_RELEASE.toLong()))
            if (ownerSelectsChild) {
                client.setEventListenerForWindow(child, Bitmask(Event.BUTTON_RELEASE.toLong()))
            }
            if (ownerClearsChild) {
                client.setEventListenerForWindow(child, Bitmask())
            }
            server.windowManager.mapWindow(child)
            server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)

            assertEquals("The grabbing client must receive mouse-up", 5, output.buffer.get(32).toInt())
            assertEquals(expectedRecipient, output.buffer.getInt(44))
            assertEquals("A grab must not deliver the release to another client", 0, foreignOutput.buffer.position())
            assertNull(server.grabManager.window)
        }
    }

    /** Wine may replace the implicit grab with its disabled but viewable clip window. */
    @Test
    fun `disabled explicit grab retains paired button delivery to the grabbing client`() {
        val server = XServer(ScreenInfo(640, 480), false)
        val output = RecordingOutput()
        val client = XClient(server, null, output)
        val game = server.windowManager.createWindow(
            42, server.windowManager.rootWindow, 0, 0, 200, 200,
            WindowAttributes.WindowClass.INPUT_ONLY, null, 0, client,
        )
        client.setEventListenerForWindow(game, Bitmask((Event.BUTTON_PRESS or Event.BUTTON_RELEASE).toLong()))
        server.windowManager.mapWindow(game)
        server.pointer.setPosition(10, 10)
        server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        assertEquals(4, output.buffer.get(0).toInt())

        val foreignOutput = RecordingOutput()
        val desktopClient = XClient(server, null, foreignOutput)
        val clip = server.windowManager.createWindow(
            43, server.windowManager.rootWindow, 0, 0, 1, 1,
            WindowAttributes.WindowClass.INPUT_ONLY, null, 0, desktopClient,
        )
        desktopClient.setEventListenerForWindow(clip, Bitmask((Event.BUTTON_PRESS or Event.BUTTON_RELEASE).toLong()))
        server.windowManager.mapWindow(clip)
        clip.attributes.setEnabled(false)
        server.grabManager.activatePointerGrab(
            clip, false, Bitmask((Event.BUTTON_PRESS or Event.BUTTON_RELEASE or Event.POINTER_MOTION).toLong()), client, null,
        )
        server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        assertEquals("The original mouse-down must be paired despite the disabled grab window", 5, output.buffer.get(32).toInt())
        assertEquals(43, output.buffer.getInt(44))
        assertEquals(0x100, output.buffer.getShort(60).toInt())

        server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        assertEquals("Subsequent presses must use the active grab listener", 4, output.buffer.get(64).toInt())
        assertEquals(5, output.buffer.get(96).toInt())
        server.injectPointerMove(20, 20)
        assertEquals("Pointer motion must also honor the disabled active grab", 6, output.buffer.get(128).toInt())
        assertEquals(43, output.buffer.getInt(140))

        server.grabManager.activatePointerGrab(clip, false, Bitmask(Event.BUTTON_RELEASE.toLong()), client, null)
        server.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT)
        server.injectPointerMove(30, 30)
        assertEquals("Unselected press and motion events must be discarded", 160, output.buffer.position())
        server.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT)
        assertEquals(5, output.buffer.get(160).toInt())
        assertEquals("Only selected events reach the grab client", 192, output.buffer.position())
        assertEquals("The window owner must not receive the grab client's buttons", 0, foreignOutput.buffer.position())
        assertEquals("Explicit grabs survive the last button release", clip, server.grabManager.window)
    }

    /** In-memory little-endian X11 stream for checking exact event fields. */
    private class RecordingOutput : XOutputStream(1024) {
        init {
            setByteOrder(ByteOrder.LITTLE_ENDIAN)
        }

        /** Disable automatic flushing because host tests have no Android native socket. */
        override fun lock(): XStreamLock = XStreamLock {}
    }

    /** Host-test shadow for Drawable's Android-only rasterizer setup, not X11 routing. */
    @Implements(value = Drawable::class, isInAndroidSdk = false)
    class HeadlessDrawable {
        companion object {
            /** Skip the unused Android-only rasterizer while retaining real drawable construction. */
            @JvmStatic
            @Implementation
            fun __staticInitializer__() {}
        }
    }
}
