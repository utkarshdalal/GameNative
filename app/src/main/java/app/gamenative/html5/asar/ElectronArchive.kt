package app.gamenative.html5.asar

import kotlinx.serialization.json.JsonObject

// common view over packed (asar) and unpacked (resources/app/) electron sources.
interface ElectronArchive : AutoCloseable {
    fun read(relPath: String): ByteArray?
    fun exists(relPath: String): Boolean
    fun listFiles(relPath: String): List<String>
    fun packageJson(): JsonObject?
}
