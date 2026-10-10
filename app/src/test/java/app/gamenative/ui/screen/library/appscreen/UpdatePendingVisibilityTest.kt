package app.gamenative.ui.screen.library.appscreen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePendingVisibilityTest {
    @Test
    fun firstInstallCannotShowUpdatePending() {
        assertFalse(canShowUpdatePending(isInstalled = false, isDownloading = true))
    }

    @Test
    fun activeUpdateCannotShowUpdatePending() {
        assertFalse(canShowUpdatePending(isInstalled = true, isDownloading = true))
    }

    @Test
    fun incompleteIdleInstallCannotShowUpdatePending() {
        assertFalse(canShowUpdatePending(isInstalled = false, isDownloading = false))
    }

    @Test
    fun installedIdleGameCanShowUpdatePending() {
        assertTrue(canShowUpdatePending(isInstalled = true, isDownloading = false))
    }
}
