package com.winlator.xserver.extensions

import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentExtensionPacingTransitionTest {
    @Test
    fun transitionFramePacing_resetsOldTimingEpochAndAppliesLsfgOwnership() {
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
        transition!!.invoke(extension, true, 0)

        assertTrue(timings.isEmpty())
        assertEquals(0, privateField(extension, "frameRateLimit"))
        assertEquals(true, privateField(extension, "eagerIdleRelease"))
    }

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
}
