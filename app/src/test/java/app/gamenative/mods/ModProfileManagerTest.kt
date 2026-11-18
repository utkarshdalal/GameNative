package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModProfile
import app.gamenative.data.ModProfileInstallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModProfileManagerTest {
    @Test
    fun defaultMissingStateEnabled_enablesExistingInstallForEmptyProfile() {
        val profile = profile(createdAt = 200L)
        val install = install(createdAt = 100L)

        assertTrue(ModProfileManager.defaultMissingStateEnabled(profile, install, profileHasExistingStates = false))
    }

    @Test
    fun defaultMissingStateEnabled_disablesNewerInstallForExistingProfile() {
        val profile = profile(createdAt = 100L)
        val install = install(createdAt = 200L)

        assertFalse(ModProfileManager.defaultMissingStateEnabled(profile, install, profileHasExistingStates = false))
    }

    @Test
    fun defaultMissingStateEnabled_disablesMissingInstallWhenProfileAlreadyHasStates() {
        val profile = profile(createdAt = 200L)
        val install = install(createdAt = 100L)

        assertFalse(ModProfileManager.defaultMissingStateEnabled(profile, install, profileHasExistingStates = true))
    }

    @Test
    fun defaultMissingStateEnabled_disablesGloballyDisabledInstall() {
        val profile = profile(createdAt = 200L)
        val install = install(createdAt = 100L, status = ModInstallStatus.DISABLED)

        assertFalse(ModProfileManager.defaultMissingStateEnabled(profile, install, profileHasExistingStates = false))
    }

    @Test
    fun normalizedPriorityByInstallId_makesDuplicatePrioritiesUniqueInDisplayOrder() {
        val alpha = install("alpha", modName = "Alpha", createdAt = 100L)
        val bravo = install("bravo", modName = "Bravo", createdAt = 100L)
        val charlie = install("charlie", modName = "Charlie", createdAt = 100L)
        val states = listOf(
            state("charlie", priority = 0),
            state("bravo", priority = 0),
            state("alpha", priority = 0),
        )

        val priorities = ModProfileManager.normalizedPriorityByInstallId(states, listOf(charlie, bravo, alpha))

        assertEquals(
            mapOf(
                "alpha" to 2,
                "bravo" to 1,
                "charlie" to 0,
            ),
            priorities,
        )
    }

    private fun profile(createdAt: Long): ModProfile =
        ModProfile(
            profileId = "profile",
            appId = "app",
            name = "Profile",
            createdAt = createdAt,
        )

    private fun install(
        createdAt: Long,
        status: ModInstallStatus = ModInstallStatus.READY,
    ): ModInstall =
        install("install", "Mod", createdAt, status)

    private fun install(
        installId: String,
        modName: String,
        createdAt: Long,
        status: ModInstallStatus = ModInstallStatus.READY,
    ): ModInstall =
        ModInstall(
            installId = installId,
            appId = "app",
            nexusGameDomain = "skyrim",
            nexusModId = 1L,
            nexusFileId = 2L,
            modName = modName,
            fileName = "mod.zip",
            archivePath = "",
            extractedPath = "",
            status = status.name,
            createdAt = createdAt,
        )

    private fun state(installId: String, priority: Int): ModProfileInstallState =
        ModProfileInstallState(
            profileId = "profile",
            installId = installId,
            appId = "app",
            priority = priority,
        )
}
