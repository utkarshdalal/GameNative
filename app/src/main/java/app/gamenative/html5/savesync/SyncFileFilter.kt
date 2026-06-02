package app.gamenative.html5.savesync

// shared denylist for Chromium-runtime files that must NEVER round-trip through any cloud
// save provider. consulted by GOG/Steam/Epic cloud sync wherever they recurse into a
// wine-prefix-rooted save dir for HTML5 NW.js / Electron titles.
//
// motivation: NW.js titles' save root is typically `<APPDATA>/<Title>/` which contains the
// game save (cc.save, etc.) AND the entire chromium User Data profile. recursive uploaders
// will pick up Crashpad dumps (~91MB), BrowserMetrics-spare.pma (~4MB), ShaderCache, GPUCache,
// etc. -- chromium internals that have zero save value but blow through cloud quotas and
// re-download/re-upload on every device transition.
//
// what we keep (load-bearing for save round-trips):
// - cc.save / *.save / *.rpgsave at any depth
// - User Data/Default/Local Storage/leveldb/** (chromium-LS state, dual-write hypothesis)
// - User Data/Default/IndexedDB/** (when present)
//
// what we drop:
// - any path component matching a Chromium-runtime directory (Crashpad, ShaderCache, ...)
// - basenames matching well-known internal files (previews_opt_out.db, ...)
// - basenames prefixed with a known internal stem (BrowserMetrics-*)
// - any *.dmp or *.pma file regardless of location
object SyncFileFilter {

    // exact path-component matches. paths are split on / (after \ → / normalization) and
    // any segment matching one of these excludes the whole path.
    private val EXCLUDED_DIR_COMPONENTS = setOf(
        "Crashpad",
        "ShaderCache",
        "GPUCache",
        "data_reduction_proxy_leveldb",
        "Site Characteristics Database",
        "Stability",
    )

    // exact basename matches. covers chromium User Data root-level runtime files (First Run,
    // Last Browser, etc.) and the advisory leveldb LOCK -- none of these carry save state but
    // all show up in pack:nwjs UFS patterns rooted at the User Data dir. chrome_debug.log
    // alone hits multi-MB in real installs and dwarfs save payload if uploaded.
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

    // basename prefix matches. catches BrowserMetrics-spare.pma, BrowserMetrics-active.pma,
    // BrowserMetrics.txt -- chromium versions the suffix.
    private val EXCLUDED_FILENAME_PREFIXES = listOf(
        "BrowserMetrics",
    )

    // basename extension matches (case-insensitive). chromium-runtime artifacts that show up
    // anywhere in the User Data tree.
    private val EXCLUDED_EXTENSIONS = setOf(
        ".dmp",  // crashpad minidumps
        ".pma",  // chromium metrics archives
    )

    fun isChromiumInternal(relativePath: String): Boolean {
        if (relativePath.isEmpty()) return false
        val normalized = relativePath.replace('\\', '/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false

        if (parts.any { it in EXCLUDED_DIR_COMPONENTS }) return true

        val basename = parts.last()
        if (basename in EXCLUDED_FILENAMES) return true
        if (EXCLUDED_FILENAME_PREFIXES.any { basename.startsWith(it) }) return true
        if (EXCLUDED_EXTENSIONS.any { basename.lowercase().endsWith(it) }) return true

        return false
    }
}
