package app.gamenative.service.cloud

// chromium-runtime files that must NEVER round-trip through cloud. NW.js/Electron save roots usually
// hold the whole chromium User Data profile beside the game save, so recursive uploaders would ship
// crash dumps (~91MB), metrics archives, shader caches -- no save value, and they blow cloud quotas.
// Local Storage/leveldb and IndexedDB ARE save state and must stay.
object SyncFileFilter {

    // any matching path segment excludes the whole path.
    private val EXCLUDED_DIR_COMPONENTS = setOf(
        "Crashpad",
        "ShaderCache",
        "GPUCache",
        "data_reduction_proxy_leveldb",
        "Site Characteristics Database",
        "Stability",
    )

    // User Data root runtime files and the advisory leveldb LOCK; none carry save state.
    private val EXCLUDED_FILENAMES = setOf(
        "previews_opt_out.db",
        "page_load_capping_opt_out.db",
        "chrome_debug.log",
        "First Run",
        "Last Browser",
        "Last Version",
        "Local State",
        "Variations",
        "LOCK",
    )

    // chromium varies the suffix (BrowserMetrics-spare.pma, BrowserMetrics.txt, ...).
    private val EXCLUDED_FILENAME_PREFIXES = listOf(
        "BrowserMetrics",
    )

    // case-insensitive.
    private val EXCLUDED_EXTENSIONS = setOf(
        ".dmp",  // crashpad minidumps
        ".pma",  // chromium metrics archives
        ".gntmp", // our own staging file for an atomic save write (see html5/AtomicSaveWrite.kt)
    )

    // our own LevelDbRewriter.swapIn scratch DIRECTORIES, so matched on any path component. one outliving
    // its swap means the process was killed mid-rename; never save state.
    private val EXCLUDED_COMPONENT_SUFFIXES = listOf(".gnnew", ".gnold")

    fun isChromiumInternal(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val normalized = relativePath.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false

        if (parts.any { it in EXCLUDED_DIR_COMPONENTS }) return true
        if (parts.any { part -> EXCLUDED_COMPONENT_SUFFIXES.any { part.endsWith(it) } }) return true

        val basename = parts.last()
        if (basename in EXCLUDED_FILENAMES) return true
        if (EXCLUDED_FILENAME_PREFIXES.any { basename.startsWith(it) }) return true
        if (EXCLUDED_EXTENSIONS.any { basename.lowercase().endsWith(it) }) return true

        return false
    }
}
