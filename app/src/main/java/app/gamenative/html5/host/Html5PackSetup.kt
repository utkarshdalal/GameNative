package app.gamenative.html5.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.gamenative.R
import app.gamenative.html5.packed.C3Bundle
import app.gamenative.html5.packed.PackedArchive
import app.gamenative.html5.packed.ZipPackedArchive
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.Patch
import app.gamenative.runtime.WebViewContainer
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber

// per-launch pack/asset resolution. the PackedArchive / ElectronAsarSetup handles are closed in Html5TeardownEffect.
internal data class Html5PackSetup(
    val installDir: File,
    val omoriContext: OmoriDecryptContext?,
    val decryptContext: Html5DecryptContext?,
    val nwArgvJson: String?,
    val nwAppDataPath: String?,
    val mainModuleFilename: String,
    val packedArchive: PackedArchive?,
    val tpatchOverlays: List<ZipFile>,
    val electronSetup: ElectronAsarSetup?,
    val electronCtx: Map<String, String>?,
)

private val ELECTRON_CONTEXT_ENGINES = setOf(EnginePackId.ELECTRON, EnginePackId.TYRANO)

@Composable
internal fun rememberHtml5PackSetup(
    context: android.content.Context,
    container: WebViewContainer,
    profile: EngineProfile?,
): Html5PackSetup {
    // webRoot: "" for flat packs, "www" for RMMV. for packed roots ZipAssetInterceptor serves and this is unused.
    val installDir: File = remember(container.installPath, container.webRoot) {
        val base = File(container.installPath)
        if (container.webRoot.isBlank() || PackedArchive.isPackedWebRoot(container.webRoot)) {
            base
        } else {
            File(base, container.webRoot)
        }
    }

    // registry: TitleQuirks.OMORI. a blank key serves .OMORI files as ciphertext (JS syntax errors).
    val omoriContext = remember(profile, container.decryptionKey) {
        val needsOmoriDecrypt = profile?.patches?.any {
            it is Patch.AssetDecrypt && it.kind == "omori-aes-ctr"
        } == true
        if (!needsOmoriDecrypt) return@remember null
        OmoriDecryptContext.fromSteamLaunchArg(container.decryptionKey.takeIf { it.isNotEmpty() })
    }

    val decryptContext = remember(installDir, profile, omoriContext) {
        val needsDecrypt = profile?.patches?.any {
            it is Patch.AssetDecrypt && it.kind == "rpgmv-xor"
        } == true
        if (!needsDecrypt) return@remember null
        // OMORI's XOR key lives in AES-encrypted System.KEL instead of System.json.
        val omoriXorKey = omoriContext?.resolveRmmvXorKey(installDir)
        Html5DecryptContext(installDir, preResolvedKey = omoriXorKey)
    }

    // mirrors the Steam launch arg into nw.App.argv; OMORI's plugins read their decrypt key from it.
    val nwArgvJson = remember(container.decryptionKey) {
        val launchArg = container.decryptionKey.takeIf { it.isNotEmpty() } ?: return@remember null
        org.json.JSONArray().apply { put(launchArg) }.toString()
    }

    // Impact-engine titles fall back to localStorage (saves never hit disk) when nw.App.dataPath is empty.
    // real NW.js returns %LOCALAPPDATA%\<package.json name>; the install dir name usually matches.
    val nwAppDataPath = remember(profile?.engine, container.installPath) {
        if (profile?.engine != EnginePackId.NWJS) return@remember null
        val installPath = container.installPath.takeIf { it.isNotBlank() } ?: return@remember null
        val appName = java.io.File(installPath).name.takeIf { it.isNotBlank() } ?: return@remember null
        "C:\\Users\\xuser\\AppData\\Local\\$appName"
    }

    // mirrors NW.js's package.json `main` so plugins using path.dirname(filename) land on the content folder.
    // packed roots are flat: paths are archive-relative.
    val mainModuleFilename = remember(container.webRoot) {
        val webRoot = container.webRoot
        if (webRoot.isBlank() || PackedArchive.isPackedWebRoot(webRoot)) "index.html"
        else "$webRoot/index.html"
    }

    val packedArchive: PackedArchive? = remember(container.installPath, container.webRoot) {
        val webRoot = container.webRoot
        when {
            webRoot.startsWith(PackedArchive.ZIP_PREFIX) -> runCatching {
                val zipName = webRoot.removePrefix(PackedArchive.ZIP_PREFIX)
                ZipPackedArchive(ZipFile.builder().setFile(File(container.installPath, zipName)).get())
            }.onFailure { Timber.tag("WebViewScreen").e(it, "zip open failed") }.getOrNull()
            webRoot.startsWith(PackedArchive.C3_BUNDLE_PREFIX) -> runCatching {
                C3Bundle.open(File(container.installPath, webRoot.removePrefix(PackedArchive.C3_BUNDLE_PREFIX)))
            }.onFailure { Timber.tag("WebViewScreen").e(it, "c3 bundle open failed") }.getOrNull()
            else -> null
        }
    }

    // registry: TitleQuirks.TYRANO_TPATCH.
    val tpatchOverlays: List<ZipFile> = remember(container.installPath, profile?.engine) {
        TyranoTpatchOverlay.scan(container.installPath, profile?.engine)
    }

    val electronSetup: ElectronAsarSetup? = remember(container.installPath, profile?.engine) {
        ElectronAsarSetup.open(container.installPath, profile)
    }

    // electron-only: asar-packed non-electron titles open the archive too but have no productName.
    LaunchedEffect(electronSetup, electronSetup?.productName, profile?.engine) {
        if (electronSetup != null &&
            profile?.engine == EnginePackId.ELECTRON &&
            electronSetup.productName.isNullOrBlank()
        ) {
            SnackbarManager.show(context.getString(R.string.html5_electron_missing_product_name))
        }
    }

    // packs that merely live inside an asar must not be handed Electron app identity.
    val electronCtx: Map<String, String>? = remember(electronSetup, container.id, profile?.engine) {
        if (profile == null || profile.engine !in ELECTRON_CONTEXT_ENGINES) return@remember null
        electronSetup?.buildContext()
    }

    return Html5PackSetup(
        installDir = installDir,
        omoriContext = omoriContext,
        decryptContext = decryptContext,
        nwArgvJson = nwArgvJson,
        nwAppDataPath = nwAppDataPath,
        mainModuleFilename = mainModuleFilename,
        packedArchive = packedArchive,
        tpatchOverlays = tpatchOverlays,
        electronSetup = electronSetup,
        electronCtx = electronCtx,
    )
}
