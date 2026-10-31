package app.gamenative.ui.screen.xserver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.ShooterModeConfig
import app.gamenative.externaldisplay.SwapInputOverlayView
import app.gamenative.ui.component.parseBooleanExtra
import app.gamenative.ui.component.parsePositiveFpsLimit
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.widget.PerformanceHudView
import com.winlator.container.Container
import com.winlator.core.Win32AppWorkarounds
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.FrameRating
import com.winlator.widget.XServerRendererView
import com.winlator.winhandler.ProcessInfo
import com.winlator.xserver.Keyboard
import com.winlator.xserver.WindowManager
import kotlinx.coroutines.Job

private const val INPUT_THROTTLING_ENABLED_EXTRA = "inputThrottlingEnabled"
private const val INPUT_THROTTLING_RATE_EXTRA = "inputThrottlingRateHz"

private fun initialInputThrottlingEnabled(container: Container): Boolean =
    parseBooleanExtra(container.getExtra(INPUT_THROTTLING_ENABLED_EXTRA)) ?: false

private fun initialInputThrottlingRateHz(container: Container): Int =
    parsePositiveFpsLimit(container.getExtra(INPUT_THROTTLING_RATE_EXTRA))
        ?.coerceIn(InputThrottling.MIN_RATE_HZ, InputThrottling.MAX_RATE_HZ)
        ?: InputThrottling.DEFAULT_RATE_HZ

/**
 * Mutable session state of [XServerScreen], held in one remembered object.
 *
 * XServerScreen is one ~2900-line composable. Every top-level `remember { mutableStateOf() }` local in it costs a
 * dex register for the whole method, and D8 (debug builds) miscompiles the method once it needs more than ~255
 * registers: ART then rejects the class at load with a VerifyError and every game launch crashes. Keeping the state
 * here means the screen holds a single register for all of it. New per-session UI state belongs in this class, not
 * as a new local in XServerScreen.
 */
