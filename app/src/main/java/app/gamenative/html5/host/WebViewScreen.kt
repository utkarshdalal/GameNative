package app.gamenative.html5.host

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.webkit.WebViewAssetLoader
import app.gamenative.BuildConfig
import app.gamenative.FeatureGate
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig
import app.gamenative.events.AndroidEvent
import app.gamenative.html5.asar.AsarArchive
import app.gamenative.html5.asar.AsarAssetInterceptor
import app.gamenative.html5.asar.ElectronArchive
import app.gamenative.html5.asar.UnpackedElectronArchive
import app.gamenative.html5.input.Html5DefaultControlsProfileFactory
import app.gamenative.html5.input.Html5InputBridge
import app.gamenative.html5.input.Html5InputController
import app.gamenative.html5.input.Html5InputSynthesizer
import app.gamenative.html5.input.Html5OverlaySeed
import app.gamenative.html5.input.resolveInputMode
import app.gamenative.html5.savesync.SaveDirectoryResolver
import app.gamenative.html5.shim.Html5AchievementSeed
import app.gamenative.html5.shim.Html5FsBridge
import app.gamenative.html5.shim.Html5RuntimeBridge
import app.gamenative.html5.shim.ShimBundles
import app.gamenative.html5.shim.SteamworksJsBridge
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.component.QuickMenuAction
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.widget.FrameTimeRingBuffer
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControllerManager
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import com.winlator.widget.InputControlsView
import java.io.File
import java.io.FileInputStream
import java.util.EnumSet
import org.apache.commons.compress.archivers.zip.ZipFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.data.GameSource

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    appId: String,
    navigateBack: () -> Unit,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    viewModel: WebViewScreenViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // if chromium gate failed at boot, snackbar + pop back before touching WebView.
    if (PluviaApp.html5RuntimeDisabled) {
        LaunchedEffect(Unit) {
            SnackbarManager.show(context.getString(R.string.webview_runtime_unavailable))
            navigateBack()
        }
        return
    }

    val loaded = remember(appId) { viewModel.loadByAppId(appId) }
    if (loaded == null) {
        LaunchedEffect(appId) {
            SnackbarManager.show(context.getString(R.string.webview_container_not_found))
            navigateBack()
        }
        return
    }

    //container is mutable via remember(loaded) so dialog onDone callbacks
    // can update local state alongside the disk persist -- without this, the LaunchedEffect
    // observers below (LaunchedEffect(container.overlayOpacity) etc.) only fire on first
    // composition + recomposition, not when the user saves new values. follow-up reopens
    // of the dialog stayed stale on the original immutable copy.
    var container by remember(loaded) { mutableStateOf(loaded.container) }
    val profile = loaded.profile

    // diagnostic: surface what we loaded for state-relevant fields every (re-)load.
    // suspendPolicy is sourced from the wine Container (single per-container preference) so it's
    // logged separately below.
    LaunchedEffect(loaded) {
        Timber.tag("WebViewScreen").d(
            "container loaded: id=%s overlayVisible=%b overlayOpacity=%.2f controlsProfileId=%d inputMap=%s",
            container.id, container.overlayVisible, container.overlayOpacity,
            container.controlsProfileId, container.inputMap,
        )
    }

    // resolve the user-effective input mode so we know which shims to inject.
    // gamepad.js is ALWAYS injected for html5 containers (controller support never hurts
    // -- touch games ignore gamepadconnected when no controller is present).
    // pointer-with-tap.js is conditional on mode == "pointer-with-tap-detection".
    val resolvedMode = remember(container, profile) {
        resolveInputMode(container, profile)
    }

    val shimUrls: List<String> = remember(profile, resolvedMode) {
        resolveShimUrls(
            profile,
            resolvedMode,
            includeDiagnostic = FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM,
        )
    }

    // SteamworksJsBridge construction lifted out of the WebView remember{}
    // block so the SAME instance is used for both addJavascriptInterface (binder-thread JS
    // surface) AND seedFromSchema (cache populate from LaunchedEffect). using two
    // SteamworksJsBridge() calls would create two separate caches and JS would see empty
    // getAchievement/getStat values. declared HERE (before LaunchedEffect) so the seed block
    // below can capture them -- Compose composes top-down, so refs declared after a
    // LaunchedEffect aren't visible inside it.
    val steamworksBridgeScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // WebViewContainer has NO gameSource field. Steam-gate via
    // appId String prefix (precedent: WebViewScreenViewModel.loadByAppId). null for sideloaded
    // / non-Steam -- disables the achievement seed + watcher path entirely.
    val steamAppIdInt = remember(appId) {
        if (GameSource.STEAM.matches(appId)) GameSource.STEAM.idOf(appId).toIntOrNull() else null
    }

    // gseDir resolves to the GSE save dir for Steam containers; non-Steam falls back to a
    // per-container scratch dir under filesDir so bridge writes don't NPE. the bridge writes
    // are no-op-equivalent for non-Steam (no AchievementWatcher attached, nothing reads the
    // files) -- fallback path keeps the bridge well-typed.
    val gseDir = remember(steamAppIdInt, container.id) {
        steamAppIdInt?.let { SteamService.getGseSaveDirs(context, it).firstOrNull() }
            ?: File(context.filesDir, "html5-gse-fallback/${container.id}")
    }

    val steamworksBridge = remember(container.id, steamAppIdInt) {
        SteamworksJsBridge(
            containerId = container.id,
            appId = steamAppIdInt ?: 0,
            gseDir = gseDir,
        )
    }

    // pack:c3 + workerShim setup. owns the OPFS flush controller + mirror bridge that
    // shuttle bytes between c3's worker-side OPFS and the wine save dir. null for everything
    // else. closed implicitly when the WebView dies; flush dispatched explicitly in onDispose.
    val c3Setup = remember(container.id, profile?.engine, profile?.workerShim) {
        if (C3WorkerShimSetup.isActive(profile)) {
            C3WorkerShimSetup(container.id, container.installPath, viewModel.html5SaveSyncService)
        } else null
    }

    // gate webView.loadUrl on Html5SaveSyncService.syncInbound completion so localStorage
    // [gn:gw:*] is populated BEFORE the renderer's cloud-read IPC fires. closes a race where
    // the IPC could otherwise outrun cloud hydration; with the gate the IPC reads the
    // (now-populated) localStorage like every other greenworks call. flag keyed on container.id
    // so a flip-to-other-game restart resets it.
    var saveSyncInboundComplete by remember(container.id) { mutableStateOf(false) }

    // entry events -- match XServerScreen immersive UX.
    // wrapping in LaunchedEffect(Unit) -- otherwise these fire on every recomposition
    // and thrash immersive/orientation state while the WebView mid-loads.
    LaunchedEffect(Unit) {
        // pack:c3+workerShim titles need OPFS-SAH (chromium 109+ on android webview).
        // sub-109 devices fall back to Wine: persist runtime=wine so the next Play tap routes through
        // XServerScreen. user can re-opt-in via Container Config after updating Android System WebView.
        // gate fires BEFORE markActive so no html5-side state is mutated when we bail. SnackbarManager
        // surfaces the reason; project rule: no toasts. exit via BackPressed.
        if (c3Setup != null && !C3WorkerShimSetup.isSupported(context)) {
            Timber.tag("Html5WorkerShim").w(
                "OPFS-SAH unsupported on this WebView — persisting container.runtime=wine. appId=%s",
                appId,
            )
            SnackbarManager.show(context.getString(R.string.html5_opfs_unsupported_falling_back_to_wine))
            runCatching {
                val wineContainer = ContainerUtils.getContainer(context, appId)
                wineContainer.setRuntime(Container.RUNTIME_WINE)
                wineContainer.saveData()
            }.onFailure {
                Timber.tag("Html5WorkerShim").e(it, "failed to persist runtime=wine flip for appId=%s", appId)
            }
            PluviaApp.events.emit(AndroidEvent.BackPressed)
            return@LaunchedEffect
        }
        // terra titles hit the vendor GLES 256-uniform wall without the ANGLE override --
        // informational only (we can't write Settings.Global); game proceeds and fails to
        // render so the user sees WHY. no-op when override active or webview <118 (inert).
        if (AngleOverrideAdvisor.shouldSuggest(context, container)) {
            SnackbarManager.show(context.getString(R.string.html5_angle_override_suggested))
        }
        PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))
        PluviaApp.events.emit(
            AndroidEvent.SetAllowedOrientation(EnumSet.allOf(Orientation::class.java)),
        )
        // mark the active container + run launch-sync BEFORE the webview loads.
        // markActive must precede onDispose's WebViewDestroyed emit so the event-bus handler has
        // an appId to work with. syncInbound is mtime-gated -- fast no-op when webview is fresher.
        // failures are swallowed inside the service so loadUrl is never blocked.
        // part-C: pass engineProfile up-front so resolveSetup doesn't race the
        // WebViewContainer disk-load (Felvidek symptom: inbound saw empty engineProfile while
        // outbound at close saw it populated). container is already in memory here.
        viewModel.html5SaveSyncService.markActive(appId, container.engineProfile)
        viewModel.html5SaveSyncService.syncInbound(appId)
        // signal: localStorage is populated for greenworks reads. the gated LaunchedEffect
        // below will fire webView.loadUrl now that the renderer can safely read cloud bytes.
        saveSyncInboundComplete = true
        // pull install dir → OPFS for pack:c3+workerShim containers (no-op for others).
        c3Setup?.pullInstallToOpfs(appId)
        // attach diagnostic sink keyed on container.id (was slug); detach in onDispose.
        // parallel to app_webview/Profile-<container.id>/.
        if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
            viewModel.html5DiagnosticBridge.attach(container.id)
        }

        // eager achievement + stat seed BEFORE WebView loadUrl.
        // mirrors XServerScreen.kt watcher start. failure-soft per seed
        // failure (offline / network blip) falls back to fromDisk, watcher still starts so
        // future writes are caught (offline-skip in AchievementWatcher.kt suppresses upload).
        val gameIdInt = steamAppIdInt
        if (gameIdInt != null) {
            withContext(Dispatchers.IO) {
                val seedResult = runCatching {
                    Html5AchievementSeed.seed(context, gameIdInt, container)
                }.onFailure {
                    Timber.tag("WebViewScreen").w(it, "achievement seed failed; falling back to on-disk state")
                }.getOrNull() ?: runCatching {
                    Html5AchievementSeed.fromDisk(context, gameIdInt)
                }.onFailure {
                    Timber.tag("WebViewScreen").w(it, "fromDisk fallback also failed; bridge cache stays empty")
                }.getOrNull()

                // populate the SAME bridge instance JS will hit on binder thread. without this,
                // getAchievement / getStat* return defaults until the next storeStats() touch.
                seedResult?.let { sr ->
                    steamworksBridge.seedFromSchema(
                        achievements = sr.achievementsCache,
                        achTimes = sr.earnedTimes,
                        stats = sr.statsCache,
                        types = sr.statTypes,
                    )
                }

                // start watcher even on seed failure -- pre-existing achievements.json is the
                // snapshot baseline; offline-skip suppresses upload until reconnect.
                startAchievementWatcherForHtml5(
                    context = context,
                    appId = gameIdInt,
                    container = container,
                    seedResult = seedResult,
                )
            }
        }
    }

    // per-launch pack/asset resolution (installDir, decrypt contexts, zip/asar handles,
    // electron ctx). derivation grouped into rememberHtml5PackSetup; re-bound to locals here so
    // downstream interceptor/webView code is unchanged. handles closed in onDispose below.
    val packSetup = rememberHtml5PackSetup(context, container, profile, appId)
    val installDir: File = packSetup.installDir
    val omoriContext = packSetup.omoriContext
    val decryptContext = packSetup.decryptContext
    val nwArgvJson = packSetup.nwArgvJson
    val nwAppDataPath = packSetup.nwAppDataPath
    val mainModuleFilename = packSetup.mainModuleFilename
    val zipFile: ZipFile? = packSetup.zipFile
    val tpatchOverlays: List<ZipFile> = packSetup.tpatchOverlays
    val electronSetup: ElectronAsarSetup? = packSetup.electronSetup
    val electronCtx: Map<String, String>? = packSetup.electronCtx

    // start the loopback HTTP server so worker subresource fetches reach our content
    // source via the network stack. PlzDedicatedWorker (Chromium ≥ 113) bypasses
    // shouldInterceptRequest for module-worker dynamic imports -- workermain.js's
    // `import("/easystar-...js")` and friends MUST hit the network or they DNS-fail.
    // initial source is a stub returning 404; the real lambda is wired below once the
    // interceptor is built. dispose stops the bound socket on screen exit.
    // PluviaApp.onCreate already pre-tested the port via WebViewOrigin.init, so the bind
    // here normally succeeds. defensive guard: a tiny TIME_WAIT race or a process that
    // grabbed the port between init and now would throw → snackbar + pop back instead of
    // bringing down the compose tree.
    val localServer = remember(container.id) {
        runCatching { Html5LocalHttpServer { null } }
            .onFailure { Timber.tag("WebViewScreen").e(it, "loopback server bind failed") }
            .getOrNull()
    }
    if (localServer == null) {
        LaunchedEffect(Unit) {
            SnackbarManager.show(context.getString(R.string.webview_runtime_unavailable))
            navigateBack()
        }
        return
    }
    DisposableEffect(localServer) {
        onDispose { runCatching { localServer.stop() } }
    }

    // asset loader + custom path handler for install dir. AssetsPathHandler is asset-dir
    // only -- install dir is a file path, so we need an inline PathHandler. AssetInterceptor
    // pre-empts /index.html + /_shims/* before this handler is reached. path traversal
    // safeguard: canonical path must stay under installDir.canonicalPath.
    // per-container origin = http://<safeId>.localhost:<port>; AssetLoader matches on host
    // alone (port stripped by WebViewAssetLoader internally).
    // case-insensitive disk walk via Html5DiskPath -- Windows-authored titles assume CI fs
    // (common pattern: request `Ayami_Intro.webm` for `ayami_intro.webm` on disk).
    // %xx-decode happens before the walk because installDir is on disk and not URI-aware.
    val assetLoaderDomain = remember(container.id) { WebViewOrigin.hostFor(container.id) }
    val assetLoader = remember(installDir, assetLoaderDomain) {
        val installRootCanonical = installDir.canonicalPath
        WebViewAssetLoader.Builder()
            .setDomain(assetLoaderDomain)
            .setHttpAllowed(true)
            .addPathHandler("/") { path ->
                runCatching {
                    // addPathHandler's `path` arg is already percent-decoded by
                    // WebViewAssetLoader (it calls Uri.getPath() internally). decoding a second
                    // time is wrong: java.net.URLDecoder is form-decode (treats `+` as space) --
                    // RMMV plugin files like `QM+Followers.js` 404'd that way. ANY literal char
                    // that survives Uri's decode would also get mangled. just pass `path` through.
                    val resolved = Html5DiskPath.resolveCaseInsensitive(installDir, path)
                        ?: return@addPathHandler null
                    val canon = resolved.canonicalFile
                    if (!canon.path.startsWith(installRootCanonical)) {
                        return@addPathHandler null
                    }
                    if (!canon.exists() || !canon.isFile) return@addPathHandler null
                    val mime = mimeFor(canon.name)
                    // size hint lets the loopback server stream large disk media (RMMV webm)
                    // sequentially instead of buffering it whole. applyServeTime drops it if a
                    // transform (rmmv decrypt) changes the body -- see withContentLength.
                    WebResourceResponse(mime, "utf-8", FileInputStream(canon))
                        .withContentLength(canon.length())
                }.getOrNull()
            }
            .build()
    }

    // resolve navigator.language ONCE per (container, pref). keyed into
    // remember below so mid-session pref flip rebuilds interceptor cleanly.
    val locale = remember(container.language, PrefManager.appLanguage) {
        WebViewLocaleResolver.resolve(container.language, PrefManager.appLanguage)
    }

    // parse-time gestureConfig snippet for the unified touch.js shim.
    // sourced fresh from container.gestureConfig so a save (dialog Done) AND a container-id
    // recompose both rebuild the interceptor with the new JSON. fromJson tolerates "" / null /
    // malformed input by returning defaults round-trip) -- toJson emits all 16 fields
    // so touch.js never has to fall back to its own DEFAULTS at parse time.
    val gestureConfigJson = remember(container.gestureConfig) {
        TouchGestureConfig.fromJson(container.gestureConfig, TouchGestureConfig.html5Defaults()).toJson()
    }

    // perf: resolve effective DPR override.
    // container.renderScale < 0 → follow PrefManager.html5RenderScale (global)
    // container.renderScale == 0 → device-native (no override)
    // container.renderScale > 0 → explicit per-container value
    // returned Float? plumbs into IndexHtmlRewriter -- null = device-native, skip injection.
    // remember keys on container.renderScale so a dialog edit rebuilds the interceptor; we
    // intentionally DON'T key on PrefManager.html5RenderScale because changing the global mid-
    // session for an already-running container would tear the renderer down (PIXI/C3 cache DPR).
    val effectiveRenderScale: Float? = remember(container.renderScale) {
        val raw = if (container.renderScale < 0f) PrefManager.html5RenderScale else container.renderScale
        if (raw > 0f) raw else null
    }

    // zip containers get the zip interceptor; disk containers keep the file interceptor.
    // asar containers (pack:electron) take precedence -- asarArchive != null
    // short-circuits BEFORE the zip check. a pack:electron container would never also be
    // zip-hosted in practice, but interceptor precedence is stable (asar → zip → file).
    // profile + electronCtx + gestureConfigJson added to keys so interceptor rebuilds if any
    // changes (gestureConfig save flows through container.gestureConfig → JSON → key).
    // container.isTouchscreenMode also keyed so cold-boot picks up the user's persisted toggle.
    val interceptor: WebViewClient = remember(
        assetLoader, installDir, shimUrls, zipFile, electronSetup, profile, locale, electronCtx, gestureConfigJson, nwArgvJson, mainModuleFilename, effectiveRenderScale, container.isTouchscreenMode,
    ) {
        val injection = IndexInjectionConfig(
            locale = locale,
            electronCtx = electronCtx,
            gestureConfigJson = gestureConfigJson,
            nwArgvJson = nwArgvJson,
            nwAppDataPath = nwAppDataPath,
            mainModuleFilename = mainModuleFilename,
            electronPreloadUrl = electronSetup?.preloadUrl,
            renderScaleOverride = effectiveRenderScale,
            fsBridgeOnly = profile?.fsBridgeOnly == true,
            touchscreenMode = container.isTouchscreenMode,
            fillCanvas = profile?.fillCanvas == true,
        )
        val hydrationProvider: () -> Boolean = { viewModel.html5SaveSyncService.getWineHasFreshBytes() }
        // Windows form of the OPFS-mirrored wine save dir, for worker-fs.js's C:/ → OPFS mapping.
        // mirror root resolves async (pullInstallToOpfs) so this is a resolver, read per worker-stub
        // request. `<...>/.wine/drive_c/users/xuser/Saved Games/<game>` → `C:/users/xuser/Saved Games/<game>`.
        // GATE on the CURRENT container being pack:c3+workerShim -- not just getActiveMirrorRoot()
        // being non-null. that service field could be stale from a prior c3 game if a teardown's
        // clearActive() was skipped (crash); without this gate a later worker-spawning non-c3 pack
        // could get a stale __gnWinSaveRoot injected. with it, __gnWinSaveRoot is injected ONLY for
        // c3-worker containers, so worker-fs.js's C:/ translation is inert for every other pack.
        val winSaveRootProvider: () -> String? = {
            if (!C3WorkerShimSetup.isActive(profile)) {
                null
            } else {
                viewModel.html5SaveSyncService.getActiveMirrorRoot()?.let { root ->
                    val p = root.absolutePath.replace('\\', '/')
                    val idx = p.indexOf("/drive_c/")
                    if (idx >= 0) "C:/" + p.substring(idx + "/drive_c/".length) else null
                }
            }
        }
        when {
            electronSetup != null -> AsarAssetInterceptor(
                context = context,
                archive = electronSetup.archive,
                shimUrls = shimUrls,
                injection = injection,
                shouldWaitForMainHydrationProvider = hydrationProvider,
            )
            zipFile != null -> ZipAssetInterceptor(
                context,
                zipFile,
                shimUrls,
                patches = profile?.patches ?: emptyList(),
                decryptContext = decryptContext,
                injection = injection,
                overlayZips = tpatchOverlays,
                installDir = installDir,
                shouldWaitForMainHydrationProvider = hydrationProvider,
                winSaveRootProvider = winSaveRootProvider,
            )
            else -> AssetInterceptor(
                context,
                assetLoader,
                installDir,
                shimUrls,
                patches = profile?.patches ?: emptyList(),
                decryptContext = decryptContext,
                omoriContext = omoriContext,
                injection = injection,
                shouldWaitForMainHydrationProvider = hydrationProvider,
                winSaveRootProvider = winSaveRootProvider,
                effekseerWasmStub = EffekseerWasmGate.shouldStubWasm(context),
                contentEncodedCompression = profile?.contentEncodedCompression == true,
            )
        }
    }

    // wire the interceptor as the loopback server's content source. setSource is safe to
    // call multiple times -- last-write-wins; before this point the server returns 404 for
    // all requests (no race in practice because webView.loadUrl is gated below on
    // saveSyncInboundComplete which fires after the interceptor exists).
    DisposableEffect(localServer, interceptor) {
        val source: (android.net.Uri) -> WebResourceResponse? = when (val ic = interceptor) {
            is AssetInterceptor -> ic::serve
            is ZipAssetInterceptor -> ic::serve
            is AsarAssetInterceptor -> ic::serve
            else -> { _ -> null }
        }
        localServer.setSource(source)
        onDispose { localServer.setSource(null) }
    }

    // real on-disk ControlsProfile PER-CONTAINER (Wine
    // parity).was incorrectly global-by-name during the parity sweep --
    // remap in container A leaked into container B. now each container gets its own profile
    // in the global pool, referenced by container.controlsProfileId. resolution order:
    // 1. controlsProfileId > 0L AND profile exists → load it (per-container path)
    // 2. otherwise → factory mints a fresh profile, saves, returns; LaunchedEffect below
    // persists the new id back into container.controlsProfileId
    // pack's gamepadKeySynthesisMap is applied at default-profile
    // population AND as a one-shot migration of existing GAMEPAD_*→KEY_* bindings. Resolved
    // here from String→String JSON pairs to Binding→Binding once per (re-)compose.
    val packSynthMap: Map<Binding, Binding> =
        remember(profile) {
            profile?.gamepadKeySynthesisMap?.mapNotNull { (gamepadName, keyName) ->
                val gamepadBinding = runCatching { Binding.valueOf(gamepadName) }
                    .getOrNull()
                val keyBinding = runCatching { Binding.valueOf(keyName) }
                    .getOrNull()
                if (gamepadBinding == null || keyBinding == null) {
                    Timber.tag("WebViewScreen").w(
                        "skipping invalid gamepadKeySynthesisMap entry: %s -> %s",
                        gamepadName, keyName,
                    )
                    null
                } else {
                    gamepadBinding to keyBinding
                }
            }?.toMap().orEmpty()
        }
    val activeControlsProfile: ControlsProfile = remember(container.id, container.controlsProfileId, packSynthMap) {
        val manager = InputControlsManager(context)
        PluviaApp.inputControlsManager = manager
        Html5DefaultControlsProfileFactory.getOrCreate(context, container, packSynthMap.takeIf { it.isNotEmpty() })
    }

    // persist freshly minted profile id back to container if bootstrap
    // path took. ALSO seed pack default overlay elements on the same first launch (before
    // ICV reads loadElements). idempotent: seedIfEmpty checks profile JSON's elements array
    // and skips when non-empty (preserves user edits).
    LaunchedEffect(container.id, activeControlsProfile.id) {
        if (container.controlsProfileId == 0L && activeControlsProfile.id >= 0) {
            // fix: smart overlayVisible default at first launch ONLY. controller present
            // → overlay hidden (controller-first ux); no controller → overlay visible (otherwise
            // phone-only users have no input until they manually toggle). user's later explicit
            // INPUT_CONTROLS toggle wins on subsequent launches -- controlsProfileId gate ensures
            // we run this exactly once per container.
            val mgr = ControllerManager.getInstance()
            mgr.scanForDevices()
            val controllerPresent = mgr.getDetectedDevices().isNotEmpty()
            val smartOverlayVisible = !controllerPresent
            Timber.tag("WebViewScreen").i(
                "first-launch overlayVisible default=%b (controllerPresent=%b)",
                smartOverlayVisible, controllerPresent,
            )
            withContext(Dispatchers.IO) {
                val packOverlay = profile?.overlay
                val seeded = if (packOverlay != null) {
                    Html5OverlaySeed.seedIfEmpty(context, activeControlsProfile, packOverlay)
                } else {
                    false
                }
                val slug = WebViewScreenViewModel.slugFromAppId(appId)
                if (slug != null) {
                    val updated = container.copy(
                        controlsProfileId = activeControlsProfile.id.toLong(),
                        overlayVisible = smartOverlayVisible,
                    )
                    runCatching { WebViewContainer.save(slug, updated) }
                        .onFailure {
                            Timber.tag("WebViewScreen").w(it, "controlsProfileId persist failed")
                        }
                    // sync in-memory state so the LaunchedEffect(container.overlayVisible)
                    // observer below fires (sets ICV visibility) without waiting for relaunch.
                    withContext(Dispatchers.Main) {
                        container = updated
                    }
                }
                // ICV.onDraw lazy-loads elements ONCE; if seedIfEmpty wrote
                // elements after that initial load, the in-memory list stays empty until
                // next launch (overlay invisible on first run). post a re-load on the ICV
                // thread so freshly-seeded elements render this session.
                if (seeded) {
                    withContext(Dispatchers.Main) {
                        PluviaApp.inputControlsView?.let { icv ->
                            icv.post {
                                activeControlsProfile.loadElements(icv)
                                icv.invalidate()
                            }
                        }
                    }
                }
            }
        }
    }

    // in-game menu state. R3 press OR overlay MENU button
    // sets showQuickMenu = true; QuickMenu actions toggle showEditModeToolbar /
    // showPhysicalControllerDialog. mirror of XServerScreen.kt pattern.
    var showQuickMenu by remember { mutableStateOf(false) }
    var showEditModeToolbar by remember { mutableStateOf(false) }
    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var hasPhysicalController by remember { mutableStateOf(false) }
    // wine-parity: opened from TOUCHSCREEN_MODE's gear icon (shown only while mode is ON).
    // dialog Done writes both disk (container.gestureConfig) and live
    // (window.__gnGestureConfig via evaluateJavascript) -- no shim re-inject, no game-state loss.
    var showGestureDialog by remember { mutableStateOf(false) }
    // Wine-parity quick-menu dialog for overlay opacity +
    // visibility. opens via QuickMenuAction.INPUT_CONTROLS. live-applies to ICV; persists
    // to WebViewContainer JSON only on dialog Done (avoids per-tick save thrash).
    var showOverlayControlsDialog by remember { mutableStateOf(false) }
    // when EDIT_OVERLAY mode is active and user taps "Edit binding"
    // in the toolbar, show ElementEditorDialog (the same dialog Wine uses) for the currently
    // selected ICV element. null = no editor open.
    var elementToEdit by remember {
        mutableStateOf<ControlElement?>(null)
    }
    // snapshot container.overlayVisible at EDIT_OVERLAY entry so Done can
    // restore the user's preference. EDIT_OVERLAY auto-enables overlay visibility (you can't
    // edit what you can't see) but does NOT mutate container.overlayVisible -- only the explicit
    // INPUT_CONTROLS toggle persists. null = not currently in edit mode.
    var wasOverlayVisibleBeforeEdit by remember { mutableStateOf<Boolean?>(null) }
    val pickerScope = rememberCoroutineScope()

    // performance HUD state (Wine-parity port). FPS source is WebView pre-draw counter; the
    // HUD view + config is shared with XServerScreen via PerformanceHudView / PrefManager.
    // load/persist live on PerformanceHudConfig (fromPrefs/saveToPrefs).
    var isPerformanceHudEnabled by remember { mutableStateOf(PrefManager.showFps) }
    var performanceHudConfig by remember { mutableStateOf(PerformanceHudConfig.fromPrefs()) }
    val webViewFps = remember { mutableFloatStateOf(0f) }
    val frameTimeBuffer = remember { FrameTimeRingBuffer() }
    var hudHostWidth by remember { mutableIntStateOf(0) }
    var hudHostHeight by remember { mutableIntStateOf(0) }

    fun applyPerformanceHudConfig(c: PerformanceHudConfig) {
        performanceHudConfig = c
        c.saveToPrefs()
    }

    // resolve this app's html5 container slug + persist `updated`, logging failMsg on failure.
    // closes over appId. callers that need extra work in the slug-null branch (in-memory sync,
    // success logging) keep their own blocks -- this only collapses the bare resolve+save+log shape.
    fun persistContainer(updated: WebViewContainer, failMsg: String) {
        runCatching {
            val slug = WebViewScreenViewModel.slugFromAppId(appId)
            if (slug != null) {
                WebViewContainer.save(slug, updated)
            }
        }.onFailure { Timber.tag("WebViewScreen").w(it, failMsg) }
    }

    // keep ICV in sync with container.overlayOpacity / .overlayVisible.
    // container is loaded once via loadByAppId -- these effects fire on first composition
    // (no-op redundant with factory apply{} above) and any future state-driven container
    // re-load (recompose-driven). cheap idempotent setters; null-guard for ICV race.
    LaunchedEffect(container.overlayOpacity) {
        PluviaApp.inputControlsView?.setOverlayOpacity(container.overlayOpacity)
        PluviaApp.inputControlsView?.invalidate()
    }
    LaunchedEffect(container.overlayVisible) {
        // ICV is layered ON TOP of WebView via Compose Box. when invisible, the
        // ICV view still consumes the layout slot and paints its background -- leaving an all-grey
        // screen over the WebView. mirror Wine's hideInputControls (XServerScreen.kt): also
        // toggle View visibility GONE/VISIBLE so the WebView renders untouched.
        PluviaApp.inputControlsView?.let { icv ->
            icv.setShowTouchscreenControls(container.overlayVisible)
            icv.visibility = if (container.overlayVisible) View.VISIBLE else View.GONE
            Timber.tag("WebViewScreen").d("ICV visibility set: %s", if (container.overlayVisible) "VISIBLE" else "GONE")
            icv.invalidate()
        }
    }

    // refresh hasPhysicalController on QuickMenu open so the EDIT_PHYSICAL_CONTROLLER row
    // appears only when a controller is connected. mirrors XServerScreen.kt.
    LaunchedEffect(showQuickMenu) {
        if (showQuickMenu) {
            val mgr = ControllerManager.getInstance()
            mgr.scanForDevices()
            hasPhysicalController = mgr.getDetectedDevices().isNotEmpty()
        }
    }

    // back button opens QuickMenu instead of exiting (Wine parity).
    // - QuickMenu CLOSED → back opens it. user must use QuickMenu's EXIT_GAME row to leave.
    // - QuickMenu OPEN → its internal BackHandler (already wired) closes it. our handler stays
    // disabled in that state so the inner one wins (LIFO dispatch).
    // - showOverlayControlsDialog / showGestureDialog / showPhysicalControllerDialog all use
    // AlertDialog or Dialog which install their own BackHandler -- close themselves first.
    BackHandler(enabled = !showQuickMenu) {
        showQuickMenu = true
    }

    // bridge + synthesizer for KEY_*/MOUSE_* binding → DOM event spec.
    // bridge is JS-side queue (drained by input-synth.js per rAF tick). synthesizer translates
    // Binding press/release → JSON spec into the queue. ICV uses Html5BindingSink adapter
    // below to forward overlay button presses through the synthesizer when xServer == null.
    val html5InputBridge = remember { Html5InputBridge() }
    val html5InputSynthesizer = remember(html5InputBridge) { Html5InputSynthesizer(html5InputBridge) }

    // input controller wraps the on-disk ControlsProfile. PhysicalControllerHandler
    // writes profile.gamepadState; Html5GamepadBridge re-serializes it for navigator.getGamepads().
    // synthesizer wired so KEY_*/MOUSE_* bindings (overlay or remapped physical keys) route
    // through DOM event synthesis. GAMEPAD_* bindings -- overlay or physical -- both write the
    // same profile.gamepadState (single bridge, single navigator.getGamepads slot).
    val html5InputController = remember(activeControlsProfile.id, html5InputSynthesizer) {
        Html5InputController(
            profile = activeControlsProfile,
            synthesizer = html5InputSynthesizer,
        )
    }

    // surface which dispatch path is live at controller wire-up so
    // adb logcat shows the polyfill-vs-fallback choice without a debugger. polyfill is "active"
    // when the bridge is wired here AND the asset registers in shimUrls below -- the WebView
    // boot LaunchedEffect verifies typeof window.__gnVirtualGamepad to confirm at runtime.
    LaunchedEffect(html5InputController) {
        Timber.tag("Html5Input").d(
            "dispatch path: gamepad-polyfill (kotlin-side wired, runtime verifies on first dispatch) | keyboard-fallback (legacy path retained for non-polling games + missing-asset)",
        )
    }

    // fix: rebind the OPEN_NAVIGATION_MENU callback so overlay MENU button (or any
    // keycode the user remaps to OPEN_NAVIGATION_MENU) opens QuickMenu. setter pattern
    // avoids invalidating the Html5InputController across recomposition just to swap a
    // callback ref. captures the setter, not the value, so it always sets the latest state.
    LaunchedEffect(html5InputController) {
        html5InputController.setOnOpenNavigationMenu { showQuickMenu = true }
    }

    // wire the unified touch.js 3-finger-tap action `open_quick_menu` into
    // the QuickMenu state. touch.js dispatches a window 'gn-open-quickmenu' event; a JS-side
    // listener (installed via DisposableEffect below) hops it through __gnInputBridge.openQuickMenu.
    // single bridge per WebView is the project convention -- preferred over a fresh
    // @JavascriptInterface for one no-arg callback.
    LaunchedEffect(html5InputBridge) {
        html5InputBridge.onOpenQuickMenu = { showQuickMenu = true }
    }

    // <input type="file"> support -- WebView dispatches WebChromeClient.onShowFileChooser when
    // the user taps a file input. without an Activity-result-backed picker the chooser noops,
    // which silently breaks AD's "Import Save File" modal (and any other game using file
    // input). pendingFileChooserCallback is a single-slot mutable box: only one chooser is
    // ever in flight (Chromium guarantees this -- UI is modal). pickContentLauncher returns a
    // single Uri or null on cancel; we wrap in Array<Uri> for the callback contract.
    val pendingFileChooserCallback = remember {
        java.util.concurrent.atomic.AtomicReference<android.webkit.ValueCallback<Array<android.net.Uri>>?>(null)
    }
    val pickContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        val cb = pendingFileChooserCallback.getAndSet(null)
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    val webView = remember {
        WebView(context).apply {
            // pin to MATCH_PARENT before AndroidView attaches. default WRAP_CONTENT measures
            // to 0-height at first attach -- Chromium caches that as its layout viewport, so
            // `vh` and `%` in CSS resolve against 0 forever after, even once the view is
            // resized. pack:electron is most affected; html/body {height:100%} and interior
            // `vh` collapse despite window.innerHeight reporting the right value.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // black background -- covers the ~1s INBOUND-wait window between WebView attach
            // and gated loadUrl. without this Chromium paints WebView's default white. game's
            // own backgroundColor (set per pack via win.setBackgroundColor in Electron) takes
            // over once the page commits.
            setBackgroundColor(android.graphics.Color.BLACK)
            // per-container partitioning is achieved via the synthetic origin
            // http://<safeId>.localhost:<port> (chromium partitions IDB/LS/cookies by origin);
            // multi-profile API no longer used. all containers share the Default profile dir.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // source folder intercept-only -- no file:// access.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.databaseEnabled = true
            // Android WebView defaults to requiring a user gesture before any <video>/<audio>
            // plays -- mirrors mobile Chrome's autoplay policy. C3/nw.js games ship intro
            // cutscenes as <video autoplay>, which land on a white screen on boot until the
            // user taps. Desktop Chromium has no such requirement. Disable the gate so game
            // intros + splash videos start on their own.
            settings.mediaPlaybackRequiresUserGesture = false
            // pack-conditional viewport policy -- declarative via EngineProfile.wideViewport
            // (pack:electron + pack:c3 = true; rmmv/nwjs/others = false). WHY each pack chooses it:
            // - electron: fixed-width meta viewport (e.g. width=900) lays out at that CSS width and
            //   scales up ~2× on a handheld instead of rendering tiny at device px; no-meta Electron
            //   titles get the 980px desktop default too.
            // - c3 (covers c2runtime + c3runtime): without wide viewport the layout viewport == visual
            //   (~833 CSS on 1080p), and c2 useHighDpi=false computes mode-5 intscale=floor(833/480)=1
            //   (canvas locked 480x270); wide viewport gives the 980 CSS default so intscale=2 →
            //   960x540. resulting overflow is bounded by packs/c3.js's CSS max-width/height cap.
            //   loadWithOverviewMode scales layout to fit device width so c2's letterbox-centering
            //   math can't push canvas off-screen on non-16:10 devices (c3runtime declares
            //   width=device-width → no-op for it).
            // - rmmv/nwjs keep both OFF: they ship user-scalable=no with NO width, so the 980 default
            //   would scale the canvas off-screen.
            val wideViewport = profile?.wideViewport == true
            settings.useWideViewPort = wideViewport
            settings.loadWithOverviewMode = wideViewport
            // kill user-zoom so taps don't double-tap-zoom games.
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            // no overscroll bounce -- games own their own scrolling.
            overScrollMode = WebView.OVER_SCROLL_NEVER
            // suppress Android WebView's NATIVE scrollbars. our CSS sets html/body
            // overflow:hidden, but if a pack momentarily overflows (canvas mid-resize, layout
            // viewport > visual viewport during c3 useWideViewPort cap settle), Chromium's
            // built-in scrollbars still flash. games are full-bleed -- scrollbars are never
            // legitimate. visible on non-16:10 devices as brief scrollbar activity on startup
            // before c3.js's canvas-fit settles. with these off, transient overflow is silently
            // clipped instead.
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // suppress Android WebView's native long-press context menu (text-select / share).
            // can otherwise pop up on multi-finger taps and look like a game-side right-click.
            isLongClickable = false
            setOnLongClickListener { true }
            // suppress the Android View default focus-highlight overlay that paints a half-screen
            // grey rectangle on first DPad/controller key press. API 26+, minSdk 26 so no
            // version guard needed.
            defaultFocusHighlightEnabled = false
            // DPad / keyboard KeyEvents only reach WebView.onKeyDown → DOM
            // `keydown` when the WebView is the focused View. Compose's AndroidView doesn't
            // auto-focus the child. requestFocus() is scheduled post-attach in DisposableEffect
            // below -- calling it here inside .apply{} is a no-op (no ViewRoot yet).
            isFocusable = true
            isFocusableInTouchMode = true
            installPhysicalMouseHoverForwarding(this)
            // smoke post-crash: raise the renderer's priority so Android treats it
            // as foreground-bound -- less likely to be killed under memory pressure.
            // API 26+; minSdk 26 so no version guard. waivedWhenNotVisible=true means the
            // priority drops naturally when the app is backgrounded.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            // navigator UA spoof -- when profile opts in
            // (typically per-title via patches.json), rewrite the WebView's UA to a Windows
            // desktop Chrome of the same Chromium milestone. covers navigator.userAgent +
            // navigator.appVersion AND outbound HTTP request headers. JS-side desktop-spoof.js
            // shim handles navigator.platform + navigator.userAgentData, which can't be set
            // via WebSettings.
            if (profile?.desktopUaSpoof == true) {
                settings.userAgentString = synthesizeDesktopChromeUa(settings.userAgentString)
                Timber.tag("WebViewScreen").i("desktopUaSpoof active for %s: UA=%s", appId, settings.userAgentString)
            }
            if (BuildConfig.DEBUG) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            webViewClient = interceptor
            installDataUrlDownloadListener(this)
            webChromeClient = buildShaderAwareChromeClient(
                context = context,
                pendingFileChooserCallback = pendingFileChooserCallback,
                pickContentLauncher = pickContentLauncher,
                onCriticalShaderFailure = { PluviaApp.events.emit(AndroidEvent.BackPressed) },
            )
            // per-container fs sandbox bridge. registered FIRST
            // so shim JS (fs.js) sees __gnFsBridge at parse time. universal registration --
            // no per-profile flag; any HTML5 container that calls require('fs') gets
            // real on-disk I/O. sandboxRoot resolution reuses SaveDirectoryResolver's
            // Steam-vs-sideloaded branch . no Hilt -- bridge dies with WebView.
            
            // pack:electron with resolved productName picks the 5-arg
            // Wine-prefix overload so Steam Cloud UFS + Wine runtime see the same files. the
            // winlator Container synthesized here with just the id -- containerRootDir handles
            // the null-rootDir case via ImageFs-based derivation, producing the same path
            // activateContainer would land on. non-electron / missing-productName containers
            // keep the existing 2-arg overload (no regression for pack:rmmv / pack:c3 / sideloaded).
            // synth winlator Container -- resolver helpers below read id (drives containerRootDir)
            // and installPath (drives wine-wrap for GOG). reused for sandbox + wine drive_c
            // resolution so both paths see the same container identity.
            val winlatorContainerForFs = Container(container.id).also {
                it.installPath = container.installPath
            }
            val fsBridgeSandbox = when {
                profile?.engine == EnginePackId.ELECTRON && !electronSetup?.productName.isNullOrBlank() ->
                    SaveDirectoryResolver.resolveSandboxRoot(
                        context = context,
                        appId = appId,
                        container = winlatorContainerForFs,
                        profile = profile,
                        productName = electronSetup!!.productName!!,
                    )
                // pack:nwjs: NW.js Steam titles write saves via fs.writeFile to engine-relative
                // paths like `\Saves\Default\System.save`. derive sandbox from the title's UFS
                // pattern so writes land under the wine-prefix app-data dir Steam Cloud UFS
                // reads. parallels pack:electron's wine-prefix wrap.
                profile?.engine == EnginePackId.NWJS ->
                    SaveDirectoryResolver.resolveSandboxRootForNwjs(context, appId, winlatorContainerForFs)
                // 3-arg overload -- wraps GOG installPath through wine-prefix so fsBridge writes
                // land where GOG cloud sync reads. Steam still routes via SteamService.getAppDirPath
                // (already inside wine prefix). Sideloaded keeps raw installPath behavior.
                else -> SaveDirectoryResolver.resolveSandboxRoot(context, appId, winlatorContainerForFs)
            }
            // wine drive_c root for Windows-absolute path translation. set for every HTML5
            // container so games composing `C:/users/xuser/AppData/...` (the project posture
            // -- see IndexHtmlRewriter env literal) land in the right wine prefix location
            // where Steam UFS / GOG cloud sync reads.
            val fsBridgeWineDriveC = runCatching {
                SaveDirectoryResolver.resolveWineDriveC(context, winlatorContainerForFs)
            }.getOrNull()
            addJavascriptInterface(
                Html5FsBridge(
                    containerId = container.id,
                    sandboxRoot = fsBridgeSandbox,
                    onFsUsage = {
                        app.gamenative.html5.savesync.Html5FsAuthoritative
                            .markUsed(context.applicationContext, container.id)
                    },
                    wineDriveC = fsBridgeWineDriveC,
                ),
                "__gnFsBridge",
            )
            // SteamworksJsBridge keyed on container.id (was slug) -- log dir
            // html5-logs/<container.id>/ parallels app_webview/Profile-<container.id>/ for
            // uniform adb navigation.
            // bridge instance captured above so seedFromSchema (called from
            // LaunchedEffect on IO) and JS calls (binder thread) hit the SAME caches.
            addJavascriptInterface(steamworksBridge, "__gnSteamworksBridge")
            // single sync-read bridge for navigator.getGamepads() patch in gamepad.js. mirrors
            // profile.gamepadState (written by physical KeyEvent → handler.handleInputEvent AND
            // overlay tap → handler.applyBinding, same destination).
            addJavascriptInterface(html5InputController.bridge, "__gnGamepadBridge")
            // input-synth.js drains __gnInputBridge per rAF tick, so the bridge MUST be
            // registered as a JS interface here -- without it input-synth.js finds nothing to
            // drain and overlay button taps and any
            // KEY_*/MOUSE_* synthesizer pushes never reach the DOM.
            addJavascriptInterface(html5InputBridge, "__gnInputBridge")
            // dev-only diagnostic shim sink. gated so release builds don't expose
            // the @JavascriptInterface surface. attach/detach wraps LaunchedEffect + onDispose below.
            if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
                addJavascriptInterface(viewModel.html5DiagnosticBridge, "Html5DiagnosticBridge")
            }
            // v2: in-game quit → back to library. nw.js stub + pack shims route here.
            // call navigateBack directly instead of onBackPressedDispatcher so the
            // QuickMenu BackHandler isn't triggered (in-game quit must EXIT, not open the menu).
            addJavascriptInterface(
                Html5RuntimeBridge {
                    navigateBack()
                },
                "__gnRuntimeBridge",
            )
            // opfs-mirror bridge for pack:c3+workerShim containers. dies with the WebView;
            // lifecycle matches Html5FsBridge. no-op when c3Setup is null.
            c3Setup?.attachToWebView(this)
            // register WebView + bridge with the save-sync service during
            // composition so LaunchedEffect's syncInbound (greenworks branch) finds them
            // when its coroutine body runs. composition completes BEFORE LaunchedEffect
            // bodies fire -- register-then-check invariant satisfied.
            // declared inside the apply { } block because `val webView` is not in lexical scope
            // from the LaunchedEffect above; this is the earliest legal site.
            viewModel.html5SaveSyncService.setActiveWebView(this, steamworksBridge)
        }
    }

    // rebind webViewClient whenever `interceptor` rebuilds (gestureConfigJson, touchscreenMode,
    // locale, electronCtx, renderScale changes). the `remember { WebView(context).apply {
    // webViewClient = interceptor }}` block runs once; without this, shouldInterceptRequest
    // would continue routing through the stale client. localServer.setSource gets the new
    // interceptor via its own DisposableEffect above; this is the sibling for the webViewClient
    // surface (still load-bearing on Chromium <113 where many subresources DON'T loopback).
    DisposableEffect(webView, interceptor) {
        webView.webViewClient = interceptor
        onDispose { }
    }

    // feed WebView measured size into the synthesizer so cursor coords are
    // bounded to the actual viewport (default ctor uses 1x1). layout listener fires on every
    // size change including initial measure + rotation. cheap call (volatile writes only).
    DisposableEffect(webView, html5InputSynthesizer) {
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w > 0 && h > 0) {
                html5InputSynthesizer.updateViewport(w, h)
            }
        }
        webView.addOnLayoutChangeListener(listener)
        // immediate kick if already laid out
        if (webView.width > 0 && webView.height > 0) {
            html5InputSynthesizer.updateViewport(webView.width, webView.height)
        }
        onDispose { webView.removeOnLayoutChangeListener(listener) }
    }

    // any QuickMenu / child dialog open means "menu UI is in front of the game". derived state
    // so a tap from QuickMenu → sub-dialog doesn't briefly un-pause while showQuickMenu flips.
    // computed here (depends on the UI flags) and passed into the suspend controller.
    val anyMenuUiOpen = showQuickMenu ||
        showOverlayControlsDialog ||
        showGestureDialog ||
        showPhysicalControllerDialog ||
        showEditModeToolbar ||
        elementToEdit != null

    // suspend/resume lifecycle (policy resolve + menu-open pause/resume, incl. JS-side audio)
    // extracted to a controller; returns the manual-resume bits the teardown effect + resume
    // widget consume.
    val suspendController = rememberHtml5SuspendController(
        context = context,
        containerId = container.id,
        appId = appId,
        webView = webView,
        anyMenuUiOpen = anyMenuUiOpen,
    )
    val manualResumeMode = suspendController.manualResumeMode
    val resumeFromManual = suspendController.resumeFromManual

    // fix: key on container.id (stable per session) NOT the whole container object --
    // first-launch effect updates container.overlayVisible in-memory which would otherwise
    // tear down + recreate the WebView mid-load, causing a grey screen on first launch.
    Html5TeardownEffect(
        containerId = container.id,
        context = context,
        webView = webView,
        appId = appId,
        onExit = onExit,
        viewModel = viewModel,
        html5InputController = html5InputController,
        html5InputSynthesizer = html5InputSynthesizer,
        steamworksBridge = steamworksBridge,
        steamworksBridgeScope = steamworksBridgeScope,
        c3Setup = c3Setup,
        zipFile = zipFile,
        tpatchOverlays = tpatchOverlays,
        electronSetup = electronSetup,
        isQuickMenuOpen = { showQuickMenu },
        isManualResumeWaiting = { manualResumeMode && PluviaApp.isOverlayPaused && !anyMenuUiOpen },
        onResumeFromManual = resumeFromManual,
    )

    Html5GatedLoadEffect(
        saveSyncInboundComplete = saveSyncInboundComplete,
        webView = webView,
        containerId = container.id,
        entryPath = electronSetup?.resolvedEntry ?: container.entryPoint,
        inputModeLabel = "$resolvedMode",
    )

    // PerformanceHint session (API 30+). Tells Android's scheduler the main thread runs
    // latency-critical work -- boosts CPU frequency / pins a perf core when the thread runs,
    // reducing the chance the WebView audio renderer thread starves and trips a sync_reader
    // timeout → CHECK SIGTRAP. always-on while WebView is alive.
    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity ?: return@DisposableEffect onDispose {}
        val helper = PerformanceHintHelper.create(activity) ?: return@DisposableEffect onDispose {}
        val handler = Handler(Looper.getMainLooper())
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val totalNs = runCatching { metrics.getMetric(FrameMetrics.TOTAL_DURATION) }.getOrDefault(0L)
            helper.reportActualWorkDuration(totalNs)
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, handler) }
        onDispose {
            // close FIRST so any queued FrameMetrics callback sees the closed flag and
            // bails before reaching the freed native session. removeOnFrameMetrics...
            // does NOT drain pending Handler messages, so close-before-remove is the
            // race-free ordering.
            helper.close()
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }

    rememberHtml5FpsCounter(isPerformanceHudEnabled, context, webView, webViewFps, frameTimeBuffer)

    // ICV layered on TOP of WebView via Compose Box. xServer=null is safe
    // because pack default overlays + per-container profile bindings are GAMEPAD_*-only
    // (KEY_*/MOUSE_* synthesis routes via PhysicalControllerHandler → Html5InputSynthesizer,
    // not through ICV). InputControlsView.handleInputEvent line 945 null-guards winHandler for
    // GAMEPAD_* path; the unguarded xServer.injectKeyPress / injectPointerButtonPress branches
    // are only reachable for KEY_*/MOUSE_* -- never reachable here.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged {
                hudHostWidth = it.width
                hudHostHeight = it.height
            },
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx ->
                InputControlsView(ctx).apply {
                    setXServer(null)
                    setProfile(activeControlsProfile)
                    // route overlay button presses through the SAME dispatch path
                    // physical KeyEvents use (Html5InputController.dispatchBinding). GAMEPAD_*
                    // bindings honor the user's Edit Physical Controller remap (e.g. dpad → WASD)
                    // before falling back to the default GAMEPAD→KEY map. Wine path stays
                    // bytewise-identical (this branch only runs when xServer == null).
                    setHtml5BindingSink { binding, isDown, offset ->
                        html5InputController.dispatchBinding(binding, isDown, offset)
                    }
                    // apply persisted overlay opacity + visibility at construction.
                    // LaunchedEffect below re-applies on Container Config edits mid-session.
                    // initial View visibility must mirror overlayVisible to avoid
                    // ICV painting a grey background over the WebView on first launch (since item
                    // 2 default is now false).
                    setOverlayOpacity(container.overlayOpacity)
                    setShowTouchscreenControls(container.overlayVisible)
                    visibility = if (container.overlayVisible) View.VISIBLE else View.GONE
                }.also { icv ->
                    PluviaApp.inputControlsView = icv
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (isPerformanceHudEnabled) {
            Html5PerformanceHudOverlay(
                config = performanceHudConfig,
                fpsProvider = { webViewFps.floatValue },
                frameTimeBuffer = frameTimeBuffer,
                hostWidth = hudHostWidth,
                hostHeight = hudHostHeight,
            )
        }

        // in-game menu. isHtml5=true hides Wine-only tabs and shows EDIT_OVERLAY /
        // INPUT_CONTROLS / EDIT_PHYSICAL_CONTROLLER / TOUCHSCREEN_MODE / EXIT_GAME items.
        QuickMenu(
            isVisible = showQuickMenu,
            onDismiss = { showQuickMenu = false },
            isPerformanceHudEnabled = isPerformanceHudEnabled,
            performanceHudConfig = performanceHudConfig,
            onPerformanceHudConfigChanged = ::applyPerformanceHudConfig,
            isTouchscreenModeActive = container.isTouchscreenMode,
            onTouchGestureSettingsClick = { showGestureDialog = true },
            activeToggleIds = buildSet {
                if (container.isTouchscreenMode) add(QuickMenuAction.TOUCHSCREEN_MODE)
            },
            onItemSelected = { action ->
                when (action) {
                    QuickMenuAction.PERFORMANCE_HUD -> {
                        isPerformanceHudEnabled = !isPerformanceHudEnabled
                        PrefManager.showFps = isPerformanceHudEnabled
                        true
                    }
                    QuickMenuAction.EDIT_OVERLAY -> {
                        // force overlay visible while editing -- you can't edit
                        // invisible elements. snapshot prior visibility so Done restores it.
                        // mirrors onLiveVisible toggle-on path (line 1046-1054). does NOT touch
                        // container.overlayVisible -- only the explicit INPUT_CONTROLS toggle persists.
                        wasOverlayVisibleBeforeEdit = container.overlayVisible
                        PluviaApp.inputControlsView?.let { icv ->
                            icv.profile?.loadElements(icv)
                            icv.setShowTouchscreenControls(true)
                            icv.visibility = View.VISIBLE
                            icv.invalidate()
                        }
                        PluviaApp.inputControlsView?.setEditMode(true)
                        showEditModeToolbar = true
                        true
                    }
                    QuickMenuAction.INPUT_CONTROLS -> {
                        // Wine parity -- opacity + visibility live here.
                        showOverlayControlsDialog = true
                        true
                    }
                    QuickMenuAction.EDIT_PHYSICAL_CONTROLLER -> {
                        showPhysicalControllerDialog = true
                        true
                    }
                    QuickMenuAction.TOUCHSCREEN_MODE -> {
                        // wine-parity toggle. ON = touch.js interprets gestures (default).
                        // OFF = touch.js suspends, raw touch passes through to canvas.
                        // persist to disk + live-update via evaluateJavascript so the change
                        // takes effect on the next touch event (no shim reinject needed).
                        val newMode = !container.isTouchscreenMode
                        container = container.copy(isTouchscreenMode = newMode)
                        persistContainer(container, "isTouchscreenMode persist failed")
                        webView.evaluateJavascript("window.__gnTouchModeActive = $newMode;", null)
                        true
                    }
                    QuickMenuAction.EXIT_GAME -> {
                        // bypass our BackHandler -- call navigateBack directly so
                        // the NavHost pops without triggering "open QuickMenu" again.
                        navigateBack()
                        true
                    }
                    else -> false
                }
            },
            hasPhysicalController = hasPhysicalController,
            isHtml5 = true,
        )

        // EDIT_OVERLAY toolbar -- Add / Edit / Delete / Done.
        // mirrors Wine's EditModeToolbar (XServerScreen.kt) but kept HTML5-local so the
        // Wine private composable stays bytewise-identical. Edit opens ElementEditorDialog
        // (shared composable) for the currently selected ICV element.
        if (showEditModeToolbar) {
            Html5EditOverlayToolbar(
                onAdd = {
                    PluviaApp.inputControlsView?.let { icv ->
                        if (icv.addElement()) icv.invalidate()
                    }
                },
                onEdit = {
                    val sel = PluviaApp.inputControlsView?.selectedElement
                    if (sel != null) {
                        elementToEdit = sel
                    } else {
                        SnackbarManager.show(context.getString(R.string.html5_overlay_select_element_first))
                    }
                },
                onDelete = {
                    PluviaApp.inputControlsView?.removeElement()
                },
                onDone = {
                    PluviaApp.inputControlsView?.profile?.save()
                    PluviaApp.inputControlsView?.setEditMode(false)
                    // restore overlay visibility to the user's prior pref.
                    // wasOverlayVisibleBeforeEdit is non-null because we entered EDIT_OVERLAY.
                    // false → hide ICV (mirror onLiveVisible toggle-off path); true → leave shown.
                    val priorVisible = wasOverlayVisibleBeforeEdit ?: container.overlayVisible
                    PluviaApp.inputControlsView?.let { icv ->
                        icv.setShowTouchscreenControls(priorVisible)
                        icv.visibility = if (priorVisible) View.VISIBLE else View.GONE
                    }
                    wasOverlayVisibleBeforeEdit = null
                    PluviaApp.inputControlsView?.invalidate()
                    showEditModeToolbar = false
                },
            )
        }

        // manual-resume widget (mirror XServerScreen.kt). shown when suspendPolicy=manual
        // AND user-must-resume gate is set AND no menu UI is open. tap → clears isOverlayPaused
        // + resumes WebView + restores focus. fullscreen scrim eats stray touches so the user
        // can't accidentally interact with the underlying paused WebView.
        if (manualResumeMode && PluviaApp.isOverlayPaused && !anyMenuUiOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            color = Color.White,
                            shape = CircleShape,
                        )
                        .clickable(onClick = { resumeFromManual() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.resume_game),
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
    }

    WebViewScreenDialogs(
        elementToEdit = elementToEdit,
        onDismissElementEditor = { elementToEdit = null },
        showPhysicalControllerDialog = showPhysicalControllerDialog,
        onDismissPhysicalControllerDialog = { showPhysicalControllerDialog = false },
        activeControlsProfile = activeControlsProfile,
        html5InputSynthesizer = html5InputSynthesizer,
        showGestureDialog = showGestureDialog,
        onDismissGestureDialog = { showGestureDialog = false },
        showOverlayControlsDialog = showOverlayControlsDialog,
        onDismissOverlayControlsDialog = { showOverlayControlsDialog = false },
        container = container,
        onContainerChange = { container = it },
        webView = webView,
        appId = appId,
        pickerScope = pickerScope,
        persistContainer = ::persistContainer,
    )
}
