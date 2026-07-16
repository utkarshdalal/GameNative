package app.gamenative.mods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModDownloadRegistryTest {
    @Test
    fun cancellationBeforeStart_isConsumedWithoutPoisoningRetry() {
        val installId = "cancel-before-start"
        ModDownloadRegistry.requestCancel(installId)

        try {
            assertFalse(ModDownloadRegistry.start(installId, "app", "Mod"))
        } finally {
            ModDownloadRegistry.finish(installId)
        }

        try {
            assertTrue(ModDownloadRegistry.start(installId, "app", "Mod"))
            assertFalse(ModDownloadRegistry.isCancelRequested(installId))
        } finally {
            ModDownloadRegistry.finish(installId)
        }
    }

    @Test
    fun cancellationAfterStart_remainsObservableUntilFinishAndDoesNotPoisonRetry() {
        val installId = "cancel-after-start"
        try {
            assertTrue(ModDownloadRegistry.start(installId, "app", "Mod"))
            ModDownloadRegistry.requestCancel(installId)
            assertTrue(ModDownloadRegistry.isCancelRequested(installId))
        } finally {
            ModDownloadRegistry.finish(installId)
        }

        assertNull(ModDownloadRegistry.get(installId))
        assertFalse(ModDownloadRegistry.isCancelRequested(installId))
        try {
            assertTrue(ModDownloadRegistry.start(installId, "app", "Mod"))
        } finally {
            ModDownloadRegistry.finish(installId)
        }
    }
}
