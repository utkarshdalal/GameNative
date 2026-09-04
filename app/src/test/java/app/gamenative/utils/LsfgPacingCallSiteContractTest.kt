package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgPacingCallSiteContractTest {
    @Test
    fun xServerScreen_routesLsfgPacingThroughReadinessGates() {
        val source = String(
            Files.readAllBytes(sourcePath("app/gamenative/ui/screen/xserver/XServerScreen.kt")),
            Charsets.UTF_8,
        )
        val limiter = source.substringAfter("fun applyFpsLimiterToEngines(limit: Int)")
            .substringBefore("fun effectiveFpsLimit()")

        assertTrue(limiter.contains("val lsfgActive = isLsfgAvailable && lsfgMultiplier >= 2"))
        assertTrue(limiter.contains("xServerView?.transitionLsfgFramePacing(lsfgActive, limit)"))
        assertTrue(limiter.contains("?.transitionFramePacing(lsfgActive, limit)"))
        assertFalse(
            "LSFG must not unconditionally zero the renderer before native readiness",
            limiter.contains("xServerView?.setFrameRateLimit(if (lsfgActive) 0 else limit)"),
        )
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
