package app.gamenative.html5.asar

import app.gamenative.html5.fingerprint.DirectoryRef

// caller owns the archive lifetime; this class never closes it.
class AsarDirectoryRef(private val archive: ElectronArchive) : DirectoryRef {
    override fun exists(relPath: String): Boolean = archive.exists(relPath)
    override fun listFiles(relPath: String): List<String> = archive.listFiles(relPath)
}
