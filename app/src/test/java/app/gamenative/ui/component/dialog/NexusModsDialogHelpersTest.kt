package app.gamenative.ui.component.dialog

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusModsDialogHelpersTest {
    @Test
    fun callbackWait_callbackWinsWhenCancellationIsAlsoReady() = runTest {
        val callback = CompletableDeferred("authorized")

        assertEquals(
            CallbackWaitResult.Received("authorized"),
            awaitCallbackOrCancellation(callback, MutableStateFlow(true), timeoutMillis = 1_000L),
        )
    }

    @Test
    fun callbackWait_returnsCancellation() = runTest {
        assertEquals(
            CallbackWaitResult.Cancelled,
            awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(true), 1_000L),
        )
    }

    @Test
    fun callbackWait_returnsNullOnTimeout() = runTest {
        assertNull(
            awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(false), 1_000L),
        )
    }

    @Test
    fun callbackWait_propagatesParentCancellation() = runTest {
        val outcome = CompletableDeferred<Throwable?>()
        val waiter = launch {
            outcome.complete(
                runCatching {
                    awaitCallbackOrCancellation(CompletableDeferred<String>(), MutableStateFlow(false), 60_000L)
                }.exceptionOrNull(),
            )
        }

        yield()
        waiter.cancel()

        assertTrue(outcome.await() is CancellationException)
    }

    @Test
    fun profileStatus_preservesDisabledInstallStatus() {
        val install = install(status = ModInstallStatus.DISABLED)

        assertEquals(ModInstallStatus.DISABLED.name, install.profileStatus(enabledInProfile = false))
    }

    @Test
    fun profileStatus_marksReadyInstallDisabledInProfile() {
        val install = install(status = ModInstallStatus.READY)

        assertEquals("PROFILE_DISABLED", install.profileStatus(enabledInProfile = false))
    }

    private fun install(status: ModInstallStatus): ModInstall =
        ModInstall(
            installId = "install",
            appId = "app",
            nexusGameDomain = "skyrimspecialedition",
            nexusModId = 1L,
            nexusFileId = 2L,
            modName = "Mod",
            fileName = "file.zip",
            archivePath = "",
            extractedPath = "",
            status = status.name,
        )
}