internal class XServerScreenState(container: Container, context: Context) {
    var frameRating by mutableStateOf<FrameRating?>(null)
    var touchMouse by mutableStateOf<TouchMouse?>(null)
    var keyboard by mutableStateOf<Keyboard?>(null)
    var xServerView: XServerRendererView? by mutableStateOf<XServerRendererView?>(null)
    var swapInputOverlay: SwapInputOverlayView? by mutableStateOf(null)
    var imeInputReceiver: app.gamenative.externaldisplay.IMEInputReceiver? by mutableStateOf(null)
    var win32AppWorkarounds: Win32AppWorkarounds? by mutableStateOf(null)
    var physicalControllerHandler: PhysicalControllerHandler? by mutableStateOf(null)
    var exitWatchJob: Job? by mutableStateOf(null)
    var areControlsVisible by mutableStateOf(false)
    var isDisableMouseInput by mutableStateOf(container.isDisableMouseInput)
    var isEditMode by mutableStateOf(false)
    var gameRoot by mutableStateOf<View?>(null)
    var windowModificationListener by mutableStateOf<WindowManager.OnWindowModificationListener?>(null)
    var elementPositionsSnapshot by mutableStateOf<Map<com.winlator.inputcontrols.ControlElement, Pair<Int, Int>>>(emptyMap())
    var showElementEditor by mutableStateOf(false)
    var elementToEdit by mutableStateOf<com.winlator.inputcontrols.ControlElement?>(null)
    var physicalControllerDialogMode by mutableIntStateOf(PHYSICAL_CONTROLLER_DIALOG_NONE)
    var showTouchGestureDialog by mutableStateOf(false)
    var showShooterModeDialog by mutableStateOf(false)
    var isTouchscreenModeActive by mutableStateOf(container.isTouchscreenMode)
    var isShooterModeActive by mutableStateOf(container.isShooterMode)
    var currentGestureConfig by mutableStateOf(app.gamenative.data.TouchGestureConfig.fromJson(container.getGestureConfig()))
    var currentShooterConfig by mutableStateOf(ShooterModeConfig.fromJson(container.getShooterConfig()))
    var debugGestureName by mutableStateOf("")
    var debugGestureKey by mutableIntStateOf(0)
    var keyboardRequestedFromOverlay by mutableStateOf(false)
    var shouldForceResumeOnMenuClose by mutableStateOf(false)
    var showQuickMenu by mutableStateOf(false)
    var quickMenuToolsVisible by mutableStateOf(false)
    var quickMenuWineProcesses by mutableStateOf<List<ProcessInfo>>(emptyList())
    var quickMenuWineProcessesLoading by mutableStateOf(false)
    var hasPhysicalController by mutableStateOf(false)
    var controllerSlotStatusVersion by mutableIntStateOf(0)
    var keepPausedForEditor by mutableStateOf(false)
    var hasPhysicalKeyboard by mutableStateOf(false)
    var hasPhysicalMouse by mutableStateOf(false)
    var usingScreenMirror by mutableStateOf(false)
    var hasInternalTouchpad by mutableStateOf(false)
    var hasUpdatedScreenGamepad by mutableStateOf(false)
    var isPerformanceHudEnabled by mutableStateOf(PrefManager.showFps)
    var detectedMaxRefreshRateHz by mutableIntStateOf(detectRefreshRateHz(context, null))
    var performanceHudConfig by mutableStateOf(loadPerformanceHudConfig())
    var performanceHudView by mutableStateOf<PerformanceHudView?>(null)
    var performanceHudHost by mutableStateOf<FrameLayout?>(null)
    var isDraggingPerformanceHud by mutableStateOf(false)
    var isTrackingPerformanceHudTouch by mutableStateOf(false)
    var performanceHudTouchDownRawX by mutableStateOf(0f)
    var performanceHudTouchDownRawY by mutableStateOf(0f)
    var performanceHudDragOffsetX by mutableStateOf(0f)
    var performanceHudDragOffsetY by mutableStateOf(0f)

    // The "Input throttling" setting, stored in the container, and the session's instance of it, shared by the
    // on-screen controls and physical controllers.
    var inputThrottlingEnabled by mutableStateOf(initialInputThrottlingEnabled(container))
        private set
    var inputThrottlingRateHz by mutableIntStateOf(initialInputThrottlingRateHz(container))
        private set
    val inputThrottling = InputThrottling().apply {
        enabled = inputThrottlingEnabled
        setRateHz(inputThrottlingRateHz)
    }

    // tryCapturePointer() runs on every gamepad event while capture is off: at most one request in flight.
    // Posted on a main-thread Handler, not the view, so a detached view can't leave the flag set. Main thread only.
    private val pointerCaptureHandler = Handler(Looper.getMainLooper())
    private var pointerCaptureRequestPending = false

    fun applyInputThrottlingEnabled(container: Container, enabled: Boolean) {
        inputThrottlingEnabled = enabled
        inputThrottling.enabled = enabled
        persistInputThrottling(container)
    }

    fun applyInputThrottlingRateHz(container: Container, hz: Int) {
        val sanitized = hz.coerceIn(InputThrottling.MIN_RATE_HZ, InputThrottling.MAX_RATE_HZ)
        inputThrottlingRateHz = sanitized
        inputThrottling.setRateHz(sanitized)
        persistInputThrottling(container)
    }

    private fun persistInputThrottling(container: Container) {
        container.putExtra(INPUT_THROTTLING_ENABLED_EXTRA, inputThrottlingEnabled)
        container.putExtra(INPUT_THROTTLING_RATE_EXTRA, inputThrottlingRateHz)
        container.saveData()
    }

