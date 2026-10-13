package app.gamenative.mods

import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusCollectionReusePolicyTest {
    @Test
    fun matchesExactFile_acceptsSameDomainModAndFile() {
        val install = install(fileId = 200L)
        val collectionFile = collectionFile(fileId = 200L)
        val resolvedFile = nexusFile(fileId = 200L)

        assertTrue(NexusCollectionReusePolicy.matchesExactFile(install, collectionFile, resolvedFile))
    }

    @Test
    fun matchesExactFile_rejectsDifferentFileFromSameMod() {
        val install = install(fileId = 199L)
        val collectionFile = collectionFile(fileId = 200L)
        val resolvedFile = nexusFile(fileId = 200L)

        assertFalse(NexusCollectionReusePolicy.matchesExactFile(install, collectionFile, resolvedFile))
    }

    @Test
    fun matchesExactFile_usesResolvedFileWhenCollectionFileIdIsMissing() {
        val install = install(fileId = 200L)
        val collectionFile = collectionFile(fileId = 0L)
        val resolvedFile = nexusFile(fileId = 200L)

        assertTrue(NexusCollectionReusePolicy.matchesExactFile(install, collectionFile, resolvedFile))
    }

    private fun install(fileId: Long): ModInstall =
        ModInstall(
            installId = "app_skyrim_100_$fileId",
            appId = "app",
            nexusGameDomain = "skyrim",
            nexusModId = 100L,
            nexusFileId = fileId,
            modName = "Mod",
            fileName = "mod-$fileId.zip",
            archivePath = "/archives/mod-$fileId.zip",
            extractedPath = "/extracted/mod-$fileId",
            status = ModInstallStatus.READY.name,
        )

    private fun collectionFile(fileId: Long): NexusCollectionFile =
        NexusCollectionFile(
            gameDomain = "skyrim",
            modId = 100L,
            fileId = fileId,
            modName = "Mod",
            fileName = "mod-$fileId.zip",
        )

    private fun nexusFile(fileId: Long): NexusModFile =
        NexusModFile(
            fileId = fileId,
            name = "Main file",
            version = "1.0",
            fileName = "mod-$fileId.zip",
            sizeBytes = 1024L,
            uploadedTimestamp = 1L,
        )
}
