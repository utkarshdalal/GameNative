package app.gamenative.theme

import android.content.Context
import android.widget.Toast
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.theme.io.ThemeLoader
import app.gamenative.theme.io.ThemeXmlMapper
import app.gamenative.theme.model.ThemeDefinition
import app.gamenative.theme.model.ThemeEngine
import app.gamenative.theme.model.ThemeLoadResult
import app.gamenative.theme.validate.ThemeValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.nio.file.Paths

/**
 * ThemeManager: enumerates themes (built-in assets and user directory), persists selection,
 * loads manifests with compatibility checks, and exposes a dev-only hot reload.
 *
 * This step keeps integration minimal: we parse only the manifest and notify listeners
 * when selection or reload occurs. Rendering hookup will follow in later steps.
 */
object ThemeManager {

    enum class Source { BuiltIn, User }

    data class ThemeEntry(
        val id: String,
        val name: String, // display name from manifest title
        val source: Source,
        val location: String, // folder path or asset subfolder
        val manifest: ManifestLite,
    )

    data class ManifestLite(
        val id: String,
        val title: String, // human-readable display name
        val version: String,
        val engineVersion: String,
        val minAppVersion: String,
        val maxAppVersion: String?,
    ) {
        /** Extract major version number from semantic version string (e.g., "1.0.0" -> 1) */
        val engineMajorVersion: Int
            get() = engineVersion.split(".").firstOrNull()?.toIntOrNull() ?: 0
    }

    private lateinit var appCtx: Context

    private val scope = CoroutineScope(Dispatchers.Main)

    private val _availableThemes = MutableStateFlow<List<ThemeEntry>>(emptyList())
    val availableThemes: StateFlow<List<ThemeEntry>> = _availableThemes.asStateFlow()

    private val _selectedThemeId = MutableStateFlow<String?>(null)
    val selectedThemeId: StateFlow<String?> = _selectedThemeId.asStateFlow()

    // Emit a monotonically increasing token whenever we re-parse the active theme (dev-only reload)
    private val _reloadTick = MutableStateFlow(0)
    val reloadTick: StateFlow<Int> = _reloadTick.asStateFlow()

    private val _activeTheme = MutableStateFlow<ThemeDefinition?>(null)
    val activeTheme: StateFlow<ThemeDefinition?> = _activeTheme.asStateFlow()

    // Store raw theme tree for re-mapping on orientation changes
    private var activeThemeTree: app.gamenative.theme.model.ThemeTree? = null
    private var lastMappedIsPortrait: Boolean? = null
    private var lastMappedScreenWidth: Int? = null

    private const val ASSETS_THEMES_ROOT = "Themes"
    private const val FALLBACK_THEME_ID = "list_view"

    fun init(context: Context) {
        appCtx = context.applicationContext
        // Perform one-time migration from legacy layout preference to theme id, before scanning
        migrateLegacyLayoutToThemeIfNeeded()
        // Initial scan + resolve selection
        scope.launch(Dispatchers.IO) {
            val list = scanAllThemes()
            _availableThemes.value = list
            val persisted = PrefManager.activeThemeId.ifBlank { FALLBACK_THEME_ID }
            val chosen = list.find { it.id.equals(persisted, ignoreCase = true) }
                ?: pickFallback(list)
            _selectedThemeId.value = chosen?.id
            if (chosen == null) {
                Timber.w("No themes found; ThemeManager availableThemes is empty")
            } else {
                // Load full theme definition
                loadAndActivateTheme(chosen)
            }
        }
    }

    /**
     * One-time migration: map old Library layout preference to a Theme id.
     * This only sets PrefManager.activeThemeId if not already chosen and if migration wasn't done.
     */
    private fun migrateLegacyLayoutToThemeIfNeeded() {
        try {
            if (PrefManager.themeLayoutMigrated) return
            // If user already has an active theme set, skip
            if (PrefManager.activeThemeId.isNotBlank()) {
                PrefManager.themeLayoutMigrated = true
                return
            }
            val layout = PrefManager.libraryLayout
            val themeId = when (layout.name) {
                // Map rough equivalents
                "LIST" -> "list_view"
                "GRID_CAPSULE" -> "capsule_grid"
                "GRID_HERO" -> "hero_grid"
                else -> FALLBACK_THEME_ID
            }
            PrefManager.activeThemeId = themeId
            PrefManager.themeLayoutMigrated = true
            Timber.i("Migrated legacy layout '%s' to theme id '%s'", layout.name, themeId)
        } catch (t: Throwable) {
            Timber.e(t, "migrateLegacyLayoutToThemeIfNeeded failed")
        }
    }

