package app.gamenative.ui.component.dialog

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NexusModsDialogHelpersTest {
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
