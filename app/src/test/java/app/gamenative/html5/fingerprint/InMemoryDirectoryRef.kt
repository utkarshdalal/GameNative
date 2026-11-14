package app.gamenative.html5.fingerprint

// entries are forward-slash file paths; exists/listFiles derive from this set. contents
// (optional) are keyed by the same paths and back readText(), so tests can stub package.json
// without on-disk fixtures.
class InMemoryDirectoryRef(
    private val entries: Set<String>,
    private val contents: Map<String, String> = emptyMap(),
) : DirectoryRef {
    constructor(entries: Set<String>) : this(entries, emptyMap())

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