    fun getSelectedThemeEntry(): ThemeEntry? = _availableThemes.value.firstOrNull { it.id == _selectedThemeId.value }

    /**
     * Get the asset path for string resolution in the currently selected theme.
     * For built-in themes: "Themes/<themeId>"
     * For user themes: returns the user theme location
     */
    fun getActiveThemeAssetPath(): String? {
        val entry = getSelectedThemeEntry() ?: return null
        return when (entry.source) {
            Source.BuiltIn -> "$ASSETS_THEMES_ROOT/${entry.location}"
            Source.User -> entry.location
        }
    }

    fun selectTheme(id: String) {
        val match = _availableThemes.value.firstOrNull { it.id == id }
        if (match == null) {
            Timber.w("Attempted to select unknown theme id=%s", id)
            return
        }
        _selectedThemeId.value = match.id
        PrefManager.activeThemeId = match.id
        scope.launch(Dispatchers.Main) {
            Toast.makeText(appCtx, appCtx.getString(R.string.theme_applied_toast, match.id), Toast.LENGTH_SHORT).show()
        }
        Timber.i("Theme selected: %s (%s)", match.id, match.source)
        // Load full theme definition
        scope.launch(Dispatchers.IO) {
            try {
                loadAndActivateTheme(match)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to activate theme after selection")
            }
        }
    }

    fun reloadActiveThemeDevOnly() {
        if (!BuildConfig.DEBUG) return
        val current = getSelectedThemeEntry() ?: return
        scope.launch(Dispatchers.IO) {
            try {
                // Re-parse manifests to reflect any changes on disk/assets
                val list = scanAllThemes()
                _availableThemes.value = list
                val stillThere = list.find { it.id == current.id }
                if (stillThere == null) {
                    Timber.w("Active theme missing after reload; falling back")
                    applyFallbackWithToast(list)
                    pickFallback(list)?.let { fb -> loadAndActivateTheme(fb) }
                } else {
                    // Re-load full theme definition
                    loadAndActivateTheme(stillThere)
                    // Bump reload tick so observers can diff-apply changes if needed
                    _reloadTick.value = _reloadTick.value + 1
                    Timber.i("Theme reloaded (dev): %s", current.id)
                }
            } catch (t: Throwable) {
                Timber.e(t, "Reload failed; falling back")
                val list2 = scanAllThemes()
                applyFallbackWithToast(list2)
                pickFallback(list2)?.let { fb -> loadAndActivateTheme(fb) }
            }
        }
    }

    // --- Scanning & parsing ---

    private fun scanAllThemes(): List<ThemeEntry> {
        val builtIns = scanBuiltInThemes()
        val users = scanUserThemes()
        return (builtIns + users)
            .distinctBy { it.id }
            .sortedWith(compareBy<ThemeEntry> { it.source }.thenBy { it.id.lowercase() })
    }

    private fun scanBuiltInThemes(): List<ThemeEntry> {
        val results = mutableListOf<ThemeEntry>()
        try {
            val subdirs = appCtx.assets.list(ASSETS_THEMES_ROOT) ?: emptyArray()
            subdirs.forEach { dir ->
                val manifestPath = "$ASSETS_THEMES_ROOT/$dir/manifest.xml"
                val manifest = parseManifest(appCtx.assets.open(manifestPath)) ?: return@forEach
                if (!isCompatible(manifest)) return@forEach
                results += ThemeEntry(
                    id = manifest.id,
                    name = manifest.title,
                    source = Source.BuiltIn,
                    location = dir,
                    manifest = manifest,
                )
            }
        } catch (t: Throwable) {
            Timber.e(t, "scanBuiltInThemes failed")
        }
        return results
    }

