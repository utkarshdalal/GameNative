package app.gamenative.html5.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.html5.profile.Patch
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.ui.util.SnackbarManager
import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile
import timber.log.Timber

// per-launch pack/asset resolution derived from container + profile. pure data (no UI, no
// save-sync); grouped out of WebViewScreen. ZipFile / ElectronAsarSetup handles are closed in
// WebViewScreen.onDispose alongside the other per-launch resources.
internal data class Html5PackSetup(
    val installDir: File,
    val omoriContext: OmoriDecryptContext?,
    val decryptContext: Html5DecryptContext?,
    val nwArgvJson: String?,
    val nwAppDataPath: String?,
    val mainModuleFilename: String,
    val zipFile: ZipFile?,
    val tpatchOverlays: List<ZipFile>,
    val electronSetup: ElectronAsarSetup?,
    val electronCtx: Map<String, String>?,
)

@Composable
internal fun rememberHtml5PackSetup(
    context: android.content.Context,
    container: WebViewContainer,
    profile: EngineProfile?,
    appId: String,
): Html5PackSetup {
    // webRoot pins the asset folder: "" for flat packs (RMMZ/C3), "www" for RMMV.
    // AssetInterceptor reads index.html from here; WebViewAssetLoader serves sub-resources
    // from here. Keeping the single effective-root pattern avoids plumbing webRoot separately.
    // when webRoot starts with "zip:", installDir is unused -- ZipAssetInterceptor owns serving.
    val installDir: File = remember(container.installPath, container.webRoot) {
        val base = File(container.installPath)
        if (container.webRoot.isBlank() || container.webRoot.startsWith("zip:")) {
            base
        } else {
            File(base, container.webRoot)
        }
    }

    // wraps .rpgmvp/.rpgmvo streams when AssetDecrypt patches apply.
    // constructed once per (installDir, profile) -- key reads occur lazily inside.
    // OMORI AES-256-CTR decrypt context. only built when the resolved profile carries an
    // asset-decrypt patch with kind="omori-aes-ctr" (rmmv-patches.json byAppId override).
    // the key comes from Steam's PICS launch arguments -- `--<32-hex>` for OMORI.exe -- which
    // SteamService caches in-memory at PICS sync. null when the arg is absent (game won't
    // decrypt; .OMORI requests pass through as ciphertext, surfacing JS syntax errors).
    // registry: TitleQuirks.OMORI.
    val omoriContext = remember(appId, profile) {
        val needsOmoriDecrypt = profile?.patches?.any {
            it is Patch.AssetDecrypt && it.kind == "omori-aes-ctr"
        } == true
        if (!needsOmoriDecrypt) return@remember null
        val gameId = GameSource.STEAM.idOf(appId).toIntOrNull() ?: return@remember null
        val launchArg = SteamService.getLaunchArgumentsForOs(gameId)
        OmoriDecryptContext.fromSteamLaunchArg(launchArg)
    }

    val decryptContext = remember(installDir, profile, omoriContext) {
        val needsDecrypt = profile?.patches?.any {
            it is Patch.AssetDecrypt && it.kind == "rpgmv-xor"
        } == true
        if (!needsDecrypt) return@remember null
        // OMORI ships System.KEL (AES-encrypted) where stock RMMV ships System.json. when the
        // omori context is available, resolve the standard rmmv-xor key from there so that
        // .rpgmvp/.rpgmvo image+audio decryption uses the correct per-title XOR key.
        val omoriXorKey = omoriContext?.resolveRmmvXorKey(installDir)
        Html5DecryptContext(installDir, preResolvedKey = omoriXorKey)
    }

    // when a Steam launch arg is configured for this app, mirror it into window.__gnNwArgv so
    // OMORI's `String(window.nw.App.argv).replace("--", "")` resolves to the real key for
    // plugins re-decrypting `.KEL`/`.PLUTO` data files via Encryption.init(). generic to all
    // html5 containers -- only emits when launch args exist for this Steam appid.
    val nwArgvJson = remember(appId) {
        val gameId = GameSource.STEAM.idOf(appId).toIntOrNull() ?: return@remember null
        val launchArg = SteamService.getLaunchArgumentsForOs(gameId) ?: return@remember null
        org.json.JSONArray().apply { put(launchArg) }.toString()
    }

    // pack:nwjs Impact-engine titles gate save-storage adapter on `nw.App.dataPath` truthiness --
    // empty string falls them to localStorage and saves never hit disk. real NW.js returns
    // `%LOCALAPPDATA%\<package.json.name>`. derive AppName from the install dir basename
    // (matches NW.js convention for most titles where install dir == package.json name).
    // only populated for pack:nwjs -- c3/electron/rmmv don't read this.
    val nwAppDataPath = remember(profile?.engine, container.installPath) {
        if (profile?.engine != EnginePackId.NWJS) return@remember null
        val installPath = container.installPath.takeIf { it.isNotBlank() } ?: return@remember null
        val appName = java.io.File(installPath).name.takeIf { it.isNotBlank() } ?: return@remember null
        "C:\\Users\\xuser\\AppData\\Local\\$appName"
    }

    // process.mainModule.filename mirror of NW.js's package.json `main` resolution. RMMV
    // ships package.json with "main": "www/index.html", RMMZ "index.html", C3 "index.html".
    // when set correctly, plugin code that composes paths via `path.dirname(filename)` lands
    // on the actual content folder. webRoot is "" / "www" / "zip:..."; the latter we treat
    // as flat (zip-content paths are zip-relative anyway).
    val mainModuleFilename = remember(container.webRoot) {
        val webRoot = container.webRoot
        if (webRoot.isBlank() || webRoot.startsWith("zip:")) "index.html"
        else "$webRoot/index.html"
    }

    // zip-hosted games open ZipFile once here, close once in onDispose.
    // path: <installPath>/<zipName>, e.g. /sdcard/Download/<Game>/package.nw.
    val zipFile: ZipFile? = remember(container.installPath, container.webRoot) {
        if (container.webRoot.startsWith("zip:")) {
            val zipName = container.webRoot.removePrefix("zip:")
            runCatching { ZipFile.builder().setFile(File(container.installPath, zipName)).get() }
                .onFailure { Timber.tag("WebViewScreen").e(it, "zip open failed") }
                .getOrNull()
        } else {
            null
        }
    }

    // pack:tyrano .tpatch overlay handles -- opened here so they share onDispose lifetime
    // with the main zipFile. selection + scan logic lives in TyranoTpatchOverlay.
    // registry: TitleQuirks.TYRANO_TPATCH.
    val tpatchOverlays: List<ZipFile> = remember(container.installPath, profile?.engine) {
        TyranoTpatchOverlay.scan(container.installPath, profile?.engine)
    }

    // pack:electron setup: opens the asar (or unpacked) archive, derives productName /
    // version / resolved entry / preload URL / __gnElectronCtx. null for non-electron OR
    // when archive open fails. closed in onDispose alongside zipFile.
    val electronSetup: ElectronAsarSetup? = remember(container.installPath, profile?.engine) {
        ElectronAsarSetup.open(container.installPath, profile)
    }

    // snackbar -- fires once when asar opened but productName missing.
    LaunchedEffect(electronSetup, electronSetup?.productName) {
        if (electronSetup != null && electronSetup.productName.isNullOrBlank()) {
            SnackbarManager.show(context.getString(R.string.html5_electron_missing_product_name))
        }
    }

    val electronCtx: Map<String, String>? = remember(electronSetup, container.id, profile?.engine) {
        if (profile == null) return@remember null
        electronSetup?.buildContext()
    }

    return Html5PackSetup(
        installDir = installDir,
        omoriContext = omoriContext,
        decryptContext = decryptContext,
        nwArgvJson = nwArgvJson,
        nwAppDataPath = nwAppDataPath,
        mainModuleFilename = mainModuleFilename,
        zipFile = zipFile,
        tpatchOverlays = tpatchOverlays,
        electronSetup = electronSetup,
        electronCtx = electronCtx,
    )
}
