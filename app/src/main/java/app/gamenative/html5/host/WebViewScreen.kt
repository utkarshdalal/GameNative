package app.gamenative.html5.host

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
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
import app.gamenative.html5.asar.AsarAssetInterceptor
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
import app.gamenative.html5.shim.SteamworksJsBridge
import app.gamenative.runtime.WebViewContainer
import app.gamenative.service.SteamService
import app.gamenative.ui.component.PerformanceQuickMenuState
import app.gamenative.ui.component.QuickMenu
import app.gamenative.ui.component.QuickMenuAction
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.enums.Orientation
import app.gamenative.ui.util.SnackbarManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import app.gamenative.html5.profile.EnginePackId
import app.gamenative.data.GameSource

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    appId: String,
    navigateBack: () -> Unit,
    // in-session exits only -- see dispatchWebViewSessionExit for why it differs from navigateBack.
    exitSession: () -> Unit,
    onExit: (onComplete: (() -> Unit)?) -> Unit,
    viewModel: WebViewScreenViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

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

    // mutable so dialog saves update local state too; otherwise observers and reopened dialogs see stale values.
    var container by remember(loaded) { mutableStateOf(loaded.container) }
    val profile = loaded.profile

    LaunchedEffect(loaded) {
        Timber.tag("WebViewScreen").d(
            "container loaded: id=%s overlayVisible=%b overlayOpacity=%.2f controlsProfileId=%d inputMap=%s",
            container.id, container.overlayVisible, container.overlayOpacity,
            container.controlsProfileId, container.inputMap,
        )
    }

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

    // null for non-Steam titles, which disables achievements entirely.
    val steamAppIdInt = remember(appId) {
        if (GameSource.STEAM.matches(appId)) GameSource.STEAM.idOf(appId).toIntOrNull() else null
    }

    // non-Steam gets a scratch dir nothing reads, so bridge writes stay harmless.
    val gseDir = remember(steamAppIdInt, container.id) {
        steamAppIdInt?.let { SteamService.getGseSaveDirs(context, it).firstOrNull() }
            ?: File(context.filesDir, "html5-gse-fallback/${container.id}")
    }

    val steamLanguage = remember(container.language, PrefManager.appLanguage) {
        WebViewLocaleResolver.resolveSteamLanguage(container.language, PrefManager.appLanguage)
    }

    val steamworksBridge = remember(container.id, steamAppIdInt, steamLanguage) {
        SteamworksJsBridge(
            containerId = container.id,
            appId = steamAppIdInt ?: 0,
            gseDir = gseDir,
            gameLanguage = steamLanguage,
        )
    }

    val c3Setup = remember(container.id, profile?.engine, profile?.workerShim) {
        if (C3WorkerShimSetup.isActive(profile)) {
            C3WorkerShimSetup(container.id, container.installPath, viewModel.html5SaveSyncService)
        } else null
    }

    // gates loadUrl until inbound sync has populated localStorage, so game JS never reads pre-sync saves.
    var saveSyncInboundComplete by remember(container.id) { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // no OPFS-SAH: persist runtime=wine so the next launch goes through Wine. runs BEFORE markActive so
        // no html5 state is touched when bailing.
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
        // informational only; the game still launches (and fails to render) so the user sees why.
        if (AngleOverrideAdvisor.shouldSuggest(context, container)) {
            SnackbarManager.show(context.getString(R.string.html5_angle_override_suggested))
        }
        PluviaApp.events.emit(AndroidEvent.SetSystemUIVisibility(false))
        PluviaApp.events.emit(
            AndroidEvent.SetAllowedOrientation(EnumSet.allOf(Orientation::class.java)),
        )
        // BEFORE the webview loads. engineProfile is passed in because the service's own disk load can race
        // and see it empty. sync failures are swallowed so loadUrl is never blocked.
        viewModel.html5SaveSyncService.markActive(appId, container.engineProfile)
        viewModel.html5SaveSyncService.syncInbound(appId)
        saveSyncInboundComplete = true
        c3Setup?.pullInstallToOpfs(appId)
        if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
            viewModel.html5DiagnosticBridge.attach(container.id)
        }

        // seed failure (offline) falls back to on-disk state.
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

                // the SAME bridge instance JS calls; otherwise getAchievement / getStat* return defaults.
                seedResult?.let { sr ->
                    steamworksBridge.seedFromSchema(
                        achievements = sr.achievementsCache,
                        achTimes = sr.earnedTimes,
                        stats = sr.statsCache,
                        types = sr.statTypes,
                    )
                }

                // start even on seed failure: the existing achievements.json is the baseline.
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

    // see Html5LocalHttpServer for why. the port was pre-tested at app start, but another process can still
    // grab it; fail back to the library rather than crash.
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

    // install dir is a file path, so it needs an inline PathHandler (AssetsPathHandler is assets-only).
    // canonical path must stay under installDir.
    val assetLoaderDomain = remember(container.id) { WebViewOrigin.hostFor(container.id) }
    val assetLoader = remember(installDir, assetLoaderDomain) {
        val installRootCanonical = installDir.canonicalPath
        WebViewAssetLoader.Builder()
            .setDomain(assetLoaderDomain)
            .setHttpAllowed(true)
            .addPathHandler("/") { path ->
                runCatching {
                    // `path` is already percent-decoded; decoding again mangles literal `+` in file names.
                    val resolved = Html5DiskPath.resolveCaseInsensitive(installDir, path)
                        ?: return@addPathHandler null
                    val canon = resolved.canonicalFile
                    if (!canon.path.startsWith(installRootCanonical)) {
                        return@addPathHandler null
                    }
                    if (!canon.exists() || !canon.isFile) return@addPathHandler null
                    val mime = mimeFor(canon.name)
                    WebResourceResponse(mime, "utf-8", FileInputStream(canon))
                        .withContentLength(canon.length())
                }.getOrNull()
            }
            .build()
    }

    val locale = remember(container.language, PrefManager.appLanguage) {
        WebViewLocaleResolver.resolve(container.language, PrefManager.appLanguage)
    }

    val gestureConfigJson = remember(container.gestureConfig) {
        TouchGestureConfig.fromJson(container.gestureConfig, TouchGestureConfig.html5Defaults()).toJson()
    }

    // renderScale < 0 = follow the global pref, 0 = device-native (null), > 0 = explicit.
    // deliberately NOT keyed on the global pref: changing it mid-session would tear down the renderer.
    val effectiveRenderScale: Float? = remember(container.renderScale) {
        val raw = if (container.renderScale < 0f) PrefManager.html5RenderScale else container.renderScale
        if (raw > 0f) raw else null
    }

    // precedence: asar -> zip -> disk.
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
        // `<...>/drive_c/users/xuser/Saved Games/<game>` -> `C:/users/xuser/Saved Games/<game>`.
        // gate on THIS container being c3-worker: the mirror root can be stale from a prior c3 game whose
        // teardown was skipped (crash), and must not leak into another pack's workers.
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

    // one ControlsProfile PER container (a shared one leaked remaps across games), referenced by
    // controlsProfileId; the factory mints one when missing and the effect below persists its id.
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

    // first launch only: persist the new profile id and seed the pack's default overlay (seedIfEmpty keeps
    // user edits).
    LaunchedEffect(container.id, activeControlsProfile.id) {
        if (container.controlsProfileId == 0L && activeControlsProfile.id >= 0) {
            // hide the overlay if a controller is present; otherwise touch-only users would have no input.
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
                    withContext(Dispatchers.Main) {
                        container = updated
                    }
                }
                // ICV loads elements once on first draw, which may predate the seed; reload so they show now.
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

    var showQuickMenu by remember { mutableStateOf(false) }
    var showEditModeToolbar by remember { mutableStateOf(false) }
    var showPhysicalControllerDialog by remember { mutableStateOf(false) }
    var hasPhysicalController by remember { mutableStateOf(false) }
    var showGestureDialog by remember { mutableStateOf(false) }
    var showOverlayControlsDialog by remember { mutableStateOf(false) }
    var elementToEdit by remember {
        mutableStateOf<ControlElement?>(null)
    }
    // edit mode forces the overlay visible without persisting it; this restores the user's setting on Done.
    // null = not in edit mode.
    var wasOverlayVisibleBeforeEdit by remember { mutableStateOf<Boolean?>(null) }
    val pickerScope = rememberCoroutineScope()

    var isPerformanceHudEnabled by remember { mutableStateOf(PrefManager.showFps) }
    var performanceHudConfig by remember { mutableStateOf(PerformanceHudConfig.fromPrefs()) }
    val webViewFps = remember { mutableFloatStateOf(0f) }
    var hudHostWidth by remember { mutableIntStateOf(0) }
    var hudHostHeight by remember { mutableIntStateOf(0) }

    fun applyPerformanceHudConfig(c: PerformanceHudConfig) {
        performanceHudConfig = c
        c.saveToPrefs()
    }

    fun persistContainer(updated: WebViewContainer, failMsg: String) {
        runCatching {
            val slug = WebViewScreenViewModel.slugFromAppId(appId)
            if (slug != null) {
                WebViewContainer.save(slug, updated)
            }
        }.onFailure { Timber.tag("WebViewScreen").w(it, failMsg) }
    }

    LaunchedEffect(container.overlayOpacity) {
        PluviaApp.inputControlsView?.setOverlayOpacity(container.overlayOpacity)
        PluviaApp.inputControlsView?.invalidate()
    }
    LaunchedEffect(container.overlayVisible) {
        // View visibility must flip too: a hidden-controls ICV still paints a grey background over the WebView.
        PluviaApp.inputControlsView?.let { icv ->
            icv.setShowTouchscreenControls(container.overlayVisible)
            icv.visibility = if (container.overlayVisible) View.VISIBLE else View.GONE
            Timber.tag("WebViewScreen").d("ICV visibility set: %s", if (container.overlayVisible) "VISIBLE" else "GONE")
            icv.invalidate()
        }
    }

    LaunchedEffect(showQuickMenu) {
        if (showQuickMenu) {
            val mgr = ControllerManager.getInstance()
            mgr.scanForDevices()
            hasPhysicalController = mgr.getDetectedDevices().isNotEmpty()
        }
    }

    // back opens QuickMenu rather than exiting. disabled while it's open so QuickMenu's own BackHandler wins.
    BackHandler(enabled = !showQuickMenu) {
        showQuickMenu = true
    }

    // KEY_*/MOUSE_* bindings become DOM events via a JS-side queue drained per rAF by input-synth.js.
    val html5InputBridge = remember { Html5InputBridge() }
    val html5InputSynthesizer = remember(html5InputBridge) { Html5InputSynthesizer(html5InputBridge) }

    // GAMEPAD_* bindings, overlay and physical alike, write the same profile.gamepadState that
    // navigator.getGamepads() reads.
    val html5InputController = remember(activeControlsProfile.id, html5InputSynthesizer) {
        Html5InputController(
            profile = activeControlsProfile,
            synthesizer = html5InputSynthesizer,
        )
    }

    // setter, so swapping the callback doesn't rebuild the controller.
    LaunchedEffect(html5InputController) {
        html5InputController.setOnOpenNavigationMenu { showQuickMenu = true }
    }

    // touch.js's `open_quick_menu` gesture.
    LaunchedEffect(html5InputBridge) {
        html5InputBridge.onOpenQuickMenu = { showQuickMenu = true }
    }

    // <input type="file"> silently does nothing without an activity-result picker behind onShowFileChooser.
    // single slot: chromium only has one chooser in flight.
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
            // MUST be set before attach: WRAP_CONTENT measures 0 height at first attach and chromium keeps that
            // as its layout viewport, so CSS `vh` / `%` resolve against 0 forever.
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            // avoids a white flash while loadUrl waits on inbound save sync.
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // everything is served via interception -- no file:// access.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.databaseEnabled = true
            // desktop chromium has no autoplay gesture gate; intro <video autoplay> would otherwise stall.
            settings.mediaPlaybackRequiresUserGesture = false
            // per pack (EngineProfile.wideViewport):
            // - electron: a fixed-width meta viewport lays out at that width and scales up instead of rendering tiny.
            // - c3: c2 integer scaling needs the 980px default width to get past 1x; overview mode keeps its
            //   letterbox centering on-screen.
            // - rmmv/nwjs: OFF -- no viewport width, so the 980 default would push the canvas off-screen.
            val wideViewport = profile?.wideViewport == true
            settings.useWideViewPort = wideViewport
            settings.loadWithOverviewMode = wideViewport
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            overScrollMode = WebView.OVER_SCROLL_NEVER
            // CSS overflow:hidden alone still lets native scrollbars flash during transient overflow (canvas resize).
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            // the native context menu can pop on multi-finger taps.
            isLongClickable = false
            setOnLongClickListener { true }
            // the default focus highlight paints a grey rectangle on the first dpad press.
            defaultFocusHighlightEnabled = false
            // key events only reach DOM keydown when the WebView has focus. focus is requested after
            // attach -- here there's no ViewRoot yet.
            isFocusable = true
            isFocusableInTouchMode = true
            installPhysicalMouseHoverForwarding(this)
            // less likely to be killed under memory pressure; waived while backgrounded.
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
            // covers navigator.userAgent and request headers; desktop-spoof.js covers what WebSettings can't.
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
                // exitSession, not back: an in-game quit must EXIT, not open the QuickMenu.
                onEngineExit = { exitSession() },
                isGodotEngine = profile?.engine == EnginePackId.GODOT,
            )
            // fs sandbox roots resolve inside the wine prefix so Steam / GOG cloud sync and the Wine runtime
            // see the same files. a bare Container(id) is enough for the resolvers.
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
                // NW.js titles write engine-relative save paths; root them where the title's Steam Cloud pattern reads.
                profile?.engine == EnginePackId.NWJS ->
                    SaveDirectoryResolver.resolveSandboxRootForNwjs(context, appId, winlatorContainerForFs)
                else -> SaveDirectoryResolver.resolveSandboxRoot(context, appId, winlatorContainerForFs)
            }
            // maps games' absolute `C:/...` paths into the wine prefix.
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
            // the SAME instance the achievement seed populates.
            addJavascriptInterface(steamworksBridge, "__gnSteamworksBridge")
            // launch-time localStorage restore for ls-restore.js, staged by syncInbound before loadUrl.
            addJavascriptInterface(
                app.gamenative.html5.savesync.Html5LocalStorageRestoreBridge(appId, viewModel.html5SaveSyncService),
                "__gnLsRestoreBridge",
            )
            addJavascriptInterface(html5InputController.bridge, "__gnGamepadBridge")
            addJavascriptInterface(html5InputBridge, "__gnInputBridge")
            // gated so release builds don't expose this interface.
            if (FeatureGate.ENABLE_HTML5_DIAGNOSTIC_SHIM) {
                addJavascriptInterface(viewModel.html5DiagnosticBridge, "Html5DiagnosticBridge")
            }
            // exitSession, not back: an in-game quit must EXIT, not open the QuickMenu.
            addJavascriptInterface(
                Html5RuntimeBridge {
                    exitSession()
                },
                "__gnRuntimeBridge",
            )
            c3Setup?.attachToWebView(this)
            // registered during composition so syncInbound (a LaunchedEffect, which runs later) finds it.
            viewModel.html5SaveSyncService.setActiveSteamworksBridge(steamworksBridge)
        }
    }

    // the WebView is built once; rebind so a rebuilt interceptor isn't bypassed by the stale client.
    DisposableEffect(webView, interceptor) {
        webView.webViewClient = interceptor
        onDispose { }
    }

    // bounds synthesized cursor coords to the real viewport.
    DisposableEffect(webView, html5InputSynthesizer) {
        val listener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w > 0 && h > 0) {
                html5InputSynthesizer.updateViewport(w, h)
            }
        }
        webView.addOnLayoutChangeListener(listener)
        if (webView.width > 0 && webView.height > 0) {
            html5InputSynthesizer.updateViewport(webView.width, webView.height)
        }
        onDispose { webView.removeOnLayoutChangeListener(listener) }
    }

    // includes sub-dialogs so moving from QuickMenu into one doesn't briefly un-pause.
    val anyMenuUiOpen = showQuickMenu ||
        showOverlayControlsDialog ||
        showGestureDialog ||
        showPhysicalControllerDialog ||
        showEditModeToolbar ||
        elementToEdit != null

    val suspendController = rememberHtml5SuspendController(
        context = context,
        containerId = container.id,
        appId = appId,
        webView = webView,
        anyMenuUiOpen = anyMenuUiOpen,
    )
    val manualResumeMode = suspendController.manualResumeMode
    val resumeFromManual = suspendController.resumeFromManual

    // keyed on container.id, NOT the container: the first-launch effect mutates it, which would recreate the
    // WebView mid-load.
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
            // close FIRST: removing the listener doesn't drain queued callbacks, which must see the closed flag.
            helper.close()
            runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        }
    }

    rememberHtml5FpsCounter(isPerformanceHudEnabled, context, webView, webViewFps)

    // xServer = null is safe: overlay presses go through the html5 binding sink, never ICV's xServer branches.
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
                    // same dispatch as physical keys, so overlay GAMEPAD_* buttons honor the user's remaps.
                    setHtml5BindingSink { binding, isDown, offset ->
                        html5InputController.dispatchBinding(binding, isDown, offset)
                    }
                    // View visibility must match overlayVisible or ICV paints grey over the WebView.
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
                hostWidth = hudHostWidth,
                hostHeight = hudHostHeight,
            )
        }

        QuickMenu(
            isVisible = showQuickMenu,
            onDismiss = { showQuickMenu = false },
            performance = PerformanceQuickMenuState(
                hudEnabled = isPerformanceHudEnabled,
                hudConfig = performanceHudConfig,
                onHudConfigChanged = ::applyPerformanceHudConfig,
            ),
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
                        // force visible for editing without persisting; Done restores the snapshot.
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
                        showOverlayControlsDialog = true
                        true
                    }
                    QuickMenuAction.EDIT_PHYSICAL_CONTROLLER -> {
                        showPhysicalControllerDialog = true
                        true
                    }
                    QuickMenuAction.TOUCHSCREEN_MODE -> {
                        // OFF passes raw touch through to the canvas. applied live, no reload.
                        val newMode = !container.isTouchscreenMode
                        container = container.copy(isTouchscreenMode = newMode)
                        persistContainer(container, "isTouchscreenMode persist failed")
                        webView.evaluateJavascript("window.__gnTouchModeActive = $newMode;", null)
                        true
                    }
                    QuickMenuAction.EXIT_GAME -> {
                        // not back: our BackHandler would just reopen the QuickMenu.
                        exitSession()
                        true
                    }
                    else -> false
                }
            },
            hasPhysicalController = hasPhysicalController,
            isHtml5 = true,
        )

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

        // manual-resume widget. the fullscreen scrim eats stray touches meant for the paused game.
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
