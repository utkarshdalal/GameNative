package app.gamenative.html5.asar

import kotlinx.serialization.json.JsonObject

// common view over packed (asar) and unpacked (resources/app/) electron sources.
interface ElectronArchive : AutoCloseable {
    fun read(relPath: String): ByteArray?

    // file size without reading it; null for a missing path or a directory.
    fun size(relPath: String): Long? = read(relPath)?.size?.toLong()
    fun exists(relPath: String): Boolean
    fun listFiles(relPath: String): List<String>
    fun packageJson(): JsonObject?
}
