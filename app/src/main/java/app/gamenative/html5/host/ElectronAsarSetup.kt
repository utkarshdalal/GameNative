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

// owns the archive handle; caller must close() on WebView teardown.
// packed asar wins over unpacked resources/app -- it's what the title actually runs.
class ElectronAsarSetup private constructor(
    val archive: ElectronArchive,
) {
    // null surfaces a snackbar; the bridge falls back to the install-dir sandbox root and app.getPath
    // fails loudly with NOT_IMPLEMENTED_V1.
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

    // same heuristic as the interceptor. an entry in a subdir (src/index.html) must be loaded by its real
    // URL, or relative sub-resource URLs resolve against the wrong path and 404.
    val resolvedEntry: String? by lazy {
        AsarAssetInterceptor.resolveEntry(archive)
    }

    // without preload.js, whatever it publishes via contextBridge.exposeInMainWorld stays undefined.
    val preloadUrl: String? by lazy {
        if (archive.exists("preload.js")) "/preload.js" else null
    }

    // __gnElectronCtx, so JS app.getPath is a map lookup rather than a bridge round-trip.
    fun buildContext(): Map<String, String>? {
        val name = productName ?: return null
        return buildElectronCtx(name, version)
    }

    fun close() {
        runCatching { archive.close() }
    }

    companion object {
        private const val TAG = "ElectronAsarSetup"

        // an UNPACKED resources/app/ is ambiguous (disk-served titles keep payloads there too), so only
        // these packs route it through the archive interceptor. a real .asar file is not gated.
        private val UNPACKED_ARCHIVE_ENGINES = setOf(EnginePackId.ELECTRON, EnginePackId.TYRANO)

        // null = no archive; AssetInterceptor serves from disk.
        //
        // a real resources/app.asar is opened for ANY pack: the fingerprinter probes inside the asar, so an
        // asar-packed RMMV / C3 / NW.js title fingerprints as its true engine but its files exist only in the
        // archive. pack identity picks the shim set; the interceptor just moves bytes.
        fun open(installPath: String, profile: EngineProfile?): ElectronAsarSetup? {
            val base = File(installPath)
            val asarFile = listOf(
                File(base, "resources/app.asar"),
                File(base, "resources/electron.asar"),
            ).firstOrNull { it.isFile }
            val unpackedRoot = File(base, "resources/app").takeIf {
                profile?.engine in UNPACKED_ARCHIVE_ENGINES && File(it, "package.json").isFile
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
