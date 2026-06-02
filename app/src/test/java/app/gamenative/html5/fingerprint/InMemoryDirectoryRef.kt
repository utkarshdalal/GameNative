package app.gamenative.html5.fingerprint

// test-only. entries are forward-slash file paths; exists/listFiles derived from this set.
// contents (optional) keyed by the same path strings, used by readText() — tests that need to
// stub package.json contents pass a Map. lets unit tests drive the fingerprinter without
// writing on-disk fixtures.
class InMemoryDirectoryRef(
    private val entries: Set<String>,
    private val contents: Map<String, String> = emptyMap(),
) : DirectoryRef {
    // convenience constructor for tests that only care about path-existence (most cases).
    constructor(entries: Set<String>) : this(entries, emptyMap())

    // tests passing only contents — entry set derived from keys.
    companion object {
        fun fromContents(contents: Map<String, String>): InMemoryDirectoryRef =
            InMemoryDirectoryRef(contents.keys, contents)
    }

    override fun exists(relPath: String): Boolean {
        val norm = relPath.trimEnd('/')
        // a DIRECTORY exists if any entry starts with "<norm>/"
        if (entries.any { it.startsWith("$norm/") }) return true
        return norm in entries
    }

    override fun listFiles(relPath: String): List<String> {
        val prefix = if (relPath.isEmpty() || relPath == ".") "" else "${relPath.trimEnd('/')}/"
        return entries.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix).substringBefore('/') }
            .distinct()
            .toList()
    }

    override fun readText(relPath: String): String? = contents[relPath.trimEnd('/')]
}
