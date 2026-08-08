package app.gamenative.html5.savesync

import android.content.Context
import app.gamenative.data.SaveFilePattern
import app.gamenative.enums.PathType
import app.gamenative.html5.host.WebViewOrigin
import app.gamenative.html5.profile.EngineProfile
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import java.io.File
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.data.GameSource

// resolves WebView-side + Wine-side save path pair for Html5SaveSyncService.
// wine save path shape anchored by PC layouts (`imagefs/home/xuser-<id>/.wine/...`, NOT
// `imagefs/data/<id>/`). pure path arithmetic -- no IO beyond File-constructor side effects.
object SaveDirectoryResolver {

    // origin prefix derived from WebViewOrigin.levelDbPrefix(container.id) =
    // "https_game-<id>_0" -- chromium encodes scheme_host_port into leveldb filenames.

    data class WebViewPaths(
        val localStorageLevelDb: File,
        val indexedDbLevelDb: File?,
        val indexedDbBlob: File?,
    )

    data class WinePaths(
        val userDataRoot: File,
        val localStorageLevelDb: File,
        val indexedDbLevelDb: File?,
        val indexedDbBlob: File?,
    )

    data class SavePathPair(
        val webView: WebViewPaths,
        val wine: WinePaths,
        val syncMode: SyncMode,
    )

    // main entry. context resolves imagefs + webview dataDir. source carries the per-store
    // wine-root strategy: SteamUfs drives the legacy UFS-pattern walk; GogRemoteConfig
    // consumes pre-resolved abs paths from GOGManager.getSaveDirectoryPath.
    fun resolve(
        context: Context,
        appId: String,
        container: Container,
        profile: EngineProfile,
        source: CloudSource,
    ): SavePathPair {
        val sync = profile.saves?.sync
            ?: throw SaveSyncFailure.PathMissing("profile.saves.sync is null for $appId")

        // SECURITY -- reject .. segments in profile-supplied paths BEFORE File construction.
        rejectPathEscape(sync.pcPath, label = "profile.saves.sync.pcPath")
        sync.localSaveSubdir?.let { rejectPathEscape(it, label = "profile.saves.sync.localSaveSubdir") }
        sync.chromiumProfileSubdir?.let { rejectPathEscape(it, label = "profile.saves.sync.chromiumProfileSubdir") }

        // classify sync mode per source. SteamUfs uses windows-rooted UFS patterns; GogRemoteConfig
        // and EpicSavedGames are always CLOUD_ENABLED (presence already gated upstream by
        // source.isSupported via wineSaveRoots returning non-empty).
        val (syncMode, windowsPatterns) = when (source) {
            is CloudSource.SteamUfs -> {
                val patterns = source.steamApp.ufs.saveFilePatterns.filter { it.root.isWindows }
                val mode = if (patterns.isNotEmpty()) SyncMode.CLOUD_ENABLED else SyncMode.LOCAL_ONLY
                mode to patterns
            }
            is CloudSource.GogRemoteConfig,
            is CloudSource.EpicSavedGames,
            -> SyncMode.CLOUD_ENABLED to emptyList()
            // GreenworksCloud is short-circuited in
            // resolveCloudSourceForContainer BEFORE SaveDirectoryResolver runs.
            // unreachable here; throw to surface plumbing regressions immediately.
            is CloudSource.GreenworksCloud -> throw SaveSyncFailure.PathMissing(
                "GreenworksCloud must be short-circuited before SaveDirectoryResolver (appId=${source.appId})",
            )
        }

        // container.id is the authoritative WebView profile name.
        val webViewPaths = resolveWebViewPaths(context, container)

        val winePaths = when (source) {
            is CloudSource.GogRemoteConfig,
            is CloudSource.EpicSavedGames,
            -> resolveRemoteRootsWinePaths(
                appId = appId,
                profile = profile,
                source = source,
            )
            // unreachable -- short-circuits GreenworksCloud upstream.
            is CloudSource.GreenworksCloud -> throw SaveSyncFailure.PathMissing(
                "GreenworksCloud must be short-circuited before SaveDirectoryResolver (appId=${source.appId})",
            )
            is CloudSource.SteamUfs -> when (syncMode) {
                SyncMode.CLOUD_ENABLED -> resolveCloudEnabledWinePaths(
                    context = context,
                    appId = appId,
                    container = container,
                    profile = profile,
                    windowsPatterns = windowsPatterns,
                )
                SyncMode.LOCAL_ONLY -> resolveLocalOnlyWinePaths(
                    container = container,
                    profile = profile,
                )
            }
        }

        val result = SavePathPair(
            webView = webViewPaths,
            wine = winePaths,
            syncMode = syncMode,
        )
        // diag: dump resolved shape so on-device logcat can tell us which branch fired
        // and what paths got computed.
        val firstPattern = windowsPatterns.firstOrNull()
        timber.log.Timber.tag("SaveDirectoryResolver").i(
            "resolved appId=%s container.id=%s mode=%s wine.userDataRoot=%s wine.ls=%s wine.idb=%s webview.ls=%s installPath=%s rootDir=%s ufsPatterns=%d firstRoot=%s firstPath=%s",
            appId,
            container.id,
            syncMode,
            winePaths.userDataRoot.absolutePath,
            winePaths.localStorageLevelDb.absolutePath,
            winePaths.indexedDbLevelDb?.absolutePath,
            webViewPaths.localStorageLevelDb.absolutePath,
            container.installPath,
            container.rootDir?.absolutePath,
            windowsPatterns.size,
            firstPattern?.root,
            firstPattern?.substitutedPath,
        )
        return result
    }