    /** Requests pointer capture for the touchpad shortly, unless a menu or editor is open; false if it can't. */
    fun tryCapturePointer(container: Container): Boolean {
        if (!canCapturePointer(container)) return false
        if (!pointerCaptureRequestPending) {
            pointerCaptureRequestPending = true
            pointerCaptureHandler.postDelayed({
                pointerCaptureRequestPending = false
                // Checked again: a menu or editor may have opened meanwhile.
                val view = PluviaApp.touchpadView
                if (view != null && canCapturePointer(container)) {
                    view.requestFocus()
                    view.requestPointerCapture()
                }
            }, 100)
        }
        return true
    }

    /** A request still pending must not capture the touchpad of a later session. */
    fun cancelPointerCaptureRequest() {
        pointerCaptureHandler.removeCallbacksAndMessages(null)
        pointerCaptureRequestPending = false
    }

    private fun canCapturePointer(container: Container): Boolean =
        !showElementEditor && !keepPausedForEditor && !showQuickMenu && !isEditMode && !container.isTouchscreenMode
}

internal fun loadPerformanceHudConfig(): PerformanceHudConfig {
    return PerformanceHudConfig(
        showFrameRate = PrefManager.performanceHudShowFrameRate,
        showCpuUsage = PrefManager.performanceHudShowCpuUsage,
        showGpuUsage = PrefManager.performanceHudShowGpuUsage,
        showRamUsage = PrefManager.performanceHudShowRamUsage,
        showBatteryLevel = PrefManager.performanceHudShowBatteryLevel,
        showPowerDraw = PrefManager.performanceHudShowPowerDraw,
        showBatteryRuntime = PrefManager.performanceHudShowBatteryRuntime,
        showBatteryTemperature = PrefManager.performanceHudShowBatteryTemperature,
        showClockTime = PrefManager.performanceHudShowClockTime,
        showCpuTemperature = PrefManager.performanceHudShowCpuTemperature,
        showGpuTemperature = PrefManager.performanceHudShowGpuTemperature,
        showFrameRateGraph = PrefManager.performanceHudShowFrameRateGraph,
        showCpuUsageGraph = PrefManager.performanceHudShowCpuUsageGraph,
        showGpuUsageGraph = PrefManager.performanceHudShowGpuUsageGraph,
        backgroundOpacity = PrefManager.performanceHudBackgroundOpacity,
        colorIntensity = PrefManager.performanceHudColorIntensity,
        showTextOutline = PrefManager.performanceHudShowTextOutline,
        size = PerformanceHudSize.fromPrefValue(PrefManager.performanceHudSize),
    )
}

internal fun persistPerformanceHudConfig(config: PerformanceHudConfig) {
    PrefManager.performanceHudShowFrameRate = config.showFrameRate
    PrefManager.performanceHudShowCpuUsage = config.showCpuUsage
    PrefManager.performanceHudShowGpuUsage = config.showGpuUsage
    PrefManager.performanceHudShowRamUsage = config.showRamUsage
    PrefManager.performanceHudShowBatteryLevel = config.showBatteryLevel
    PrefManager.performanceHudShowPowerDraw = config.showPowerDraw
    PrefManager.performanceHudShowBatteryRuntime = config.showBatteryRuntime
    PrefManager.performanceHudShowBatteryTemperature = config.showBatteryTemperature
    PrefManager.performanceHudShowClockTime = config.showClockTime
    PrefManager.performanceHudShowCpuTemperature = config.showCpuTemperature
    PrefManager.performanceHudShowGpuTemperature = config.showGpuTemperature
    PrefManager.performanceHudShowFrameRateGraph = config.showFrameRateGraph
    PrefManager.performanceHudShowCpuUsageGraph = config.showCpuUsageGraph
    PrefManager.performanceHudShowGpuUsageGraph = config.showGpuUsageGraph
    PrefManager.performanceHudBackgroundOpacity = config.backgroundOpacity
    PrefManager.performanceHudColorIntensity = config.colorIntensity
    PrefManager.performanceHudShowTextOutline = config.showTextOutline
    PrefManager.performanceHudSize = config.size.prefValue
}
