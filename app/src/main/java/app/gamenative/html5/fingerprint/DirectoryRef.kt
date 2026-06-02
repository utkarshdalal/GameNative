package app.gamenative.html5.fingerprint

// minimal view into a game folder. no android deps -- unit-testable in pure jvm.
// reused by post-download fingerprinting (same interface, different adapter).
interface DirectoryRef {
    fun exists(relPath: String): Boolean
    fun listFiles(relPath: String): List<String>

    // text contents of relPath, or null if missing / unreadable / not a file. signatures that
    // need to peek at package.json or similar small text files use this; adapters that can't
    // read (no current use case) return null and the caller falls through.
    fun readText(relPath: String): String? = null
}