    // ---------------- webview side ----------------

    // single shared chromium profile dir (Default). per-container isolation is achieved at
    // the origin layer (https://game-<id>) -- chromium partitions IDB / LS / cookies by origin
    // automatically. multi-profile API was removed in v2 (firmware-locked WebView providers
    // on some devices ship without it, and the per-container Profile path didn't survive
    // chromium's sequential allocation scheme anyway).
    private fun resolveWebViewPaths(context: Context, container: Container): WebViewPaths {
        val profileDir = File(context.dataDir, "app_webview/Default")
        val originPrefix = WebViewOrigin.levelDbPrefix(container.id)
        return WebViewPaths(
            localStorageLevelDb = File(profileDir, "Local Storage/leveldb"),
            indexedDbLevelDb = File(profileDir, "IndexedDB/$originPrefix.indexeddb.leveldb"),
            indexedDbBlob = File(profileDir, "IndexedDB/$originPrefix.indexeddb.blob"),
        )
    }

    // ---------------- wine side (CLOUD_ENABLED) ----------------

    private fun resolveCloudEnabledWinePaths(
        context: Context,
        appId: String,
        container: Container,
        profile: EngineProfile,
        windowsPatterns: List<SaveFilePattern>,
    ): WinePaths {
        val sync = profile.saves!!.sync!!

        // SPLIT-LAYOUT shape -- UFS patterns point DIRECTLY at <root>/IndexedDB and
        // <root>/Local Storage; resolver must not re-append.
        // unified-layout (single pattern at chromium-profile root) falls through.
        val idbPattern = windowsPatterns.firstOrNull { it.endsWithChromiumSegment("IndexedDB") }
        val lsPattern = windowsPatterns.firstOrNull { it.endsWithChromiumSegment("Local Storage") }
        if (idbPattern != null || lsPattern != null) {
            return resolveSplitLayoutWinePaths(
                context = context,
                appId = appId,
                container = container,
                sync = sync,
                idbPattern = idbPattern,
                lsPattern = lsPattern,
            )
        }

        // unified-layout: ufsPatternIndex pins which pattern when multiple windows-rooted
        // entries exist; absent → first.
        val idx = sync.ufsPatternIndex?.takeIf { it in windowsPatterns.indices } ?: 0
        val pattern = windowsPatterns[idx]

        val basePath = winePrefixPathForRoot(context, appId, container, pattern.root)
        val userDataRoot = if (pattern.substitutedPath.isNotBlank()) {
            File(basePath, pattern.substitutedPath)
        } else {
            basePath
        }
        val chromiumRoot = chromiumProfileRoot(userDataRoot, sync)

        val localStorageLevelDb = File(chromiumRoot, "Local Storage/leveldb")
        val profileOriginFilename = resolvePcOriginFilename(sync)
        val idbParentDir = File(chromiumRoot, "IndexedDB")
        val originFilename = resolveWineIdbOriginFilename(
            idbSubdir = idbParentDir,
            profileOriginFilename = profileOriginFilename,
            appId = appId,
        )
        val idbLevelDb = originFilename?.let { File(idbParentDir, "$it.indexeddb.leveldb") }
        val idbBlob = originFilename?.let { File(idbParentDir, "$it.indexeddb.blob") }

        return WinePaths(
            userDataRoot = userDataRoot,
            localStorageLevelDb = localStorageLevelDb,
            indexedDbLevelDb = idbLevelDb,
            indexedDbBlob = idbBlob,
        )
    }

