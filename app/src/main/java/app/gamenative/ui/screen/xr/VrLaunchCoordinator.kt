package app.gamenative.ui.screen.xr

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import app.gamenative.BuildConfig
import app.gamenative.MainActivity
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.data.GameSource
import app.gamenative.events.SteamEvent
import app.gamenative.service.SteamService
import app.gamenative.ui.screen.xr.windows.WindowsVrRuntimeConfig
import app.gamenative.ui.screen.xr.windows.WindowsVrRuntimeService
import app.gamenative.ui.screen.xr.windows.WindowsVrSessionListener
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CURRENT_VR_CATEGORY_PARSE_VERSION
import app.gamenative.utils.LaunchMode
import app.gamenative.utils.NonVrLaunchArgs
import app.gamenative.utils.SteamLaunchOptions
import app.gamenative.utils.ContainerUtils
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import timber.log.Timber

/**
 * The game boots in the launcher window and [VrGameActivity] only comes up once it creates its
 * OpenXR session; until then the runtime reports a SYNCHRONIZED session.
 */
object VrLaunchCoordinator : WindowsVrSessionListener {
    private const val WINDOWS_VR_EXTRA = "windowsVrEnabled"
    private const val VR_DEFAULT_APPLIED_EXTRA = "vrDefaultApplied"
    private const val SESSION_END_GRACE_MS = 3_000L
    private const val ACTIVITY_START_TIMEOUT_MS = 8_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var runtime: WindowsVrRuntimeService? = null
        private set

    @Volatile
    internal var activity: VrGameActivity? = null

    @Volatile
    internal var refreshRateHz = 72
        private set

    @Volatile
    internal var gameTitle = ""
        private set

    private var appContext: Context? = null

    // Any other destruction of the VR activity (Quit from the Horizon menu) means the player left.
    @Volatile
    private var closingOnPurpose = false

    private val leaveVrAfterSessionEnd = Runnable {
        Timber.i("VR launch: game ended its OpenXR session, returning to the 2D window")
        activity?.let { vrActivity ->
            closingOnPurpose = true
            vrActivity.finish()
        }
    }

    private val activityStartWatchdog = Runnable {
        if (activity == null && PluviaApp.vrHandoffActive) {
            Timber.w("VR launch: headset activity did not start")
            onVrActivityClosed()
            appContext?.let { SnackbarManager.show(it.getString(R.string.vr_game_start_failed)) }
        }
    }

    // Ticks VR once per VR-only game; the marker keeps the player's later choice. Off main thread.
    fun applyVrOnlyDefault(context: Context, container: Container): Boolean {
        if (!BuildConfig.XR_BUILD || !MainActivity.isHeadset(context)) return false
        if (container.getExtra(VR_DEFAULT_APPLIED_EXTRA).toBoolean()) return false
        val app = steamApp(container) ?: return false
        if (app.vrCategoryParseVersion < CURRENT_VR_CATEGORY_PARSE_VERSION) return false
        container.putExtra(VR_DEFAULT_APPLIED_EXTRA, "true")
        if (app.isVrOnly) container.putExtra(WINDOWS_VR_EXTRA, "true")
        container.saveData()
        if (app.isVrOnly) Timber.i("VR launch: %s is VR only, VR launch enabled by default", container.id)
        return app.isVrOnly
    }

    // A VR only game can't run flat, so arguments that turn VR off (often from shared configs) go.
    fun dropNonVrArgs(context: Context, container: Container) {
        if (launchMode(context, container) != LaunchMode.VR || vrGameKind(container) != VrGameKind.ONLY) return
        val found = NonVrLaunchArgs.find(container.execArgs)
        if (found.isEmpty()) return
        container.execArgs = NonVrLaunchArgs.strip(container.execArgs)
        container.saveData()
        Timber.i("VR launch: removed %s from %s launch arguments", found, container.id)
        SnackbarManager.show(context.getString(R.string.non_vr_args_removed, found.joinToString(" ")))
    }

    enum class VrGameKind { NONE, SUPPORTED, ONLY }

    fun vrGameKind(container: Container): VrGameKind {
        val app = steamApp(container)
        return when {
            app?.isVrOnly == true -> VrGameKind.ONLY
            app?.isVrGame == true -> VrGameKind.SUPPORTED
            else -> VrGameKind.NONE
        }
    }

    /** Null while Steam hasn't sent the game's VR categories yet (e.g. right after install). */
    fun vrGameKindIfKnown(container: Container): VrGameKind? {
        if (ContainerUtils.extractGameSourceFromContainerId(container.id) != GameSource.STEAM) return VrGameKind.NONE
        val app = steamApp(container) ?: return null
        return if (app.vrCategoryParseVersion < CURRENT_VR_CATEGORY_PARSE_VERSION) null else vrGameKind(container)
    }

    // VR only games never use the immersive screen; for VR supported ones VR wins over it.
    fun shouldUse(context: Context, container: Container, kind: VrGameKind): Boolean =
        BuildConfig.XR_BUILD &&
            MainActivity.isHeadset(context) &&
            WindowsVrRuntimeConfig.from(container).enabled &&
            (kind != VrGameKind.NONE || !container.isLaunchImmersiveMode())

