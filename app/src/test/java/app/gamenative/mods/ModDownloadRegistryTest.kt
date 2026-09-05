package app.gamenative.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModDownloadRegistryTest {
    @Test
    fun tryStart_doesNotReplaceOrFinishAnExistingOwner() {
        val installId = "exclusive-local-import"
        try {
            assertEquals(
                ModImportStartResult.STARTED,
                ModDownloadRegistry.tryStart(installId, "app", "First"),
            )
            assertEquals(
                ModImportStartResult.ALREADY_ACTIVE,
                ModDownloadRegistry.tryStart(installId, "app", "Second"),
            )
            assertEquals("First", ModDownloadRegistry.get(installId)?.displayName)
        } finally {
            ModDownloadRegistry.finish(installId)
        }
    }

    @Test
    fun tryStart_consumesCancellationWithoutCreatingAnOwner() {
        val installId = "exclusive-canceled-import"
        ModDownloadRegistry.requestCancel(installId)

        assertEquals(
            ModImportStartResult.CANCELED_BEFORE_START,
            ModDownloadRegistry.tryStart(installId, "app", "Canceled"),
        )
        assertNull(ModDownloadRegistry.get(installId))
        assertFalse(ModDownloadRegistry.isCancelRequested(installId))
    }

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