    // GOG remote-config path resolution. source.wineSaveRoots() returns ABSOLUTE wine-prefix
    // paths from GOG's per-game save-location config; pick the one containing chromium IDB
    // data so LS/IDB rewriters land on the correct subtree. cold-start (no chromium data yet)
    // falls back to the first remote-config root -- webview will populate on first launch.
    // shared "remote roots" resolution. consumed by GogRemoteConfig + EpicSavedGames -- both
    // surface candidate wine save dirs via CloudSource.wineSaveRoots(). store-agnostic.
    private fun resolveRemoteRootsWinePaths(
        appId: String,
        profile: EngineProfile,
        source: CloudSource,
    ): WinePaths {
        val sync = profile.saves?.sync
            ?: throw SaveSyncFailure.PathMissing("profile.saves.sync is null for $appId")
        val roots = kotlinx.coroutines.runBlocking { source.wineSaveRoots() }
        if (roots.isEmpty()) {
            throw SaveSyncFailure.PathMissing("remote-config returned no save roots for $appId (source=${source::class.simpleName})")
        }
        val profileOriginFilename = resolvePcOriginFilename(sync)
        val picked = pickRootByIdb(roots, sync, profileOriginFilename) ?: roots.first()
        val chromiumRoot = chromiumProfileRoot(picked, sync)
        val idbParentDir = File(chromiumRoot, "IndexedDB")
        val originFilename = resolveWineIdbOriginFilename(
            idbSubdir = idbParentDir,
            profileOriginFilename = profileOriginFilename,
            appId = appId,
        )
        return WinePaths(
            userDataRoot = picked,
            localStorageLevelDb = File(chromiumRoot, "Local Storage/leveldb"),
            indexedDbLevelDb = originFilename?.let { File(idbParentDir, "$it.indexeddb.leveldb") },
            indexedDbBlob = originFilename?.let { File(idbParentDir, "$it.indexeddb.blob") },
        )
    }

    // multi-root selector: scan each root for chromium IDB subtree; prefer the one whose
    // origin filename matches profile.pcOrigin. zero matches → null (caller falls back to first).
    // sync passed so the disambiguation step looks under chromiumProfileRoot(root, sync) -- for
    // NW.js packs that's `<root>/User Data/Default/IndexedDB/`, not `<root>/IndexedDB/`.
    private fun pickRootByIdb(
        roots: List<File>,
        sync: app.gamenative.html5.profile.SaveSyncSpec,
        profileOriginFilename: String?,
    ): File? {
        val withIdb = roots.filter { root ->
            root.isDirectory && root.walkTopDown().any { it.isDirectory && it.name.endsWith(".indexeddb.leveldb") }
        }
        if (withIdb.isEmpty()) return null
        if (withIdb.size == 1 || profileOriginFilename == null) return withIdb.first()
        val match = withIdb.firstOrNull { root ->
            val idbDir = File(chromiumProfileRoot(root, sync), "IndexedDB")
            idbDir.listFiles()?.any { it.name == "$profileOriginFilename.indexeddb.leveldb" } == true
        }
        return match ?: withIdb.first()
    }

