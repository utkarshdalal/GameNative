package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NexusImportStateTest {
    @Test
    fun terminalInstall_canceledUpdateRestoresPreviousInstall() {
        val previous = install(status = ModInstallStatus.APPLIED, fileId = 10L, updatedAt = 1L)
        val importing = install(
            status = ModInstallStatus.IMPORTING,
            fileId = 11L,
            metadataJson = NexusImportState.importMetadata("new", previous),
        )

        val terminal = NexusImportState.terminalInstall(
            importing = importing,
            summary = "new",
            status = ModInstallStatus.CANCELED,
            message = "Import canceled",
            previousInstall = previous,
            now = 99L,
        )

        assertEquals(ModInstallStatus.APPLIED.name, terminal.status)
        assertEquals(10L, terminal.nexusFileId)
        assertEquals(99L, terminal.updatedAt)
        assertEquals(previous.metadataJson, terminal.metadataJson)
    }

    @Test
    fun terminalInstall_pausedUpdateKeepsPartialWithRestorablePreviousInstall() {
        val previous = install(status = ModInstallStatus.READY, fileId = 10L)
        val importing = install(
            status = ModInstallStatus.IMPORTING,
            fileId = 11L,
            metadataJson = NexusImportState.importMetadata("new", previous),
        )

        val terminal = NexusImportState.terminalInstall(
            importing = importing,
            summary = "new",
            status = ModInstallStatus.PAUSED,
            message = "Download paused",
            previousInstall = previous,
            now = 99L,
        )

        assertEquals(ModInstallStatus.PAUSED.name, terminal.status)
        assertEquals(11L, terminal.nexusFileId)
        assertEquals("Download paused", JSONObject(terminal.metadataJson).getString("error"))
        assertEquals(10L, NexusImportState.restorablePreviousInstall(terminal)?.nexusFileId)
    }

    @Test
    fun restorablePreviousInstall_readsSnapshotFromInterruptedImport() {
        val previous = install(status = ModInstallStatus.DISABLED, fileId = 10L)
        val interrupted = install(
            status = ModInstallStatus.IMPORTING,
            fileId = 11L,
            metadataJson = NexusImportState.importMetadata("new", previous),
        )

        val restored = NexusImportState.restorablePreviousInstall(interrupted)

        assertEquals(ModInstallStatus.DISABLED.name, restored?.status)
        assertEquals(10L, restored?.nexusFileId)
    }

    @Test
    fun userMessage_classifiesCommonImportFailures() {
        assertEquals(
            "Nexus denied access to this resource for the current account.",
            NexusImportState.userMessage(NexusApiException("Download failed (403)", statusCode = 403)),
        )
        assertEquals(
            "Download was interrupted before it finished. Retry to resume or redownload the file.",
            NexusImportState.userMessage(IOException("Download ended early (4 of 8 bytes)")),
        )
        assertTrue(
            NexusImportState.userMessage(IOException("Nexus did not return a download link"))
                .contains("manual download"),
        )
    }

    @Test
    fun completedDownload_requiresExactRecordedByteCountAndClearsAuthorizationPause() {
        val paused = NexusImportState.pauseForWebsiteAuthorization(
            install = install(status = ModInstallStatus.IMPORTING, fileId = 10L),
            message = "Authorize on Nexus",
        )

        val completed = NexusImportState.markDownloadComplete(paused, downloadedBytes = 4_096L)

        assertTrue(NexusImportState.hasCompletedDownload(completed, archiveBytes = 4_096L))
        assertFalse(NexusImportState.hasCompletedDownload(completed, archiveBytes = 4_095L))
        assertFalse(NexusImportState.isWaitingForWebsiteAuthorization(completed))
    }

    @Test
    fun authorizationPause_invalidatesPreviouslyCompletedDownload() {
        val completed = NexusImportState.markDownloadComplete(
            install = install(status = ModInstallStatus.IMPORTING, fileId = 10L),
            downloadedBytes = 4_096L,
        )

        val paused = NexusImportState.pauseForWebsiteAuthorization(completed, "Authorize on Nexus")

        assertTrue(NexusImportState.isWaitingForWebsiteAuthorization(paused))
        assertFalse(NexusImportState.hasCompletedDownload(paused, archiveBytes = 4_096L))
    }

    @Test
    fun unavailableOnlineAccess_pausesImportWithoutDiscardingCompletedDownload() {
        val completed = NexusImportState.markDownloadComplete(
            install = install(status = ModInstallStatus.IMPORTING, fileId = 10L),
            downloadedBytes = 4_096L,
        )

        val paused = NexusImportState.pauseWhileOnlineAccessUnavailable(
            install = completed,
            message = "Nexus downloads unavailable",
        )

        assertEquals(ModInstallStatus.PAUSED.name, paused.status)
        assertEquals("Nexus downloads unavailable", JSONObject(paused.metadataJson).getString("error"))
        assertTrue(NexusImportState.hasCompletedDownload(paused, archiveBytes = 4_096L))
        assertFalse(NexusImportState.isWaitingForWebsiteAuthorization(paused))
    }

    @Test
    fun unavailableOnlineAccess_doesNotRewriteMatchingPause() {
        val paused = NexusImportState.pauseWhileOnlineAccessUnavailable(
            install = install(status = ModInstallStatus.IMPORTING, fileId = 10L),
            message = "Nexus downloads unavailable",
            now = 2L,
        )

        val unchanged = NexusImportState.pauseWhileOnlineAccessUnavailable(
            install = paused,
            message = "Nexus downloads unavailable",
            now = 99L,
        )

        assertEquals(paused, unchanged)
    }

    @Test
    fun unavailableOnlineAccess_replacesObsoleteWebsiteAuthorizationPause() {
        val awaitingAuthorization = NexusImportState.pauseForWebsiteAuthorization(
            install = install(status = ModInstallStatus.IMPORTING, fileId = 10L),
            message = "Authorize this file on Nexus Mods",
        )

        val paused = NexusImportState.pauseWhileOnlineAccessUnavailable(
            install = awaitingAuthorization,
            message = "Nexus downloads unavailable",
            now = 99L,
        )

        assertEquals("Nexus downloads unavailable", JSONObject(paused.metadataJson).getString("error"))
        assertFalse(NexusImportState.isWaitingForWebsiteAuthorization(paused))
        assertEquals(99L, paused.updatedAt)
    }

    @Test
    fun userMessage_expiredAuthorizationAlwaysIncludesRecoveryStep() {
        val error = NexusApiException(
            message = "The Nexus website download authorization expired",
            statusCode = 410,
            reason = NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED,
        )

        assertEquals(
            "The Nexus website download authorization expired. Open Nexus Mods and authorize the file again.",
            NexusImportState.userMessage(error),
        )
    }

    @Test
    fun userMessage_expiredAuthorizationPrefersLocalizedOverride() {
        val error = NexusApiException(
            message = "The Nexus website download authorization expired",
            statusCode = 410,
            reason = NexusApiErrorReason.DOWNLOAD_AUTHORIZATION_EXPIRED,
        )

        assertEquals(
            "Autorisierung abgelaufen",
            NexusImportState.userMessage(
                error = error,
                expiredAuthorizationMessage = "Autorisierung abgelaufen",
            ),
        )
    }

    @Test
    fun userMessage_authenticationPrefersLocalizedOverride() {
        val error = NexusApiException(
            message = "Nexus account authorization was rejected",
            statusCode = 401,
            reason = NexusApiErrorReason.AUTHENTICATION,
        )

        assertEquals(
            "Nexus integration temporarily unavailable",
            NexusImportState.userMessage(
                error = error,
                authenticationMessage = "Nexus integration temporarily unavailable",
            ),
        )

        assertEquals(
            "Nexus integration temporarily unavailable",
            NexusImportState.userMessage(
                error = NexusApiException("Request failed", statusCode = 401),
                authenticationMessage = "Nexus integration temporarily unavailable",
            ),
        )
    }

    private fun install(
        status: ModInstallStatus,
        fileId: Long,
        updatedAt: Long = 1L,
        metadataJson: String = NexusImportState.importMetadata("summary"),
    ): ModInstall =
        ModInstall(
            installId = "app_skyrim_100_$fileId",
            appId = "app",
            nexusGameDomain = "skyrim",
            nexusModId = 100L,
            nexusFileId = fileId,
            modName = "Mod",
            fileName = "mod-$fileId.zip",
            version = "1.0",
            sizeBytes = 1024L,
            archivePath = "/archives/mod-$fileId.zip",
            extractedPath = "/extracted/mod-$fileId",
            status = status.name,
            createdAt = 1L,
            updatedAt = updatedAt,
            downloadedAt = 1L,
            metadataJson = metadataJson,
        )
}
