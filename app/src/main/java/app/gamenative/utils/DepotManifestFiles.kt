package app.gamenative.utils

import `in`.dragonbra.javasteam.types.DepotManifest
import timber.log.Timber
import java.io.File

/**
 * Cached depot manifests under `<game dir>/.DepotDownloader`. Older titles ship them with
 * encrypted filenames; every reader (acf CheckGuid, exe detection) needs the plain names.
 */
object DepotManifestFiles {
    fun manifestFile(appDirPath: String, depotId: Int, gid: Long): File =
        File(appDirPath, ".DepotDownloader/${depotId}_${gid.toULong()}.manifest")

    fun hasEncryptedFilenames(file: File): Boolean =
        file.isFile && runCatching { DepotManifest.loadFromFile(file.absolutePath)?.filenamesEncrypted }.getOrNull() == true

    /** Returns true when the file held encrypted names and was rewritten with plain ones. */
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
