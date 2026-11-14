package app.gamenative.html5.fingerprint

// minimal view into a game folder. no android deps so it stays unit-testable in pure jvm.
interface DirectoryRef {
    fun exists(relPath: String): Boolean
    fun listFiles(relPath: String): List<String>

    // null if missing / unreadable / not a file, or the adapter can't read.
    fun readText(relPath: String): String? = null
}
