package app.gamenative.mods

import app.gamenative.data.ModInstall

internal object NexusCollectionReusePolicy {
    fun matchesExactFile(
        install: ModInstall,
        collectionFile: NexusCollectionFile,
        resolvedFile: NexusModFile,
    ): Boolean {
        val expectedFileId = collectionFile.fileId.takeIf { it > 0L } ?: resolvedFile.fileId
        return install.nexusGameDomain == collectionFile.gameDomain &&
            install.nexusModId == collectionFile.modId &&
            install.nexusFileId == expectedFileId
    }
}