    fun launchMode(context: Context, container: Container): LaunchMode =
        if (BuildConfig.XR_BUILD && MainActivity.isHeadset(context) && WindowsVrRuntimeConfig.from(container).enabled) {
            LaunchMode.VR
        } else {
            LaunchMode.FLAT
        }

    fun resolveLaunchInfo(context: Context, container: Container, gameId: Int): app.gamenative.data.LaunchInfo? =
        SteamLaunchOptions.resolve(container, gameId, launchMode(context, container))

    fun useImmersive(context: Context, container: Container, kind: VrGameKind): Boolean =
        BuildConfig.XR_BUILD &&
            MainActivity.isHeadset(context) &&
            container.isLaunchImmersiveMode() &&
            kind != VrGameKind.ONLY &&
            !(kind == VrGameKind.SUPPORTED && WindowsVrRuntimeConfig.from(container).enabled)

    private fun steamApp(container: Container): app.gamenative.data.SteamApp? {
        if (ContainerUtils.extractGameSourceFromContainerId(container.id) != GameSource.STEAM) return null
        return SteamService.getAppInfoOf(ContainerUtils.extractGameIdFromContainerId(container.id))
    }

    fun prepare(context: Context, container: Container) {
        close()
        appContext = context.applicationContext
        refreshRateHz = container.xrRefreshRate
        gameTitle = container.name.orEmpty()
        runtime = WindowsVrRuntimeService(context.applicationContext).also { it.sessionListener = this }
        Timber.i("VR launch: prepared 2D-first VR for %s", container.id)
    }

    fun windowsVr(hooks: ImmersiveSessionHooks?): WindowsVrRuntimeService? =
        if (hooks != null) hooks.windowsVr else runtime

    override fun onVrRequested() {
        mainHandler.post {
            mainHandler.removeCallbacks(leaveVrAfterSessionEnd)
            enterVr()
        }
    }

    override fun onVrSessionEnded() {
        mainHandler.post {
            mainHandler.removeCallbacks(leaveVrAfterSessionEnd)
            if (activity != null) mainHandler.postDelayed(leaveVrAfterSessionEnd, SESSION_END_GRACE_MS)
        }
    }

    fun enterVr() {
        val context = appContext ?: return
        if (runtime?.isEnabled != true || activity != null) return
        PluviaApp.vrHandoffActive = true
        setFlatPresentation(false)
        try {
            context.startActivity(
                Intent(context, VrGameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            mainHandler.removeCallbacks(activityStartWatchdog)
            mainHandler.postDelayed(activityStartWatchdog, ACTIVITY_START_TIMEOUT_MS)
            Timber.i("VR launch: headset activity requested")
        } catch (e: Exception) {
            Timber.e(e, "VR launch: could not start the headset activity")
            onVrActivityClosed()
            SnackbarManager.show(context.getString(R.string.vr_game_start_failed))
        }
    }

    val isVrActive: Boolean get() = activity != null

    internal fun onVrActivityCreated(vrActivity: VrGameActivity) {
        activity = vrActivity
        mainHandler.removeCallbacks(activityStartWatchdog)
    }

    internal fun onVrActivityDestroyed() {
        val playerQuit = !closingOnPurpose && PluviaApp.xEnvironment != null
        closingOnPurpose = false
        onVrActivityClosed()
        if (playerQuit) {
            // Same exit path as the quick menu's Exit.
            Timber.i("VR launch: headset activity closed by the player, exiting the game")
            PluviaApp.events.emit(SteamEvent.ForceCloseApp)
        }
    }

    internal fun onVrActivityClosed() {
        mainHandler.removeCallbacks(activityStartWatchdog)
        mainHandler.removeCallbacks(leaveVrAfterSessionEnd)
        activity = null
        PluviaApp.vrHandoffActive = false
        setFlatPresentation(true)
    }

    fun close() {
        mainHandler.removeCallbacks(activityStartWatchdog)
        mainHandler.removeCallbacks(leaveVrAfterSessionEnd)
        activity?.let { vrActivity ->
            closingOnPurpose = true
            vrActivity.runOnUiThread { vrActivity.finish() }
        }
        runtime?.let { service ->
            service.sessionListener = null
            runCatching { service.close() }.onFailure { Timber.w(it, "VR launch: runtime close failed") }
        }
        runtime = null
        appContext = null
        PluviaApp.vrHandoffActive = false
    }

    // Like the immersive activity: don't spend GPU on the flat window while in VR.
    private fun setFlatPresentation(enabled: Boolean) {
        val view = PluviaApp.xServerView ?: return
        view.getxServer()?.setFlatPresentationEnabled(enabled)
        (view.renderer as? GLRenderer)?.setFlatPresentationEnabled(enabled)
        (view.renderer as? VulkanRenderer)?.setFlatPresentationEnabled(enabled)
    }
}
