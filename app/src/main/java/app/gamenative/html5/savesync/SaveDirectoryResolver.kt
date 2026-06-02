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

// WebView-side + Wine-side save path pair. Wine paths follow the PC layout under the container's
// prefix (`imagefs/home/xuser-<id>/.wine/...`, NOT `imagefs/data/<id>/`).
object SaveDirectoryResolver {

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

    fun resolve(
        context: Context,
        appId: String,
        container: Container,
        profile: EngineProfile,
        source: CloudSource,
    ): SavePathPair {
        val sync = profile.saves?.sync
            ?: throw SaveSyncFailure.PathMissing("profile.saves.sync is null for $appId")

        // SECURITY: reject .. segments in profile-supplied paths BEFORE File construction.
        rejectPathEscape(sync.pcPath, label = "profile.saves.sync.pcPath")
        sync.localSaveSubdir?.let { rejectPathEscape(it, label = "profile.saves.sync.localSaveSubdir") }
        sync.chromiumProfileSubdir?.let { rejectPathEscape(it, label = "profile.saves.sync.chromiumProfileSubdir") }

        // GOG/Epic are always CLOUD_ENABLED here: source.isSupported already required non-empty roots.
        val (syncMode, windowsPatterns) = when (source) {
            is CloudSource.SteamUfs -> {
                val patterns = source.steamApp.ufs.saveFilePatterns.filter { it.root.isWindows }
                val mode = if (patterns.isNotEmpty()) SyncMode.CLOUD_ENABLED else SyncMode.LOCAL_ONLY
                mode to patterns
            }
            is CloudSource.GogRemoteConfig,
            is CloudSource.EpicSavedGames,
            -> SyncMode.CLOUD_ENABLED to emptyList()
            // resolveCloudSourceForContainer short-circuits GreenworksCloud; throw to surface plumbing bugs.
            is CloudSource.GreenworksCloud -> throw SaveSyncFailure.PathMissing(
                "GreenworksCloud must be short-circuited before SaveDirectoryResolver (appId=${source.appId})",
            )
        }

        val webViewPaths = resolveWebViewPaths(context, container)

        val winePaths = when (source) {
            is CloudSource.GogRemoteConfig,
            is CloudSource.EpicSavedGames,
            -> resolveRemoteRootsWinePaths(
                appId = appId,
                profile = profile,
                source = source,
            )
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

    // one shared chromium profile (Default); games are isolated by origin, which chromium partitions
    // on. the WebView multi-profile API is missing on some firmware-locked providers.
    private fun resolveWebViewPaths(context: Context, container: Container): WebViewPaths {
        val profileDir = File(context.dataDir, "app_webview/Default")
        val originPrefix = WebViewOrigin.levelDbPrefix(container.id)
        return WebViewPaths(
            localStorageLevelDb = File(profileDir, "Local Storage/leveldb"),
            indexedDbLevelDb = File(profileDir, "IndexedDB/$originPrefix.indexeddb.leveldb"),
            indexedDbBlob = File(profileDir, "IndexedDB/$originPrefix.indexeddb.blob"),
        )
    }

    private fun resolveCloudEnabledWinePaths(
        context: Context,
        appId: String,
        container: Container,
        profile: EngineProfile,
        windowsPatterns: List<SaveFilePattern>,
    ): WinePaths {
        val sync = profile.saves!!.sync!!

        // split layout: UFS patterns point DIRECTLY at <root>/IndexedDB and <root>/Local Storage, so
        // don't re-append. a single pattern at the chromium-profile root falls through.
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

        // ufsPatternIndex picks among several windows-rooted patterns; default first.
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

    // GOG/Epic: pick the root holding chromium IDB data; before any exists, the first root.
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

    // among roots with IDB data, prefer the one matching pcOrigin. looks under chromiumProfileRoot --
    // for NW.js that's `<root>/User Data/Default/IndexedDB/`, not `<root>/IndexedDB/`.
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

    // userDataRoot = common parent of the matched patterns, so mtime walks see both.
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
        // IDB-only titles: assume LS is IDB's sibling.
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

    // case-sensitive to match chromium's on-disk layout.
    private fun SaveFilePattern.endsWithChromiumSegment(name: String): Boolean {
        val normalized = substitutedPath.replace('\\', '/').trimEnd('/')
        if (normalized.isBlank()) return false
        return normalized == name || normalized.endsWith("/$name")
    }

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
        val truncated = firstPath.substring(0, commonLen)
        val cut = truncated.lastIndexOf('/')
        return if (cut > 0) File(truncated.substring(0, cut)) else File(truncated)
    }

    // UFS root -> absolute path inside THIS container's prefix. must match where Steam cloud reads.
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
            // SteamUserData / non-windows roots don't occur for html5 titles
            else -> userHome
        }
    }

    // matches PathType.toAbsPath / toAbsPathForGOG composition.
    private const val WINE_DRIVE_C_REL = ".wine/drive_c"

    // lets Html5FsBridge map absolute Windows paths (`C:/users/xuser/AppData/...`) into the prefix.
    fun resolveWineDriveC(context: Context, container: Container): File {
        return File(containerRootDir(context, container), WINE_DRIVE_C_REL)
    }

