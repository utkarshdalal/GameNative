package app.gamenative.utils

import `in`.dragonbra.javasteam.types.DepotManifest
import timber.log.Timber
import java.io.File

object DepotManifestFiles {
    fun manifestFile(appDirPath: String, depotId: Int, gid: Long): File =
        File(appDirPath, ".DepotDownloader/${depotId}_${gid.toULong()}.manifest")

    fun decryptFilenames(file: File, depotKey: ByteArray): Boolean = runCatching {
        val manifest = DepotManifest.loadFromFile(file.absolutePath) ?: return false
        if (!manifest.filenamesEncrypted) return false
        if (!manifest.decryptFilenames(depotKey)) {
            Timber.w("Could not decrypt filenames in ${file.name}")
            return false
        }
        manifest.saveToFile(file.absolutePath)
        Timber.i("Decrypted filenames in ${file.name}")
        true
    }.getOrElse { e ->
        Timber.w(e, "Could not decrypt filenames in ${file.name}")
        false
    }
}
