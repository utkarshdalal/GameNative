package app.gamenative.utils

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgRuntimeGateTest {
    @Test
    fun readyState_requiresFreshActiveGenerationReadyStats() {
        val root = Files.createTempDirectory("lsfg-gate-ready")
        val stats = root.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stats.parent)
        Files.write(stats, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))

        LsfgRuntimeGate.configure(root.toFile())

        assertTrue(LsfgRuntimeGate.isGenerationReady())
    }

    @Test
    fun readyState_failsClosedForInactiveOrStaleStats() {
        val inactiveRoot = Files.createTempDirectory("lsfg-gate-inactive")
        val inactive = inactiveRoot.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(inactive.parent)
        Files.write(inactive, "active=0\ngeneration_ready=0\n".toByteArray(Charsets.UTF_8))
        LsfgRuntimeGate.configure(inactiveRoot.toFile())
        assertFalse(LsfgRuntimeGate.isGenerationReady())

        val staleRoot = Files.createTempDirectory("lsfg-gate-stale")
        val stale = staleRoot.resolve(".config/lsfg-vk/stats.txt")
        Files.createDirectories(stale.parent)
        Files.write(stale, "active=1\ngeneration_ready=1\n".toByteArray(Charsets.UTF_8))
        Files.setLastModifiedTime(
            stale,
            FileTime.fromMillis(System.currentTimeMillis() - 10_000L),
        )
        LsfgRuntimeGate.configure(staleRoot.toFile())
        assertFalse(LsfgRuntimeGate.isGenerationReady())
    }
}
