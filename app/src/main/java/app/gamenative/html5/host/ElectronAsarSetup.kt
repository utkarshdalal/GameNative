package app.gamenative.html5.host

import app.gamenative.html5.asar.AsarArchive
import app.gamenative.html5.asar.AsarAssetInterceptor
import app.gamenative.html5.asar.ElectronArchive
import app.gamenative.html5.asar.UnpackedElectronArchive
import app.gamenative.html5.profile.EngineProfile
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId

// pack:electron setup. owns the ElectronArchive handle + derived metadata (productName,
// version, resolved entry filename, preload URL, __gnElectronCtx map). null when the
// container isn't pack:electron OR archive open fails. caller must invoke close() when the
// WebView tears down.
//
// archive precedence: packed asar wins over unpacked (asar is the canonical electron
// distribution and matches the title's actual runtime). unpacked layout ships
// `resources/app/package.json` with no asar.
class ElectronAsarSetup private constructor(
    val archive: ElectronArchive,
) {
    // productName fallback chain: productName → name → null. null surfaces a snackbar +
    // graceful degradation (bridge uses install-dir sandbox root; app.getPath returns
    // NOT_IMPLEMENTED_V1 loudly which is the right failure signal).
    val productName: String? by lazy {
        archive.packageJson()?.let { pkg ->
            val byProductName = (pkg["productName"] as? JsonPrimitive)?.contentOrNull
            val byName = (pkg["name"] as? JsonPrimitive)?.contentOrNull
            byProductName?.takeIf { it.isNotBlank() }
                ?: byName?.takeIf { it.isNotBlank() }
        }
    }

    val version: String? by lazy {
        archive.packageJson()?.let { pkg ->
            (pkg["version"] as? JsonPrimitive)?.contentOrNull
        }
    }

    // electron entry filename resolved by the SAME heuristic the interceptor uses, so the
    // host can load the actual entry URL instead of /index.html. needed for unpacked electron
    // titles whose entry lives in a subdir (e.g. src/index.html); without this, relative URLs
    // in the served HTML resolve under the WRONG document path and every sub-resource 404s
    // (or worse, ERR_NAME_NOT_RESOLVED via assetloader fall-through).
    val resolvedEntry: String? by lazy {
        AsarAssetInterceptor.resolveEntry(archive)
    }

    // preload.js URL -- null when archive lacks one. modern Electron titles typically ship
    // preload.js at archive root; without injecting it window.api (or whatever name preload
    // publishes via contextBridge.exposeInMainWorld) stays undefined and the game's first
    // script throws.
    val preloadUrl: String? by lazy {
        if (archive.exists("preload.js")) "/preload.js" else null
    }

    // __gnElectronCtx -- pre-computed Kotlin-side (single source of truth for path math;
    // app.getPath on JS side becomes a map lookup, no bridge round-trip). null when
    // productName missing → app.getPath routes to NOT_IMPLEMENTED_V1 which surfaces the bug
    // loudly instead of silently returning undefined.
    fun buildContext(): Map<String, String>? {
        val name = productName ?: return null
        return buildElectronCtx(name, version)
    }

    fun close() {
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "ElectronAsarSetup"

        // packs that route through the archive interceptor -- pack:electron is the canonical
        // case; pack:tyrano joins it when a Tyrano-on-Electron title ships its runtime +
        // assets inside resources/app.asar, so the disk-based AssetInterceptor finds nothing.
        // classes are independent: pack identity drives the shim set (pack:tyrano gets tyrano
        // viewport/scroll/QSA/Audio shims), the interceptor handles bytes. name kept
        // ElectronAsarSetup despite covering Tyrano -- the *mechanism* (Electron-style asar
        // archive) is what's reusable here.
        private val ARCHIVE_ENABLED_ENGINES = setOf(EnginePackId.ELECTRON, EnginePackId.TYRANO)

        // returns null if engine doesn't use the archive path OR no archive could be opened.
        // graceful fallback: interceptor stays on AssetInterceptor, bridge uses non-electron
        // sandbox root.
        fun open(installPath: String, profile: EngineProfile?): ElectronAsarSetup? {
            if (profile?.engine !in ARCHIVE_ENABLED_ENGINES) return null
            val base = File(installPath)
            val asarFile = listOf(
                File(base, "resources/app.asar"),
                File(base, "resources/electron.asar"),
            ).firstOrNull { it.isFile }
            val unpackedRoot = File(base, "resources/app").takeIf {
                File(it, "package.json").isFile
            }
            val archive: ElectronArchive? = when {
                asarFile != null -> runCatching { AsarArchive.open(asarFile) }
                    .onFailure { e -> Timber.tag(TAG).e(e, "asar open failed: %s", asarFile.absolutePath) }
                    .getOrNull()
                unpackedRoot != null -> runCatching { UnpackedElectronArchive(unpackedRoot) }
                    .onFailure { e -> Timber.tag(TAG).e(e, "unpacked open failed: %s", unpackedRoot.absolutePath) }
                    .getOrNull()
                else -> null
            }
            return archive?.let { ElectronAsarSetup(it) }
        }
    }
}