    private fun scanUserThemes(): List<ThemeEntry> {
        val results = mutableListOf<ThemeEntry>()
        return try {
            val root = getUserThemesRoot() ?: return emptyList()
            if (!root.exists() || !root.isDirectory) return emptyList()
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val manifestFile = File(dir, "manifest.xml")
                if (!manifestFile.exists()) return@forEach
                val manifest = manifestFile.inputStream().use { parseManifest(it) } ?: return@forEach
                if (!isCompatible(manifest)) return@forEach
                results += ThemeEntry(
                    id = manifest.id,
                    name = manifest.title,
                    source = Source.User,
                    location = dir.absolutePath,
                    manifest = manifest,
                )
            }
            results
        } catch (t: Throwable) {
            Timber.e(t, "scanUserThemes failed")
            results
        }
    }

    private fun getUserThemesRoot(): File? {
        val ext = PrefManager.externalStoragePath
        if (ext.isBlank()) return null
        val p = Paths.get(ext, "GameNative", "Themes").toFile()
        return p
    }

    private fun parseManifest(input: InputStream): ManifestLite? {
        return try {
            input.use {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(it, null)
                var id: String? = null
                var title: String? = null
                var version: String? = null
                var engineVersion: String? = null
                var minAppVersion: String? = null
                var maxAppVersion: String? = null
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name.lowercase()) {
                            "id" -> id = parser.nextText()?.trim()
                            "title" -> title = parser.nextText()?.trim()
                            "version" -> version = parser.nextText()?.trim()
                            "engineversion" -> engineVersion = parser.nextText()?.trim()
                            "minappversion" -> minAppVersion = parser.nextText()?.trim()
                            "maxappversion" -> maxAppVersion = parser.nextText()?.trim()
                        }
                    }
                    event = parser.next()
                }
                if (id.isNullOrBlank() || version.isNullOrBlank() || engineVersion.isNullOrBlank() || minAppVersion.isNullOrBlank()) {
                    Timber.w("Manifest missing required fields")
                    null
                } else {
                    // Fall back to id if title not specified
                    ManifestLite(id!!, title ?: id!!, version!!, engineVersion!!, minAppVersion!!, maxAppVersion)
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "parseManifest failed")
            null
        }
    }

    private fun isCompatible(m: ManifestLite): Boolean {
        if (m.engineMajorVersion != ThemeEngine.ENGINE_MAJOR) {
            Timber.i("Ignoring theme %s due to engineVersion=%s (major=%d, expected=%d)", 
                m.id, m.engineVersion, m.engineMajorVersion, ThemeEngine.ENGINE_MAJOR)
            return false
        }
        // App version compatibility: The app doesn't expose semantic version; accept all for now
        return true
    }

    private fun pickFallback(list: List<ThemeEntry>): ThemeEntry? {
        return list.firstOrNull { it.id.equals(FALLBACK_THEME_ID, ignoreCase = true) }
            ?: list.firstOrNull { it.source == Source.BuiltIn }
            ?: list.firstOrNull()
    }

    private fun applyFallbackWithToast(list: List<ThemeEntry>) {
        val fb = pickFallback(list)
        if (fb == null) {
            Timber.w("No fallback theme available")
            return
        }
        _selectedThemeId.value = fb.id
        PrefManager.activeThemeId = fb.id
        // Ensure toast is shown on the main thread to avoid NPE (Looper not prepared)
        scope.launch(Dispatchers.Main) {
            Toast.makeText(appCtx, appCtx.getString(R.string.theme_fallback_toast, fb.id), Toast.LENGTH_LONG).show()
        }
        Timber.i("Fell back to theme: %s", fb.id)
    }

    // --- Activation ---
    /**
     * Load, validate, map and activate the given theme entry.
     * Handles built-in assets by copying into cache first.
     */
    private fun loadAndActivateTheme(entry: ThemeEntry) {
        try {
            val loadDir: File = when (entry.source) {
                Source.User -> File(entry.location)
                Source.BuiltIn -> {
                    // Copy built-in assets from APK to cache dir
                    val cacheRoot = File(appCtx.cacheDir, "themes_cache")
                    val dst = File(cacheRoot, entry.location)
                    copyAssetsThemeTo(dst, entry.location)
                    dst
                }
            }

            val loader = ThemeLoader()
            when (val res = loader.load(loadDir.absolutePath)) {
                is ThemeLoadResult.Success -> {
                    // Validate
                    val validation = ThemeValidator.validate(
                        res.tree,
                        appVersion = BuildConfig.VERSION_NAME,
                        engineMajor = ThemeEngine.ENGINE_MAJOR,
                    )
                    if (validation.hasBlocking()) {
                        validation.issues.forEach { issue ->
                            Timber.e("[THEME_V2] %s: %s", issue.code.name, issue.message)
                        }
                        Timber.w("Validation failed for theme %s: %s", entry.id, validation.issues.joinToString { "${it.code.name}: ${it.message}" })
                        val all = _availableThemes.value
                        applyFallbackWithToast(all)
                        pickFallback(all)?.let { fb -> if (fb.id != entry.id) loadAndActivateTheme(fb) }
                        return
                    }
                    // Store raw tree for orientation-aware remapping
                    activeThemeTree = res.tree
                    lastMappedIsPortrait = null
                    lastMappedScreenWidth = null
                    
                    // Map to runtime model (initial mapping without orientation context)
                    val def: ThemeDefinition = ThemeXmlMapper.map(res.tree)
                    _activeTheme.value = def
                    Timber.i("Theme activated: %s (%s)", entry.id, entry.source)
                }
                is ThemeLoadResult.Failure -> {
                    Timber.w("Failed to load theme %s: %s", entry.id, res.errors.joinToString { it.code })
                    val all = _availableThemes.value
                    applyFallbackWithToast(all)
                    pickFallback(all)?.let { fb -> if (fb.id != entry.id) loadAndActivateTheme(fb) }
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "loadAndActivateTheme crashed for %s", entry.id)
            val all = _availableThemes.value
            applyFallbackWithToast(all)
            pickFallback(all)?.let { fb -> if (fb.id != entry.id) loadAndActivateTheme(fb) }
        }
    }

    // --- Assets copy helpers ---
    private fun copyAssetsThemeTo(destDir: File, assetSubdir: String) {
        try {
            if (!destDir.exists()) destDir.mkdirs()
            val base = "$ASSETS_THEMES_ROOT/$assetSubdir"
            // Avoid double-nesting the top-level theme folder (destDir already points to it)
            val children = appCtx.assets.list(base) ?: emptyArray()
            children.forEach { child ->
                val childAssetPath = "$base/$child"
                val childList = try { appCtx.assets.list(childAssetPath) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
                if (childList.isEmpty()) {
                    // It's a file directly under the theme root
                    copyAssetFile(childAssetPath, File(destDir, child))
                } else {
                    // It's a subdirectory; copy recursively into destDir/child
                    copyAssetDirRecursive(childAssetPath, File(destDir, child))
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed to copy built-in theme assets to cache")
        }
    }

    private fun copyAssetDirRecursive(assetPath: String, destDir: File) {
        val list = try { appCtx.assets.list(assetPath) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        if (list.isEmpty()) {
            // It's a file
            copyAssetFile(assetPath, File(destDir, File(assetPath).name))
            return
        }
        // It's a directory
        val dirName = assetPath.substringAfterLast('/')
        val currentDest = if (dirName.isNotEmpty()) File(destDir, dirName) else destDir
        if (!currentDest.exists()) currentDest.mkdirs()
        list.forEach { child ->
            val childAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
            val childList = try { appCtx.assets.list(childAssetPath) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
            if (childList.isEmpty()) {
                copyAssetFile(childAssetPath, File(currentDest, child))
            } else {
                copyAssetDirRecursive(childAssetPath, currentDest)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, outFile: File) {
        try {
            if (!outFile.parentFile.exists()) outFile.parentFile.mkdirs()
            appCtx.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Failed copying asset file %s", assetPath)
        }
    }

    // --- Orientation-aware remapping ---
    
    /**
     * Re-map the active theme with orientation-aware variable resolution.
     * Call this when screen orientation or size changes significantly.
     * 
     * @param isPortrait True if current orientation is portrait
     * @param screenWidthDp Current screen width in dp
     * @return True if theme was remapped, false if no change needed
     */
    fun remapForOrientation(isPortrait: Boolean, screenWidthDp: Int): Boolean {
        val tree = activeThemeTree ?: return false
        
        // Skip if orientation hasn't changed
        if (lastMappedIsPortrait == isPortrait && lastMappedScreenWidth == screenWidthDp) {
            return false
        }
        
        // Skip if no breakpoints (no orientation-specific overrides)
        if (tree.breakpoints.isEmpty()) {
            lastMappedIsPortrait = isPortrait
            lastMappedScreenWidth = screenWidthDp
            return false
        }
        
        // Resolve variables with breakpoint overrides
        val resolvedVariables = app.gamenative.theme.runtime.VariableResolver.resolveWithBreakpoints(
            baseVariables = tree.variables,
            breakpoints = tree.breakpoints,
            isPortrait = isPortrait,
            screenWidthDp = screenWidthDp
        )
        
        // Re-map theme with resolved variables
        val def = ThemeXmlMapper.map(tree, resolvedVariables)
        _activeTheme.value = def
        
        lastMappedIsPortrait = isPortrait
        lastMappedScreenWidth = screenWidthDp
        
        Timber.d("Theme remapped for orientation: isPortrait=%s, width=%d", isPortrait, screenWidthDp)
        return true
    }
    
    /**
     * Check if the theme has any breakpoints that might need orientation-aware handling.
     */
    fun hasBreakpoints(): Boolean = activeThemeTree?.breakpoints?.isNotEmpty() == true
}