    // Steam: SteamService.getAppDirPath, the same lookup SteamAutoCloud uses for GameInstall.
    private fun resolveGameInstallPath(appId: String, container: Container, containerRoot: File): File {
        if (GameSource.STEAM.matches(appId)) {
            val numericId = GameSource.STEAM.idOf(appId).toIntOrNull()
            if (numericId != null) {
                val steamInstallPath = SteamService.getAppDirPath(numericId)
                if (steamInstallPath.isNotBlank()) return File(steamInstallPath)
            }
        }
        // GOG stays on the RAW install dir: GOGManager resolves <?INSTALL?> cloud locations there
        // (not under .wine/drive_c), so a wine-wrapped sandbox writes saves GOG never uploads.
        return File(container.installPath.ifBlank { containerRoot.absolutePath })
    }

    // Html5FsBridge sandbox root = install dir, the NW.js cwd that RMMV/RMMZ relative save paths
    // resolve against. no containerRoot fallback: the bridge may be built before container activation.
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

    // fsBridge writes land where each store's cloud sync reads, so switching a container between
    // HTML5 and Wine sees the same files.
    // stock RMMV asset reads go through XHR -> AssetInterceptor, NOT fsBridge; only a plugin calling
    // fs.readFileSync on non-save assets would hit this root.
    fun resolveSandboxRoot(context: Context, appId: String, container: Container): File {
        return resolveGameInstallPath(appId, container, containerRootDir(context, container))
    }

    // pack:electron: sandbox = <prefix>/AppData/Roaming/<productName>/, where Electron on Wine and
    // Steam UFS look. throws IllegalArgumentException on an unsafe productName.
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

    // pack:nwjs: saves go to relative paths like `\Saves\Default\System.save`, which under the install dir
    // land OUTSIDE the prefix where Steam UFS never looks. so the sandbox is the UFS root plus the
    // pattern's first component (WinAppDataLocal/<App>/Saves -> <prefix>/AppData/Local/<App>).
    // non-Steam falls back to the install dir.
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
        // UFS data is attacker-controllable via Steam metadata edits.
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

    private fun validateProductName(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotBlank()) { "productName blank — cannot derive sandbox path" }
        val normalized = trimmed.replace('\\', '/')
        require(!normalized.contains('/')) {
            "productName contains path separator: $raw"
        }
        require(!normalized.startsWith('.')) {
            "productName may not start with '.' (reserved for hidden/traversal): $raw"
        }
        require(!normalized.contains(Char(0))) {
            "productName contains null byte: $raw"
        }
        return trimmed
    }

    private fun resolveLocalOnlyWinePaths(
        container: Container,
        profile: EngineProfile,
    ): WinePaths {
        val sync = profile.saves!!.sync!!

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

    // c3 has no sane default.
    private fun packDefaultLocalSaveSubdir(profile: EngineProfile): String? = when (profile.engine) {
        EnginePackId.RMMV -> "www/save"
        else -> null
    }

    // matches ContainerManager.createContainer.
    private fun containerRootDir(context: Context, container: Container): File {
        // rootDir is only set after activateContainer.
        container.rootDir?.let { return it }
        val imagefsRoot = ImageFs.find(context).rootDir
        return File(imagefsRoot, "home/${ImageFs.USER}-${container.id}")
    }

    private fun resolvePcOriginFilename(sync: app.gamenative.html5.profile.SaveSyncSpec): String? {
        if (sync.pcOrigin.isNotBlank()) return OriginCodec.filenameFromUrl(sync.pcOrigin)
        return null
    }

    // chromiumProfileSubdir (NW.js: "User Data/Default") sits between userDataRoot and the chromium
    // subdirs. userDataRoot stays the parent so mtime walks also see fs saves ABOVE the profile.
    private fun chromiumProfileRoot(
        userDataRoot: File,
        sync: app.gamenative.html5.profile.SaveSyncSpec,
    ): File {
        val sub = sync.chromiumProfileSubdir?.takeIf { it.isNotBlank() } ?: return userDataRoot
        return File(userDataRoot, sub)
    }

    // packs declare pcOrigin="file://", but Electron games use chrome-extension://<hash> with an
    // unknowable per-app hash -- so trust whatever origin the cloud-downloaded IndexedDB/ holds. the
    // profile value only applies when nothing is on disk yet.
    private fun resolveWineIdbOriginFilename(
        idbSubdir: File?,
        profileOriginFilename: String?,
        appId: String,
    ): String? {
        val discovered = discoverWineIdbOriginFilename(idbSubdir)
        if (discovered == null) return profileOriginFilename
        // file__0 is what the old file:// placeholder wrote -- never an NW.js PC origin, so a
        // chrome-extension profile origin (derived from the manifest) wins over it.
        if (discovered == "file__0" && profileOriginFilename?.startsWith("chrome-extension_") == true) {
            timber.log.Timber.tag("SaveDirectoryResolver").i(
                "IDB origin: profile %s beats placeholder file__0 appId=%s dir=%s",
                profileOriginFilename, appId, idbSubdir?.absolutePath,
            )
            return profileOriginFilename
        }
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

    // prefers an origin with populated blobs over stale empty shells.
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

    // UFS paths may arrive windows-style, so split on both separators.
    private fun rejectPathEscape(p: String, label: String) {
        if (p.isBlank()) return
        val normalized = p.replace('\\', '/')
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) {
            throw SaveSyncFailure.Other("path escape rejected in $label: $p")
        }
    }
}
