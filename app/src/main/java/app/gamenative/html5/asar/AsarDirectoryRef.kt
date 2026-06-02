package app.gamenative.html5.asar

import app.gamenative.html5.fingerprint.DirectoryRef

// adapter over AsarArchive -- mirrors ZipDirectoryRef shape + ownership
// semantics. caller owns the AsarArchive lifetime (WebViewScreen opens in remember{},
// closes in onDispose). this class never calls archive.close().
class AsarDirectoryRef(private val archive: ElectronArchive) : DirectoryRef {
    override fun exists(relPath: String): Boolean = archive.exists(relPath)
    override fun listFiles(relPath: String): List<String> = archive.listFiles(relPath)
}
