package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
            "Nexus API authentication failed. Check the API key and account access.",
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
