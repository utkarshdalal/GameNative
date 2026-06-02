package app.gamenative.html5.asar

import kotlinx.serialization.json.JsonObject

// shared abstraction over packed (asar) and unpacked (resources/app/) electron sources.
// extracted so AsarAssetInterceptor + AsarDirectoryRef accept either backend without
// duplicating the request/path-resolution code.
interface ElectronArchive : AutoCloseable {
    fun read(relPath: String): ByteArray?
    fun exists(relPath: String): Boolean
    fun listFiles(relPath: String): List<String>
    fun packageJson(): JsonObject?
}
