package com.winlator.widget

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XServerViewLsfgPacingContractTest {
    @Test
    fun source_keepsRendererLimiterBehindNativeReadinessGate() {
        val source = Files.readString(
            java.nio.file.Paths.get("src/main/java/com/winlator/widget/XServerView.java"),
        )
        assertTrue(source.contains("LsfgRuntimeGate.isGenerationReady()"))
        assertTrue(source.contains("transitionLsfgFramePacing"))
        assertTrue(source.contains("refreshLsfgFramePacing"))
        assertTrue(source.contains("nativeReady ? 0 : localFrameRateLimit"))
    }

    @Test
    fun presentPath_resynchronizesRendererPacingOnEveryPresent() {
        val source = Files.readString(
            java.nio.file.Paths.get("src/main/java/com/winlator/xserver/extensions/PresentExtension.java"),
        )
        assertTrue(source.contains("vr.xServerView.transitionLsfgFramePacing"))
        assertFalse(source.contains("pending idle superseded and dropped"))
    }
}
