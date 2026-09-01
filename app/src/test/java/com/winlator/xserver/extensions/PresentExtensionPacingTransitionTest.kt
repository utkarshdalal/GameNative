package com.winlator.xserver.extensions

import com.winlator.xserver.Pixmap
import com.winlator.xserver.Window
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PresentExtensionPacingTransitionTest {
    @Test
    fun transitionFramePacing_keepsLocalLimitUntilNativeLsfgIsReady() {
        val extension = PresentExtension()
        val timingsField = PresentExtension::class.java
            .getDeclaredField("windowTimings")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val timings = timingsField.get(extension) as ConcurrentHashMap<Int, Any>
        val timingClass = PresentExtension::class.java.declaredClasses
            .first { it.simpleName == "WindowTiming" }
        val timing = timingClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        timings[7] = timing

        val transition = PresentExtension::class.java.methods.firstOrNull {
            it.name == "transitionFramePacing" && it.parameterTypes.contentEquals(
                arrayOf(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType),
            )
        }
        assertNotNull("Present pacing needs an explicit LSFG ownership transition", transition)
        transition!!.invoke(extension, true, 60)

        assertTrue(timings.isEmpty())
        assertEquals(60, privateField(extension, "frameRateLimit"))
        assertEquals(false, privateField(extension, "eagerIdleRelease"))
    }

    @Test
    fun scheduledPresent_releasesSupersededPixmapEvenWhenLsfgIsOff() {
        val extension = PresentExtension()
        val sync = mock(SyncExtension::class.java)
        setPrivateField(extension, "syncExtension", sync)

        val window = Window(7, null, 0, 0, 1, 1, null)
        val firstPixmap = mock(Pixmap::class.java)
        val superseded = PresentExtension.PendingIdle(window, firstPixmap, 1, 101, 0, 0)

        extension.releaseSupersededIdle(superseded)

        verify(sync).setTriggered(101)
    }

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

    private fun setPrivateField(instance: Any, name: String, value: Any?) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }
}