    // split-layout path resolution: UFS patterns directly identify chromium subtrees.
    // userDataRoot = longest common parent of matched patterns (used by mtime walks).
    private fun resolveSplitLayoutWinePaths(
        context: Context,
        appId: String,
        container: Container,
        sync: app.gamenative.html5.profile.SaveSyncSpec,
        idbPattern: SaveFilePattern?,
        lsPattern: SaveFilePattern?,
    ): WinePaths {
        val profileOriginFilename = resolvePcOriginFilename(sync)

        val idbDir = idbPattern?.let { p ->
            File(winePrefixPathForRoot(context, appId, container, p.root), p.substitutedPath)
        }
        val lsDir = lsPattern?.let { p ->
            File(winePrefixPathForRoot(context, appId, container, p.root), p.substitutedPath)
        }

        val originFilename = resolveWineIdbOriginFilename(
            idbSubdir = idbDir,
            profileOriginFilename = profileOriginFilename,
            appId = appId,
        )
        val idbLevelDb = if (originFilename != null && idbDir != null) {
            File(idbDir, "$originFilename.indexeddb.leveldb")
        } else {
            null
        }
        val idbBlob = if (originFilename != null && idbDir != null) {
            File(idbDir, "$originFilename.indexeddb.blob")
        } else {
            null
        }
        // LS pattern present → use it directly. absent → synthesize sibling under idb's parent
        // (defensive fallback for IDB-only titles).
        val localStorageLevelDb = lsDir?.let { File(it, "leveldb") }
            ?: idbDir?.parentFile?.let { File(it, "Local Storage/leveldb") }
            ?: throw SaveSyncFailure.PathMissing(
                "split-layout: no IDB or LS UFS pattern resolved for $appId — cannot derive wine paths",
            )

        val candidates = listOfNotNull(idbDir, lsDir)
        val userDataRoot = commonParent(candidates)

        return WinePaths(
            userDataRoot = userDataRoot,
            localStorageLevelDb = localStorageLevelDb,
            indexedDbLevelDb = idbLevelDb,
            indexedDbBlob = idbBlob,
        )
    }

