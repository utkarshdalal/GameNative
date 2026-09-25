package app.gamenative.utils

import `in`.dragonbra.javasteam.types.DepotManifest
import timber.log.Timber
import java.io.File

object DepotManifestFiles {
    fun manifestFile(appDirPath: String, depotId: Int, gid: Long): File =
        File(appDirPath, ".DepotDownloader/${depotId}_${gid.toULong()}.manifest")

    fun hasEncryptedFilenames(file: File): Boolean =
        file.isFile && runCatching { DepotManifest.loadFromFile(file.absolutePath)?.filenamesEncrypted }.getOrNull() == true

    fun decryptFilenames(file: File, depotKey: ByteArray): Boolean {
        val manifest = runCatching { DepotManifest.loadFromFile(file.absolutePath) }.getOrNull() ?: return false
        if (!manifest.filenamesEncrypted) return false
        if (!manifest.decryptFilenames(depotKey)) {
            Timber.w("Could not decrypt filenames in ${file.name}")
            return false
        }
        manifest.saveToFile(file.absolutePath)
        Timber.i("Decrypted filenames in ${file.name}")
        return true
    }
}
