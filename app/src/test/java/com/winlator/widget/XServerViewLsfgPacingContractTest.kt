package com.winlator.widget

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerViewLsfgPacingContractTest {
    @Test
    fun source_keepsRendererLimiterBehindNativeReadinessGate() {
        val source = String(
            Files.readAllBytes(sourcePath("com/winlator/widget/XServerView.java")),
            Charsets.UTF_8,
        )
        assertTrue(source.contains("LsfgRuntimeGate.isGenerationReady()"))
        assertTrue(source.contains("transitionLsfgFramePacing"))
        assertTrue(source.contains("refreshLsfgFramePacing"))
        assertTrue(source.contains("nativeReady ? 0 : localFrameRateLimit"))
    }

    @Test
    fun presentPath_resynchronizesRendererPacingOnEveryPresent() {
        val source = String(
            Files.readAllBytes(
                sourcePath("com/winlator/xserver/extensions/PresentExtension.java"),
            ),
            Charsets.UTF_8,
        )
        assertTrue(source.contains("vr.xServerView.transitionLsfgFramePacing"))
        assertFalse(source.contains("pending idle superseded and dropped"))
    }

    private fun sourcePath(relative: String): Path {
        val modulePath = Paths.get("src/main/java").resolve(relative)
        if (Files.isRegularFile(modulePath)) return modulePath
        return Paths.get("app/src/main/java").resolve(relative)
    }
}