    // matches UFS pattern whose substituted path's last segment equals the chromium subdir name.
    // accepts both bare ("IndexedDB") and nested ("save/IndexedDB") forms; case-sensitive to match
    // chromium's on-disk layout.
    private fun SaveFilePattern.endsWithChromiumSegment(name: String): Boolean {
        val normalized = substitutedPath.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank()) return false
        return normalized == name || normalized.endsWith("/$name")
    }

    // longest common parent path across the given files. used by split-layout resolver to
    // produce a userDataRoot that covers ALL wine save paths (so mtime walk sees everything).
    private fun commonParent(files: List<File>): File {
        require(files.isNotEmpty()) { "commonParent requires non-empty list" }
        if (files.size == 1) return files[0].parentFile ?: files[0]
        val firstPath = files[0].absolutePath
        var commonLen = firstPath.length
        for (f in files.drop(1)) {
            val other = f.absolutePath
            var i = 0
            while (i < commonLen && i < other.length && firstPath[i] == other[i]) i++
            commonLen = i
        }
        // back up to the last directory boundary so we don't return a partial dirname
        val truncated = firstPath.substring(0, commonLen)
        val cut = truncated.lastIndexOf('/')
        return if (cut > 0) File(truncated.substring(0, cut)) else File(truncated)
    }

    // maps a UFS pattern root to the absolute filesystem path inside THIS container.
    // windows roots all live under <container.rootDir>/.wine/drive_c/users/<USER>/...,
    // GameInstall for STEAM_ containers resolves via SteamService.getAppDirPath so the
    // path matches SteamAutoCloud.prefixToPath (PathType.GameInstall.toAbsPath) -- sync
    // must write where Cloud reads. non-STEAM containers fall back to container.installPath.
    
    // internal so resolveSandboxRoot's pack:electron branch reuses the same wine-prefix path
    // math -- one source of truth for WinAppDataRoaming / WinAppDataLocal / etc.
    internal fun winePrefixPathForRoot(context: Context, appId: String, container: Container, root: PathType): File {
        val containerRoot = containerRootDir(context, container)
        val driveC = File(containerRoot, WINE_DRIVE_C_REL)
        val userHome = File(driveC, "users/${ImageFs.USER}")
        return when (root) {
            PathType.WinAppDataLocal -> File(userHome, "AppData/Local")
            PathType.WinAppDataLocalLow -> File(userHome, "AppData/LocalLow")
            PathType.WinAppDataRoaming -> File(userHome, "AppData/Roaming")
            PathType.WinMyDocuments -> File(userHome, "Documents")
            PathType.WinSavedGames -> File(userHome, "Saved Games")
            PathType.WinProgramData -> File(driveC, "ProgramData")
            PathType.Root -> userHome
            PathType.GameInstall -> resolveGameInstallPath(appId, container, containerRoot)
            // SteamUserData + non-windows types rare for html5 titles; fall back to userHome
            // so tests don't NPE on exotic patterns.
            else -> userHome
        }
    }

    // wine emulation drive root, relative to containerRoot. one literal so refactors touch a
    // single site. matches PathType.toAbsPath / toAbsPathForGOG composition.
    private const val WINE_DRIVE_C_REL = ".wine/drive_c"

    // wine drive_c root for THIS container, plumbed into Html5FsBridge so games composing absolute
    // Windows paths (`C:/users/xuser/AppData/...`, our win32 posture) map into the wine prefix.
    fun resolveWineDriveC(context: Context, container: Container): File {
        return File(containerRootDir(context, container), WINE_DRIVE_C_REL)
    }

    // STEAM_<id> → SteamService.getAppDirPath so outbound sync lands where SteamAutoCloud
    // reads (PathType.GameInstall.toAbsPath does the same lookup, already inside wine prefix).
    // GOG_<id> → wine-prefix-wrap container.installPath so writes land where GOG cloud sync
    // scans (matches PathType.toAbsPathForGOG composition: <containerRoot>/.wine/drive_c<abs>).
    // other prefixes fall back to container.installPath with containerRoot as last resort.
    private fun resolveGameInstallPath(appId: String, container: Container, containerRoot: File): File {
        if (GameSource.STEAM.matches(appId)) {
            val numericId = GameSource.STEAM.idOf(appId).toIntOrNull()
            if (numericId != null) {
                val steamInstallPath = SteamService.getAppDirPath(numericId)
                if (steamInstallPath.isNotBlank()) return File(steamInstallPath)
            }
        }
        if (GameSource.GOG.matches(appId) && container.installPath.isNotBlank()) {
            return wineWrappedInstallPath(containerRoot, container.installPath)
        }
        return File(container.installPath.ifBlank { containerRoot.absolutePath })
    }

    // <containerRoot>/.wine/drive_c<absInstallPath> (matches PathType.toAbsPathForGOG).
    private fun wineWrappedInstallPath(containerRoot: File, absInstallPath: String): File {
        return File(containerRoot, "$WINE_DRIVE_C_REL${absInstallPath}")
    }

    // public helper -- Html5FsBridge sandbox root. mirrors the Steam-vs-sideloaded branch
    // in resolveGameInstallPath but drops the containerRoot fallback (bridge callers construct
    // the bridge BEFORE container activation; containerRoot may not be set yet). Steam containers
    // land in SteamService.getAppDirPath so fsBridge writes end up where SteamAutoCloud UFS
    // reads them; sideloaded and other prefixes fall through to installPathFallback.

    // sandbox root = install dir. NW.js cwd model -- RMMV/RMMZ games use relative paths
    // like "save/file1.rmmzsave" or "www/save/file1.rpgsave" that resolve against cwd.
    fun resolveSandboxRoot(appId: String, installPathFallback: String): File {
        if (GameSource.STEAM.matches(appId)) {
            val numericId = GameSource.STEAM.idOf(appId).toIntOrNull()
            if (numericId != null) {
                val steamInstallPath = SteamService.getAppDirPath(numericId)
                if (steamInstallPath.isNotBlank()) return File(steamInstallPath)
            }
        }
        return File(installPathFallback)
    }

    // store-aware overload -- delegates to resolveGameInstallPath so the Steam/GOG/sideloaded
    // routing lives in ONE helper. fsBridge writes thus land where each store's cloud sync
    // reads (Steam: SteamService.getAppDirPath; GOG: wine-prefix wrap; sideloaded: raw install).
    // backend toggle (HTML5⇄Wine on the same container) preserved because Wine reads/writes
    // the same wine-prefix path natively for cloud-enabled stores.
    //
    // SAFETY: stock RMMV asset reads (data/*.json, images, audio) go through XHR →
    // AssetInterceptor (HTTP layer), NOT fsBridge. confirmed by logcat: ISAT session showed
    // ONLY www/save/*.rpgsave on the bridge. plugins that call fs.readFileSync for non-save
    // assets would resolve into the (mostly-empty) wine-prefix tree -- flag if observed.
    fun resolveSandboxRoot(context: Context, appId: String, container: Container): File {
        return resolveGameInstallPath(appId, container, containerRootDir(context, container))
    }

    // electron overload. pack:electron containers need a sandbox root that
    // matches the wine-prefix emulation path (<containerRoot>/.wine/drive_c/users/xuser/
    // AppData/Roaming/<productName>/) so Steam Cloud UFS + Wine runtime see the same files.
    // non-electron profiles delegate to the existing (appId, container) overload -- no behavior
    // change for pack:rmmv / pack:c3 / sideloaded per SPEC "no HTML5 regression" constraint.
    
    // productName validation: reject anything with a path separator, `..`, or leading `.`
    // BEFORE File construction. invalid → IllegalArgumentException. caller (WebViewScreen)
    // surfaces this via SnackbarManager.show per fallback chain.
    fun resolveSandboxRoot(
        context: Context,
        appId: String,
        container: Container,
        profile: EngineProfile,
        productName: String,
    ): File {
        if (profile.engine != EnginePackId.ELECTRON) {
            return resolveSandboxRoot(appId, container.installPath)
        }
        val safeName = validateProductName(productName)
        val base = winePrefixPathForRoot(context, appId, container, PathType.WinAppDataRoaming)
        return File(base, safeName)
    }

    // pack:nwjs overload. NW.js Steam titles write saves via fs.writeFile to engine-internal
    // relative paths like `\Saves\Default\System.save`. when the bridge sandbox is the Steam
    // install dir (the 2-arg fallback), those writes land OUTSIDE the wine prefix → Steam
    // Cloud UFS doesn't see them. derive sandbox from the SteamApp's UFS pattern instead --
    // e.g. UFS pattern WinAppDataLocal/<App>/Saves gives sandbox =
    // <wine>/drive_c/users/xuser/AppData/Local/<App>. engine's `\Saves\...` → bridgeRel
    // `Saves/...` → resolved under sandbox lands under the title's app-data dir exactly where
    // UFS reads. parallels pack:electron's wine-prefix wrap, but uses the actual UFS root
    // (Local vs Roaming) and first path component (= app's app-data subdir name) instead of
    // a productName argument. non-STEAM (CUSTOM_GAME_/GOG_) falls back to install dir.
    fun resolveSandboxRootForNwjs(
        context: Context,
        appId: String,
        container: Container,
    ): File {
        val fallback = { resolveSandboxRoot(appId, container.installPath) }
        if (!GameSource.STEAM.matches(appId)) return fallback()
        val numericId = GameSource.STEAM.idOf(appId).toIntOrNull() ?: return fallback()
        val app = SteamService.getAppInfoOf(numericId) ?: return fallback()
        val pattern = app.ufs.saveFilePatterns.firstOrNull { it.root.isWindows } ?: return fallback()
        val firstComponent = pattern.substitutedPath
            .replace('\\', '/')
            .trim('/')
            .substringBefore('/')
            .takeIf { it.isNotBlank() } ?: return fallback()
        // hardening: reject `..` segments inside the substitutedPath component, leading dot,
        // null byte. UFS data is technically attacker-controllable via Steam metadata edits.
        if (firstComponent.contains("..") ||
            firstComponent.startsWith('.') ||
            firstComponent.contains(Char(0))
        ) {
            timber.log.Timber.tag("SaveDirectoryResolver").w(
                "pack:nwjs sandbox derivation rejected suspect UFS path component '%s' for appId=%s — falling back to install dir",
                firstComponent, appId,
            )
            return fallback()
        }
        val base = winePrefixPathForRoot(context, appId, container, pattern.root)
        return File(base, firstComponent)
    }

    // reject productName containing path separators, traversal, null bytes, or leading dot.
    // trims leading/trailing whitespace before validation.
    private fun validateProductName(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "productName blank — cannot derive sandbox path" }
        // unified separator: treat backslash as forward-slash for reject decision.
        val normalized = trimmed.replace('\\', '/')
        require(!normalized.contains('/')) {
            "productName contains path separator: $raw"
        }
        require(!normalized.startsWith('.')) {
            "productName may not start with '.' (reserved for hidden/traversal): $raw"
        }
        // Char(0) avoids a raw NUL byte in source -- raw NUL trips git binary-file heuristic
        // and upstream lexer edge cases.
        require(!normalized.contains(Char(0))) {
            "productName contains null byte: $raw"
        }
        return trimmed
    }

    // ---------------- wine side (LOCAL_ONLY) ----------------

    private fun resolveLocalOnlyWinePaths(
        container: Container,
        profile: EngineProfile,
    ): WinePaths {
        val sync = profile.saves!!.sync!!

        // precedence: profile override → engine-pack default → error.
        val subdir = sync.localSaveSubdir?.takeIf { it.isNotBlank() }
            ?: packDefaultLocalSaveSubdir(profile)
            ?: throw SaveSyncFailure.PathMissing(
                "no localSaveSubdir for engine '${profile.engine}' on LOCAL_ONLY title; profile must pin saves.sync.localSaveSubdir",
            )

        val installDir = container.installPath.ifBlank {
            throw SaveSyncFailure.PathMissing("container.installPath empty for LOCAL_ONLY resolution")
        }
        val userDataRoot = File(installDir, subdir)
        val chromiumRoot = chromiumProfileRoot(userDataRoot, sync)
        val localStorageLevelDb = File(chromiumRoot, "Local Storage/leveldb")
        val profileOriginFilename = resolvePcOriginFilename(sync)
        val idbParentDir = File(chromiumRoot, "IndexedDB")
        val originFilename = resolveWineIdbOriginFilename(
            idbSubdir = idbParentDir,
            profileOriginFilename = profileOriginFilename,
            appId = container.id,
        )
        val idbLevelDb = originFilename?.let { File(idbParentDir, "$it.indexeddb.leveldb") }
        val idbBlob = originFilename?.let { File(idbParentDir, "$it.indexeddb.blob") }

        return WinePaths(
            userDataRoot = userDataRoot,
            localStorageLevelDb = localStorageLevelDb,
            indexedDbLevelDb = idbLevelDb,
            indexedDbBlob = idbBlob,
        )
    }

    // engine-pack default for LOCAL_ONLY save subdir. rmmv → "www/save"; c3 has no sane default.
    private fun packDefaultLocalSaveSubdir(profile: EngineProfile): String? = when (profile.engine) {
        EnginePackId.RMMV -> "www/save"
        else -> null
    }

    // ---------------- helpers ----------------

    // container home dir resolution → <imagefsRoot>/home/xuser-<id>/.
    // matches ContainerManager.createContainer line 169, empirical PASS path.
    private fun containerRootDir(context: Context, container: Container): File {
        // prefer Container.rootDir when it's been set (happens after activateContainer).
        container.rootDir?.let { return it }
        val imagefsRoot = ImageFs.find(context).rootDir
        return File(imagefsRoot, "home/${ImageFs.USER}-${container.id}")
    }

    // derive IDB filename dir from pcOrigin (URL → filename via OriginCodec).
    // null when pcOrigin is blank → no IDB paths resolved.
    private fun resolvePcOriginFilename(sync: app.gamenative.html5.profile.SaveSyncSpec): String? {
        if (sync.pcOrigin.isNotBlank()) return OriginCodec.filenameFromUrl(sync.pcOrigin)
        return null
    }

    // applies sync.chromiumProfileSubdir (NW.js: "User Data/Default") between userDataRoot
    // and the chromium subdirs (Local Storage, IndexedDB). null/blank = pass-through.
    // userDataRoot is preserved as the parent so mtime walks still see fs writes that land
    // ABOVE the chromium profile (e.g. CrossCode's `cc.save` at <root>/cc.save).
    private fun chromiumProfileRoot(
        userDataRoot: File,
        sync: app.gamenative.html5.profile.SaveSyncSpec,
    ): File {
        val sub = sync.chromiumProfileSubdir?.takeIf { it.isNotBlank() } ?: return userDataRoot
        return File(userDataRoot, sub)
    }

    // wine-side IDB origin auto-discovery.
    // packs hardcode pcOrigin="file://" but modern Electron games use chrome-extension://<hash>
    // with a per-app hash unknowable a priori. when Steam Cloud downloads the real leveldb to
    // the wine prefix, we can discover the origin filename empirically by listing IndexedDB/.
    // prefers candidates whose sibling .blob dir is populated (real data, not stale shells).
    // falls back to profile-declared filename when discovery finds nothing (fresh install,
    // outbound-first, or cloud-empty) so existing behavior is preserved.
    private fun resolveWineIdbOriginFilename(
        idbSubdir: File?,
        profileOriginFilename: String?,
        appId: String,
    ): String? {
        val discovered = discoverWineIdbOriginFilename(idbSubdir)
        if (discovered == null) return profileOriginFilename
        if (profileOriginFilename != null && discovered != profileOriginFilename) {
            timber.log.Timber.tag("SaveDirectoryResolver").w(
                "IDB origin discovery overrode profile: appId=%s profile=%s discovered=%s dir=%s",
                appId, profileOriginFilename, discovered, idbSubdir?.absolutePath,
            )
        } else {
            timber.log.Timber.tag("SaveDirectoryResolver").i(
                "IDB origin discovery matched profile: appId=%s origin=%s dir=%s",
                appId, discovered, idbSubdir?.absolutePath,
            )
        }
        return discovered
    }

    // returns "chrome-extension_<hash>_0" / "file__0" / ... derived from whatever chromium
    // left on disk. null when dir missing or empty.
    private fun discoverWineIdbOriginFilename(idbSubdir: File?): String? {
        if (idbSubdir == null || !idbSubdir.isDirectory) return null
        val candidates = idbSubdir.listFiles { f ->
            f.isDirectory && f.name.endsWith(".indexeddb.leveldb")
        }?.asList().orEmpty()
        if (candidates.isEmpty()) return null
        val withPopulatedBlobs = candidates.firstOrNull { c ->
            val prefix = c.name.removeSuffix(".indexeddb.leveldb")
            val blob = File(idbSubdir, "$prefix.indexeddb.blob")
            blob.isDirectory && blob.walkTopDown().any { it.isFile }
        }
        val picked = withPopulatedBlobs ?: candidates.first()
        return picked.name.removeSuffix(".indexeddb.leveldb")
    }

    // 01: reject any ".." segment before File construction. splits on both / and \\
    // to cover windows-style paths that come through as-is from UFS config.
    private fun rejectPathEscape(p: String, label: String) {
        if (p.isBlank()) return
        val normalized = p.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) {
            throw SaveSyncFailure.Other("path escape rejected in $label: $p")
        }
    }
}
