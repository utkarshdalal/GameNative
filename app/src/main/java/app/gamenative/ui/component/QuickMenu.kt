package app.gamenative.ui.component

import android.view.KeyEvent
import timber.log.Timber
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.powercontrol.PowerManager
import app.gamenative.ui.component.quickMenus.PowerControlQuickMenuTab
import app.gamenative.ui.data.PerformanceHudConfig
import app.gamenative.ui.data.PerformanceHudSize
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.util.adaptivePanelWidth
import app.gamenative.utils.MathUtils.normalizedProgress
import com.winlator.container.Container
import com.winlator.renderer.GLRenderer
import com.winlator.renderer.VulkanRenderer
import com.winlator.winhandler.ProcessInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

object QuickMenuAction {
    const val KEYBOARD = 1
    const val INPUT_CONTROLS = 2
    const val EXIT_GAME = 3
    const val EDIT_CONTROLS = 4
    const val EDIT_PHYSICAL_CONTROLLER = 5
    const val PERFORMANCE_HUD = 6
    const val TOUCHSCREEN_MODE = 7
    const val DISABLE_MOUSE = 8
    const val SHOOTER_MODE = 9
    const val RADIAL_MENU = 10
}

private object QuickMenuTab {
    const val HUD = 0
    const val LSFG = 1
    const val EFFECTS = 2
    const val CONTROLLER = 3
    const val TOOLS = 4
    const val IMMERSIVE = 5
    const val INVITE = 6
    const val POWER = 7
}

data class QuickMenuItem(
    val id: Int,
    val icon: ImageVector,
    val labelResId: Int,
    val accentColor: Color = Color.Unspecified,
    val enabled: Boolean = true,
)

private enum class PerformanceHudPreset(val labelResId: Int) {
    FPS_ONLY(R.string.performance_hud_preset_fps_only),
    ESSENTIAL(R.string.performance_hud_preset_essential),
    BATTERY(R.string.performance_hud_preset_battery),
    FULL(R.string.performance_hud_preset_full),
}

private fun applyPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): PerformanceHudConfig {
    return when (preset) {
        PerformanceHudPreset.FPS_ONLY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = false,
            showGpuUsage = false,
            showRamUsage = false,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.ESSENTIAL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = false,
            showPowerDraw = false,
            showBatteryRuntime = false,
            showBatteryTemperature = false,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = false,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.BATTERY -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = false,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = false,
            showCpuTemperature = false,
            showGpuTemperature = false,
            showFrameRateGraph = true,
            showCpuUsageGraph = false,
            showGpuUsageGraph = false,
        )

        PerformanceHudPreset.FULL -> currentConfig.copy(
            showFrameRate = true,
            showCpuUsage = true,
            showGpuUsage = true,
            showRamUsage = true,
            showBatteryLevel = true,
            showPowerDraw = true,
            showBatteryRuntime = true,
            showBatteryTemperature = true,
            showClockTime = true,
            showCpuTemperature = true,
            showGpuTemperature = true,
            showFrameRateGraph = true,
            showCpuUsageGraph = true,
            showGpuUsageGraph = true,
        )
    }
}

private fun matchesPerformanceHudPreset(
    currentConfig: PerformanceHudConfig,
    preset: PerformanceHudPreset,
): Boolean {
    val presetConfig = applyPerformanceHudPreset(currentConfig, preset)
    return currentConfig.showFrameRate == presetConfig.showFrameRate &&
        currentConfig.showCpuUsage == presetConfig.showCpuUsage &&
        currentConfig.showGpuUsage == presetConfig.showGpuUsage &&
        currentConfig.showRamUsage == presetConfig.showRamUsage &&
        currentConfig.showBatteryLevel == presetConfig.showBatteryLevel &&
        currentConfig.showPowerDraw == presetConfig.showPowerDraw &&
        currentConfig.showBatteryRuntime == presetConfig.showBatteryRuntime &&
        currentConfig.showBatteryTemperature == presetConfig.showBatteryTemperature &&
        currentConfig.showClockTime == presetConfig.showClockTime &&
        currentConfig.showCpuTemperature == presetConfig.showCpuTemperature &&
        currentConfig.showGpuTemperature == presetConfig.showGpuTemperature &&
        currentConfig.showFrameRateGraph == presetConfig.showFrameRateGraph &&
        currentConfig.showCpuUsageGraph == presetConfig.showCpuUsageGraph &&
        currentConfig.showGpuUsageGraph == presetConfig.showGpuUsageGraph
}

// fpsLimiterSteps / fpsLimiterCurrentIndex / fpsLimiterProgress /
// nextFpsLimiterValue / previousFpsLimiterValue live in FpsLimiterUtils.kt

/**
 * Lets ANY quick-menu row — no matter how deeply nested, in this file or any other — report its
 * own gamepad interaction to the Meta Quest immersive activity, without threading a dedicated
 * parameter through every intermediate composable in between (the previous approach: adding a
 * new callback param to QuickMenuAdjustmentRow, then to its tab wrapper, then to QuickMenu itself,
 * then to every call site — for every single row that needed it. A new row buried in some future
 * tab would need the SAME threading redone by hand, easy to forget).
 *
 * Exists because normal Android/Compose gamepad input (real hardware, real KeyEvents) already
 * works correctly for every existing row with zero extra code — this is ONLY needed because the
 * immersive activity's Quest-Touch-controller input is synthetic and confirmed, repeatedly this
 * session, to never reach Compose's own key dispatch or default click-on-focused-view behavior in
 * that Activity. [QuickMenu] provides the real implementation (backed by state it forwards to
 * ImmersiveXrActivity via registerAdjustmentControl/registerFocusedActivate); everywhere else —
 * i.e. normal 2D panel mode — gets the no-op default below, so a row that reports itself here
 * costs nothing and needs no `if (immersive)` branching of its own.
 */
class ImmersiveInputBypass(
    val active: Boolean = false,
    private val applyAdjustment: (Pair<() -> Unit, () -> Unit>?) -> Unit = {},
    private val applyActivate: ((() -> Unit)?) -> Unit = {},
) {
    private var adjustmentOwner: Any? = null
    private var activateOwner: Any? = null

    // `owner` must be stable per row, and only the current owner may clear a slot — focus-change
    // effects from two rows can run in either order.
    // Reports (onDecrease, onIncrease) while a row is both focused and lock-toggled (A to lock,
    // DPAD_LEFT/RIGHT to adjust, B or losing focus to unlock) — for slider-style rows.
    fun reportAdjustment(owner: Any, actions: Pair<() -> Unit, () -> Unit>?) {
        if (actions != null) {
            adjustmentOwner = owner
            applyAdjustment(actions)
        } else if (adjustmentOwner === owner) {
            adjustmentOwner = null
            applyAdjustment(null)
        }
    }

    // Reports a row's own click/select action while it's focused — for plain radio/toggle rows
    // that only need a single BUTTON_A/DPAD_CENTER press to activate.
    fun reportActivate(owner: Any, action: (() -> Unit)?) {
        if (action != null) {
            activateOwner = owner
            applyActivate(action)
        } else if (activateOwner === owner) {
            activateOwner = null
            applyActivate(null)
        }
    }
}
val LocalImmersiveInputBypass = staticCompositionLocalOf { ImmersiveInputBypass() }

/** Performance HUD + FPS limiter state/callbacks as one QuickMenu parameter instead of eight —
 * XServerScreen's call site sits at the dex verifier's 255-register limit, and every argument
 * of that call costs a register there (a runtime VerifyError from exactly that was reproduced
 * on device). */
class PerformanceQuickMenuState(
    val hudEnabled: Boolean = false,
    val hudConfig: PerformanceHudConfig = PerformanceHudConfig(),
    val fpsLimiterEnabled: Boolean = true,
    val fpsLimiterTarget: Int = 60,
    val fpsLimiterMax: Int = 60,
    val onHudConfigChanged: (PerformanceHudConfig) -> Unit = {},
    val onFpsLimiterEnabledChanged: (Boolean) -> Unit = {},
    val onFpsLimiterChanged: (Int) -> Unit = {},
)

/** LSFG hot-reload state/callbacks as one QuickMenu parameter instead of seven — same
 * register-limit reason as [PerformanceQuickMenuState]. Tab only visible when [isAvailable]. */
class LsfgQuickMenuState(
    val isAvailable: Boolean = false,
    val multiplier: Int = 2,
    val flowScale: Float = 0.80f,
    val performanceMode: Boolean = true,
    val onMultiplierChanged: (Int) -> Unit = {},
    val onFlowScaleChanged: (Float) -> Unit = {},
    val onPerformanceModeChanged: (Boolean) -> Unit = {},
)

@Composable
fun QuickMenu(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    onItemSelected: (Int) -> Boolean,
    renderer: VulkanRenderer? = null,
    glRenderer: GLRenderer? = null,
    container: Container? = null,
    wineProcesses: List<ProcessInfo> = emptyList(),
    isWineProcessesLoading: Boolean = false,
    onToolsVisibilityChanged: (Boolean) -> Unit = {},
    onEndWineProcess: (ProcessInfo) -> Unit = {},
    performance: PerformanceQuickMenuState = PerformanceQuickMenuState(),
    hasPhysicalController: Boolean = false,
    isTouchscreenModeActive: Boolean = false,
    onTouchGestureSettingsClick: () -> Unit = {},
    isShooterModeActive: Boolean = false,
    onShooterModeSettingsClick: () -> Unit = {},
    activeToggleIds: Set<Int> = emptySet(),
    lsfg: LsfgQuickMenuState = LsfgQuickMenuState(),
    onAnimationComplete: (Boolean) -> Unit = {},
    /** Lets the menu open itself when the running game asks for its Steam invite dialog. */
    onRequestOpen: () -> Unit = {},
    immersiveHooks: app.gamenative.ui.screen.xr.ImmersiveSessionHooks? = null,
    modifier: Modifier = Modifier,
) {
    val immersiveControls = immersiveHooks?.controls
    val isPerformanceHudEnabled = performance.hudEnabled
    val performanceHudConfig = performance.hudConfig
    val fpsLimiterEnabled = performance.fpsLimiterEnabled
    val fpsLimiterTarget = performance.fpsLimiterTarget
    val fpsLimiterMax = performance.fpsLimiterMax
    val onPerformanceHudConfigChanged = performance.onHudConfigChanged
    val onFpsLimiterEnabledChanged = performance.onFpsLimiterEnabledChanged
    val onFpsLimiterChanged = performance.onFpsLimiterChanged
    val isLsfgAvailable = lsfg.isAvailable
    val lsfgMultiplier = lsfg.multiplier
    val lsfgFlowScale = lsfg.flowScale
    val lsfgPerformanceMode = lsfg.performanceMode
    val onLsfgMultiplierChanged = lsfg.onMultiplierChanged
    val onLsfgFlowScaleChanged = lsfg.onFlowScaleChanged
    val onLsfgPerformanceModeChanged = lsfg.onPerformanceModeChanged
    val focusManager = LocalFocusManager.current
    LaunchedEffect(immersiveHooks) {
        immersiveHooks?.registerFocusManager?.invoke(focusManager)
    }
    val focusTabRailScope = rememberCoroutineScope()
    var activeAdjustment by remember { mutableStateOf<Pair<() -> Unit, () -> Unit>?>(null) }
    var activeRadioActivate by remember { mutableStateOf<(() -> Unit)?>(null) }
    LaunchedEffect(Unit) {
        immersiveHooks?.registerFocusedActivate?.invoke { activeRadioActivate }
    }
    var startHeld by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        immersiveHooks?.registerStartHeld?.invoke { startHeld = it }
    }
    LaunchedEffect(Unit) {
        immersiveHooks?.registerAdjustmentControl?.invoke { activeAdjustment }
    }
    val exitGameItem = QuickMenuItem(
        id = QuickMenuAction.EXIT_GAME,
        icon = Icons.AutoMirrored.Filled.ExitToApp,
        labelResId = R.string.exit_game,
        accentColor = PluviaTheme.colors.accentDanger,
    )

    val controllerItems = buildList {
        add(
            QuickMenuItem(
                id = QuickMenuAction.DISABLE_MOUSE,
                icon = Icons.Filled.Mouse,
                labelResId = R.string.disable_mouse_input,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.KEYBOARD,
                icon = Icons.Default.Keyboard,
                labelResId = R.string.keyboard,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.INPUT_CONTROLS,
                icon = Icons.Default.TouchApp,
                labelResId = R.string.input_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        if (hasPhysicalController) {
            add(
                QuickMenuItem(
                    id = QuickMenuAction.EDIT_PHYSICAL_CONTROLLER,
                    icon = Icons.Default.Gamepad,
                    labelResId = R.string.edit_physical_controller,
                    accentColor = PluviaTheme.colors.accentPurple,
                )
            )
        }
        add(
            QuickMenuItem(
                id = QuickMenuAction.EDIT_CONTROLS,
                icon = Icons.Default.Edit,
                labelResId = R.string.edit_controls,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.TOUCHSCREEN_MODE,
                icon = Icons.Default.Fingerprint,
                labelResId = R.string.touchscreen_mode,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.SHOOTER_MODE,
                icon = Icons.Default.Gamepad,
                labelResId = R.string.shooter_mode_toggle,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
        add(
            QuickMenuItem(
                id = QuickMenuAction.RADIAL_MENU,
                icon = Icons.Default.Settings,
                labelResId = R.string.radial_menu,
                accentColor = PluviaTheme.colors.accentPurple,
            )
        )
    }

    // Created here rather than plumbed through XServerScreen: that composable
    // sits at the dex verifier's register limit and any extra locals there
    // trip a VerifyError at class load (dex methods over 255 registers hit a
    // broken D8 codegen path).
    val inviteMenu = remember(container?.id) { SteamInviteState.createIfAvailable(container) }
    // Owned here, not plumbed through XServerScreen (register limit; see inviteMenu).
    var lsfgPresentMode by remember(container?.id) {
        mutableStateOf(container?.let { app.gamenative.utils.LsfgQuickMenuHelper.presentMode(it) } ?: "mailbox")
    }

    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            when {
                PrefManager.quickMenuLastTab == QuickMenuTab.LSFG && !isLsfgAvailable -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.INVITE && inviteMenu == null -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.POWER -> QuickMenuTab.HUD
                PrefManager.quickMenuLastTab == QuickMenuTab.IMMERSIVE && immersiveControls == null -> QuickMenuTab.HUD
                else -> PrefManager.quickMenuLastTab
            }
        )
    }
    val selectedTabLabelResId = when (selectedTab) {
        QuickMenuTab.HUD -> R.string.performance_hud
        QuickMenuTab.LSFG -> R.string.lsfg_tab_title
        QuickMenuTab.EFFECTS -> R.string.screen_effects
        QuickMenuTab.TOOLS -> R.string.task_manager
        QuickMenuTab.INVITE -> R.string.steam_invite_tab_title
        QuickMenuTab.POWER -> R.string.power_control
        QuickMenuTab.IMMERSIVE -> R.string.quick_menu_tab_immersive
        else -> R.string.quick_menu_tab_controller
    }

    val hudScrollState = rememberScrollState()
    val effectsScrollState = rememberScrollState()
    val lsfgScrollState = rememberScrollState()
    val effectsTabFocusRequester = remember { FocusRequester() }
    val controllerScrollState = rememberScrollState()
    val lsfgTabFocusRequester = remember { FocusRequester() }
    val hudTabFocusRequester = remember { FocusRequester() }
    val controllerTabFocusRequester = remember { FocusRequester() }
    val toolsTabFocusRequester = remember { FocusRequester() }
    val powerTabFocusRequester = remember { FocusRequester() }
    val hudItemFocusRequester = remember { FocusRequester() }
    val effectsItemFocusRequester = remember { FocusRequester() }
    val controllerItemFocusRequester = remember { FocusRequester() }
    val toolsItemFocusRequester = remember { FocusRequester() }
    val lsfgItemFocusRequester = remember { FocusRequester() }
    val inviteTabFocusRequester = remember { FocusRequester() }
    val inviteItemFocusRequester = remember { FocusRequester() }

    // The game's own "Invite friends" button reaches us as an engine callback the bionic host
    // captures. Open on the invite tab rather than drawing a separate panel, so controller focus
    // and back-to-dismiss behave like the rest of the menu.
    if (inviteMenu != null) {
        LaunchedEffect(inviteMenu) {
            while (true) {
                if (!isVisible && inviteMenu.consumeGameInviteRequest()) {
                    selectedTab = QuickMenuTab.INVITE
                    PrefManager.quickMenuLastTab = selectedTab
                    SteamInviteState.openedForGameRequest = true
                    onRequestOpen()
                }
                delay(1000)
            }
        }
    }
    val powerItemFocusRequester = remember { FocusRequester() }
    val immersiveScrollState = rememberScrollState()
    val immersiveTabFocusRequester = remember { FocusRequester() }
    val immersiveItemFocusRequester = remember { FocusRequester() }

    val visibleState = remember { MutableTransitionState(false) }
    visibleState.targetState = isVisible

    LaunchedEffect(visibleState.currentState, visibleState.isIdle) {
        if (visibleState.isIdle) {
            onAnimationComplete(visibleState.currentState)
        }
    }

    BackHandler(enabled = isVisible) {
        onDismiss()
    }

    // Only the tabs actually shown in the rail, in on-screen order — mirrors the conditions each
    // QuickMenuTabButton below is gated on (isLsfgAvailable, a renderer being available, etc).
    val availableTabs = remember(isLsfgAvailable, renderer, glRenderer, immersiveControls, inviteMenu)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           {
        buildList {
            add(QuickMenuTab.HUD)
            add(QuickMenuTab.POWER)
            if (isLsfgAvailable) add(QuickMenuTab.LSFG)
            if (inviteMenu != null) add(QuickMenuTab.INVITE)
            if (renderer != null || glRenderer != null) add(QuickMenuTab.EFFECTS)
            add(QuickMenuTab.CONTROLLER)
            add(QuickMenuTab.TOOLS)
            if (immersiveControls != null) add(QuickMenuTab.IMMERSIVE)
        }
    }

    LaunchedEffect(Unit) {
        immersiveHooks?.registerFocusTabRail?.invoke {
            val requester = when (selectedTab) {
                QuickMenuTab.HUD -> hudTabFocusRequester
                QuickMenuTab.LSFG -> lsfgTabFocusRequester
                QuickMenuTab.EFFECTS -> effectsTabFocusRequester
                QuickMenuTab.CONTROLLER -> controllerTabFocusRequester
                QuickMenuTab.TOOLS -> toolsTabFocusRequester
                QuickMenuTab.IMMERSIVE -> immersiveTabFocusRequester
                else -> null
            }
            if (requester == null) return@invoke
            focusTabRailScope.launch {
                repeat(3) { attempt ->
                    try {
                        requester.requestFocus()
                        Timber.i("QuickMenu: requestFocusTabRail succeeded for tab=%s on attempt=%d", selectedTab, attempt)
                        return@launch
                    } catch (e: IllegalStateException) {
                        Timber.w(e, "QuickMenu: requestFocusTabRail failed for tab=%s on attempt=%d", selectedTab, attempt)
                        delay(80)
                    }
                }
                Timber.w("QuickMenu: requestFocusTabRail never succeeded for tab=%s after 3 attempts", selectedTab)
            }
        }
    }

    LaunchedEffect(availableTabs) {
        immersiveHooks?.registerCycleTab?.invoke { forward ->
            val currentIndex = availableTabs.indexOf(selectedTab).takeIf { it >= 0 } ?: 0
            val nextTab = if (forward) {
                availableTabs[(currentIndex + 1) % availableTabs.size]
            } else {
                availableTabs[(currentIndex - 1 + availableTabs.size) % availableTabs.size]
            }
            selectedTab = nextTab
            PrefManager.quickMenuLastTab = nextTab
        }
    }

    CompositionLocalProvider(
        LocalImmersiveInputBypass provides remember(immersiveControls != null) {
            ImmersiveInputBypass(
                active = immersiveControls != null,
                applyAdjustment = { activeAdjustment = it },
                applyActivate = { activeRadioActivate = it },
            )
        },
    ) {
    Box(
        modifier = modifier.fillMaxSize()
            .onPreviewKeyEvent { keyEvent ->
                if (isVisible && startHeld) {
                    return@onPreviewKeyEvent true
                }
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                Timber.i("QuickMenu: onPreviewKeyEvent keyCode=%d selectedTab=%d", keyEvent.nativeKeyEvent.keyCode, selectedTab)
                val currentIndex = availableTabs.indexOf(selectedTab).takeIf { it >= 0 } ?: 0
                val nextTab = if (immersiveHooks == null) null else when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BUTTON_L1 -> availableTabs[(currentIndex - 1 + availableTabs.size) % availableTabs.size]
                    KeyEvent.KEYCODE_BUTTON_R1 -> availableTabs[(currentIndex + 1) % availableTabs.size]
                    else -> null
                }
                if (nextTab != null) {
                    selectedTab = nextTab
                    PrefManager.quickMenuLastTab = nextTab
                    true
                } else {
                    false
                }
            },
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(150)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0f))
                    .then(
                        // Immersive only: .clickable() adds a screen-sized focus target that
                        // Compose's directional focus search can land on, silently closing the
                        // menu on the next DPAD_CENTER. Flat mode keeps master's clickable.
                        if (immersiveHooks != null) {
                            Modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }
                        } else {
                            Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDismiss,
                            )
                        },
                    ),
            )
        }

        AnimatedVisibility(
            visibleState = visibleState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(200),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth },
                animationSpec = tween(150),
            ),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            val panelWidth = if (selectedTab == QuickMenuTab.POWER) {
                adaptivePanelWidth(800.dp, 0.95f)
            } else {
                adaptivePanelWidth(400.dp)
            }

            Surface(
                modifier = Modifier
                    .width(panelWidth)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.quick_menu_title),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        QuickMenuCloseButton(onClick = onDismiss)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(64.dp)
                                .fillMaxHeight()
                                .focusGroup(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val tabScrollState = rememberScrollState()
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(tabScrollState),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                QuickMenuTabButton(
                                    icon = Icons.Default.QueryStats,
                                    contentDescriptionResId = R.string.performance_hud,
                                    selected = selectedTab == QuickMenuTab.HUD,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.HUD
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = hudTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.BatteryChargingFull,
                                    contentDescriptionResId = R.string.power_control,
                                    selected = selectedTab == QuickMenuTab.POWER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.POWER
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = powerTabFocusRequester,
                                )
                                if (isLsfgAvailable) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.Speed,
                                        contentDescriptionResId = R.string.lsfg_tab_title,
                                        selected = selectedTab == QuickMenuTab.LSFG,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.LSFG
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = lsfgTabFocusRequester,
                                    )
                                }
                                if (inviteMenu != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.PersonAdd,
                                        contentDescriptionResId = R.string.steam_invite_tab_title,
                                        selected = selectedTab == QuickMenuTab.INVITE,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.INVITE
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = inviteTabFocusRequester,
                                    )
                                }
                                if (renderer != null || glRenderer != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.AutoFixHigh,
                                        contentDescriptionResId = R.string.screen_effects,
                                        selected = selectedTab == QuickMenuTab.EFFECTS,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.EFFECTS
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = effectsTabFocusRequester,
                                    )
                                }
                                QuickMenuTabButton(
                                    icon = Icons.Default.Gamepad,
                                    contentDescriptionResId = R.string.quick_menu_tab_controller,
                                    selected = selectedTab == QuickMenuTab.CONTROLLER,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = {
                                        selectedTab = QuickMenuTab.CONTROLLER
                                        PrefManager.quickMenuLastTab = selectedTab
                                    },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = controllerTabFocusRequester,
                                )
                                QuickMenuTabButton(
                                    icon = Icons.Default.BarChart,
                                    contentDescriptionResId = R.string.task_manager,
                                    selected = selectedTab == QuickMenuTab.TOOLS,
                                    accentColor = PluviaTheme.colors.accentPurple,
                                    onSelected = { selectedTab = QuickMenuTab.TOOLS },
                                    modifier = Modifier.width(56.dp),
                                    focusRequester = toolsTabFocusRequester,
                                )
                                if (immersiveControls != null) {
                                    QuickMenuTabButton(
                                        icon = Icons.Default.ViewInAr,
                                        contentDescriptionResId = R.string.quick_menu_tab_immersive,
                                        selected = selectedTab == QuickMenuTab.IMMERSIVE,
                                        accentColor = PluviaTheme.colors.accentPurple,
                                        onSelected = {
                                            selectedTab = QuickMenuTab.IMMERSIVE
                                            PrefManager.quickMenuLastTab = selectedTab
                                        },
                                        modifier = Modifier.width(56.dp),
                                        focusRequester = immersiveTabFocusRequester,
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 4.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                            )

                            QuickMenuRailActionButton(
                                item = exitGameItem,
                                onClick = {
                                    if (onItemSelected(QuickMenuAction.EXIT_GAME)) {
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.width(56.dp),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxSize(),
                        ) {
                            Text(
                                text = stringResource(selectedTabLabelResId),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                            )

                            Box(
                                modifier = Modifier.weight(1f),
                            ) {
                                when (selectedTab) {
                                    QuickMenuTab.HUD -> {
                                        PerformanceHudQuickMenuTab(
                                            isPerformanceHudEnabled = isPerformanceHudEnabled,
                                            performanceHudConfig = performanceHudConfig,
                                            fpsLimiterEnabled = fpsLimiterEnabled,
                                            fpsLimiterTarget = fpsLimiterTarget,
                                            fpsLimiterMax = fpsLimiterMax,
                                            lsfgMultiplier = if (isLsfgAvailable) lsfgMultiplier else 0,
                                            onTogglePerformanceHud = {
                                                onItemSelected(QuickMenuAction.PERFORMANCE_HUD)
                                            },
                                            onPerformanceHudConfigChanged = onPerformanceHudConfigChanged,
                                            onFpsLimiterEnabledChanged = onFpsLimiterEnabledChanged,
                                            onFpsLimiterChanged = onFpsLimiterChanged,
                                            scrollState = hudScrollState,
                                            focusRequester = hudItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.LSFG -> {
                                        LsfgQuickMenuTab(
                                            multiplier = lsfgMultiplier,
                                            flowScale = lsfgFlowScale,
                                            performanceMode = lsfgPerformanceMode,
                                            onMultiplierChanged = onLsfgMultiplierChanged,
                                            onFlowScaleChanged = onLsfgFlowScaleChanged,
                                            onPerformanceModeChanged = onLsfgPerformanceModeChanged,
                                            presentMode = lsfgPresentMode,
                                            onPresentModeChanged = { mode ->
                                                lsfgPresentMode = mode
                                                container?.let {
                                                    app.gamenative.utils.LsfgQuickMenuHelper.applyPresentMode(it, mode)
                                                }
                                            },
                                            scrollState = lsfgScrollState,
                                            focusRequester = lsfgItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.INVITE -> {
                                        if (inviteMenu != null) {
                                            SteamInviteQuickMenuTab(
                                                state = inviteMenu,
                                                focusRequester = inviteItemFocusRequester,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }

                                    QuickMenuTab.EFFECTS -> {
                                        if (renderer != null) {
                                            ScreenEffectsTabContent(
                                                renderer = renderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else if (glRenderer != null) {
                                            GLScreenEffectsTabContent(
                                                renderer = glRenderer,
                                                container = container,
                                                modifier = Modifier.fillMaxSize(),
                                                firstItemFocusRequester = effectsItemFocusRequester,
                                                scrollState = effectsScrollState,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(horizontal = 8.dp, vertical = 16.dp),
                                                contentAlignment = Alignment.TopStart,
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.main_loading),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }

                                    QuickMenuTab.POWER -> {
                                        PowerControlQuickMenuTab(
                                            focusRequester = powerItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.TOOLS -> {
                                        ToolsQuickMenuTab(
                                            processes = wineProcesses,
                                            isLoadingProcesses = isWineProcessesLoading,
                                            onEndProcess = onEndWineProcess,
                                            firstItemFocusRequester = toolsItemFocusRequester,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }

                                    QuickMenuTab.IMMERSIVE -> {
                                        if (immersiveControls != null) {
                                            ImmersiveQuickMenuTab(
                                                controls = immersiveControls,
                                                scrollState = immersiveScrollState,
                                                focusRequester = immersiveItemFocusRequester,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }

                                    else -> {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .verticalScroll(controllerScrollState)
                                                .focusGroup(),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            controllerItems.forEachIndexed { index, item ->
                                                QuickMenuItemRow(
                                                    item = item,
                                                    isActive = item.id in activeToggleIds,
                                                    onClick = {
                                                        if (onItemSelected(item.id)) {
                                                            onDismiss()
                                                        }
                                                    },
                                                    focusRequester = if (index == 0) controllerItemFocusRequester else null,
                                                    secondaryIcon = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        Icons.Default.Settings
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        Icons.Default.Settings
                                                    else null,
                                                    secondaryContentDescriptionResId = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        R.string.gesture_settings_title
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        R.string.shooter_mode_settings_title
                                                    else null,
                                                    onSecondaryClick = if (item.id == QuickMenuAction.TOUCHSCREEN_MODE && isTouchscreenModeActive)
                                                        onTouchGestureSettingsClick
                                                    else if (item.id == QuickMenuAction.SHOOTER_MODE && isShooterModeActive)
                                                        onShooterModeSettingsClick
                                                    else null,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    LaunchedEffect(isVisible, selectedTab) {
        onToolsVisibilityChanged(isVisible && selectedTab == QuickMenuTab.TOOLS)
    }

    // Immersive also re-requests content focus on every tab switch (its LB/RB cycling moves
    // tabs without ever moving focus); flat mode keeps the original open-only behavior.
    LaunchedEffect(isVisible, if (immersiveControls != null) selectedTab else Unit) {
        if (isVisible) {
            repeat(3) { attempt ->
                try {
                    when (selectedTab) {
                        QuickMenuTab.HUD -> hudItemFocusRequester.requestFocus()
                        QuickMenuTab.LSFG -> lsfgItemFocusRequester.requestFocus()
                        QuickMenuTab.INVITE -> inviteItemFocusRequester.requestFocus()
                        QuickMenuTab.EFFECTS -> effectsItemFocusRequester.requestFocus()
                        QuickMenuTab.POWER -> powerItemFocusRequester.requestFocus()
                        QuickMenuTab.TOOLS -> toolsItemFocusRequester.requestFocus()
                        QuickMenuTab.IMMERSIVE -> immersiveItemFocusRequester.requestFocus()
                        else -> controllerItemFocusRequester.requestFocus()
                    }
                    Timber.i("QuickMenu: requestFocus succeeded for tab=%d on attempt=%d", selectedTab, attempt)
                    return@LaunchedEffect
                } catch (t: Exception) {
                    Timber.w(t, "QuickMenu: requestFocus threw for tab=%d on attempt=%d", selectedTab, attempt)
                    delay(80)
                }
            }
            Timber.w("QuickMenu: requestFocus never succeeded for tab=%d after 3 attempts", selectedTab)
        }
    }
}

@Composable
private fun SteamInviteQuickMenuTab(
    state: SteamInviteState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val accentColor = PluviaTheme.colors.accentPurple
    val scope = rememberCoroutineScope()

    // Keep polling while the tab is up: a friend joining our lobby is the only signal Steam
    // gives us that an invite was acted on, so the rows would otherwise go stale.
    LaunchedEffect(Unit) {
        state.refresh()
        while (true) {
            delay(3000)
            state.refreshQuietly()
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = when {
                state.isLoading -> stringResource(R.string.main_loading)
                state.hostUnavailable -> stringResource(R.string.steam_invite_unavailable)
                else -> stringResource(R.string.steam_invite_header, state.friends.size)
            },
            subtitle = state.lastError?.let { stringResource(it) },
        )

        state.friends.forEachIndexed { index, friend ->
            QuickMenuDetailRow(
                title = friend.name,
                subtitle = stringResource(
                    when {
                        friend.inOurLobby -> R.string.steam_invite_joined
                        friend.isJoinable -> R.string.steam_invite_joinable
                        friend.steamId in state.inviteSent -> R.string.steam_invite_sent
                        friend.isOnline -> R.string.steam_invite_online
                        else -> R.string.steam_invite_offline
                    },
                ),
                accentColor = accentColor,
                onActivate = {
                    scope.launch {
                        // Someone already in this game gets joined, not invited.
                        if (friend.isJoinable) state.join(friend) else state.invite(friend.steamId)
                    }
                },
                focusRequester = if (index == 0) focusRequester else null,
            )
        }
    }
}

@Composable
private fun ToolsQuickMenuTab(
    processes: List<ProcessInfo>,
    isLoadingProcesses: Boolean,
    onEndProcess: (ProcessInfo) -> Unit,
    firstItemFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QuickMenuSectionHeader(
            title = if (isLoadingProcesses) {
                stringResource(R.string.main_loading)
            } else {
                stringResource(R.string.tools_wine_processes_running_hint, processes.size)
            },
        )

        if (!isLoadingProcesses && processes.isEmpty()) {
            Text(
                text = stringResource(R.string.tools_wine_processes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else {
            processes.forEachIndexed { index, process ->
                QuickMenuDetailRow(
                    title = process.name + if (process.wow64Process) " *32" else "",
                    subtitle = process.formattedMemoryUsage,
                    accentColor = accentColor,
                    onActivate = {
                        onEndProcess(process)
                    },
                    focusRequester = if (index == 0) firstItemFocusRequester else null,
                )
            }
        }
    }
}

@Composable
private fun PerformanceHudQuickMenuTab(
    isPerformanceHudEnabled: Boolean,
    performanceHudConfig: PerformanceHudConfig,
    fpsLimiterEnabled: Boolean,
    fpsLimiterTarget: Int,
    fpsLimiterMax: Int,
    lsfgMultiplier: Int,
    onTogglePerformanceHud: () -> Unit,
    onPerformanceHudConfigChanged: (PerformanceHudConfig) -> Unit,
    onFpsLimiterEnabledChanged: (Boolean) -> Unit,
    onFpsLimiterChanged: (Int) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── FPS Limiter (topmost) ────────────────────────────────────────
        val lsfgActive = lsfgMultiplier >= 2
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_fps_limiter),
            subtitle = if (lsfgActive) {
                stringResource(R.string.performance_hud_fps_limiter_lsfg_base)
            } else null,
            enabled = fpsLimiterEnabled,
            onToggle = { onFpsLimiterEnabledChanged(!fpsLimiterEnabled) },
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        AnimatedVisibility(
            visible = fpsLimiterEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Spacer(modifier = Modifier.height(4.dp))
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.performance_hud_fps_limiter_target),
                    valueText = stringResource(
                        R.string.performance_hud_fps_limiter_value,
                        fpsLimiterTarget,
                    ),
                    progress = fpsLimiterProgress(fpsLimiterTarget, fpsLimiterMax),
                    onDecrease = {
                        onFpsLimiterChanged(previousFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    onIncrease = {
                        onFpsLimiterChanged(nextFpsLimiterValue(fpsLimiterTarget, fpsLimiterMax))
                    },
                    accentColor = accentColor,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Performance HUD ──────────────────────────────────────────────
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud),
            subtitle = stringResource(R.string.performance_hud_description),
            enabled = isPerformanceHudEnabled,
            onToggle = onTogglePerformanceHud,
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_presets),
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                // Immersive only: groups the chips so directional focus enters the row as a
                // unit. Flat mode keeps master's traversal.
                .then(if (LocalImmersiveInputBypass.current.active) Modifier.focusGroup() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PerformanceHudPreset.values().forEach { preset ->
                QuickMenuChoiceChip(
                    text = stringResource(preset.labelResId),
                    selected = matchesPerformanceHudPreset(performanceHudConfig, preset),
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(applyPerformanceHudPreset(performanceHudConfig, preset))
                        if (!isPerformanceHudEnabled) {
                            onTogglePerformanceHud()
                        }
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_appearance),
        )

        Text(
            text = stringResource(R.string.performance_hud_size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )

        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                // Immersive only: groups the chips so directional focus enters the row as a
                // unit. Flat mode keeps master's traversal.
                .then(if (LocalImmersiveInputBypass.current.active) Modifier.focusGroup() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                PerformanceHudSize.SMALL to R.string.performance_hud_size_small,
                PerformanceHudSize.MEDIUM to R.string.performance_hud_size_medium,
                PerformanceHudSize.LARGE to R.string.performance_hud_size_large,
            ).forEach { (size, labelResId) ->
                QuickMenuChoiceChip(
                    text = stringResource(labelResId),
                    selected = performanceHudConfig.size == size,
                    accentColor = accentColor,
                    onClick = {
                        onPerformanceHudConfigChanged(performanceHudConfig.copy(size = size))
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_background_opacity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.backgroundOpacity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.backgroundOpacity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        backgroundOpacity = (performanceHudConfig.backgroundOpacity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuAdjustmentRow(
            title = stringResource(R.string.performance_hud_color_intensity),
            valueText = stringResource(
                R.string.performance_hud_percentage_value,
                (performanceHudConfig.colorIntensity * 100f).roundToInt(),
            ),
            progress = normalizedProgress(performanceHudConfig.colorIntensity, 0f, 1f),
            onDecrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity - 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            onIncrease = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(
                        colorIntensity = (performanceHudConfig.colorIntensity + 0.05f).coerceIn(0f, 1f),
                    ),
                )
            },
            accentColor = accentColor,
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_text_outline),
            enabled = performanceHudConfig.showTextOutline,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showTextOutline = !performanceHudConfig.showTextOutline),
                )
            },
            accentColor = accentColor,
        )

        Spacer(modifier = Modifier.height(8.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.performance_hud_metrics),
        )

        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate),
            enabled = performanceHudConfig.showFrameRate,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRate = !performanceHudConfig.showFrameRate),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_frame_rate_graph),
            enabled = performanceHudConfig.showFrameRateGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showFrameRateGraph = !performanceHudConfig.showFrameRateGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage),
            enabled = performanceHudConfig.showCpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsage = !performanceHudConfig.showCpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_usage_graph),
            enabled = performanceHudConfig.showCpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuUsageGraph = !performanceHudConfig.showCpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage),
            enabled = performanceHudConfig.showGpuUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsage = !performanceHudConfig.showGpuUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_usage_graph),
            enabled = performanceHudConfig.showGpuUsageGraph,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuUsageGraph = !performanceHudConfig.showGpuUsageGraph),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_ram_usage),
            enabled = performanceHudConfig.showRamUsage,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showRamUsage = !performanceHudConfig.showRamUsage),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_level),
            enabled = performanceHudConfig.showBatteryLevel,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryLevel = !performanceHudConfig.showBatteryLevel),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_power_draw),
            enabled = performanceHudConfig.showPowerDraw,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showPowerDraw = !performanceHudConfig.showPowerDraw),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_runtime_left),
            enabled = performanceHudConfig.showBatteryRuntime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryRuntime = !performanceHudConfig.showBatteryRuntime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_battery_temperature),
            enabled = performanceHudConfig.showBatteryTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showBatteryTemperature = !performanceHudConfig.showBatteryTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_clock_time),
            enabled = performanceHudConfig.showClockTime,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showClockTime = !performanceHudConfig.showClockTime),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_cpu_temperature),
            enabled = performanceHudConfig.showCpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showCpuTemperature = !performanceHudConfig.showCpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.performance_hud_gpu_temperature),
            enabled = performanceHudConfig.showGpuTemperature,
            onToggle = {
                onPerformanceHudConfigChanged(
                    performanceHudConfig.copy(showGpuTemperature = !performanceHudConfig.showGpuTemperature),
                )
            },
            accentColor = accentColor,
        )
        if (PowerManager.isFanControlAvailable()) {
            var showFan by remember { mutableStateOf(PrefManager.showPerformanceHudFan) }
            QuickMenuToggleRow(
                title = stringResource(R.string.power_control_hud_show_fan),
                enabled = showFan,
                onToggle = {
                    showFan = !showFan
                    PrefManager.showPerformanceHudFan = showFan
                },
                accentColor = accentColor,
            )
        }
        if (PowerManager.isClusterTuningAvailable()) {
            var showTunerCaps by remember { mutableStateOf(PrefManager.showPerformanceHudTunerCaps) }
            QuickMenuToggleRow(
                title = stringResource(R.string.power_control_hud_show_tuner),
                enabled = showTunerCaps,
                onToggle = {
                    showTunerCaps = !showTunerCaps
                    PrefManager.showPerformanceHudTunerCaps = showTunerCaps
                },
                accentColor = accentColor,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun LsfgQuickMenuTab(
    multiplier: Int,
    flowScale: Float,
    performanceMode: Boolean,
    onMultiplierChanged: (Int) -> Unit,
    onFlowScaleChanged: (Float) -> Unit,
    onPerformanceModeChanged: (Boolean) -> Unit,
    presentMode: String,
    onPresentModeChanged: (String) -> Unit,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val isEnabled = multiplier >= 2

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Multiplier (Off / 2x / 3x / 4x) ───────────────────────────────
        QuickMenuSectionHeader(
            title = stringResource(R.string.lsfg_multiplier),
        )
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                // Immersive only: groups the chips so directional focus enters the row as a
                // unit. Flat mode keeps master's traversal.
                .then(if (LocalImmersiveInputBypass.current.active) Modifier.focusGroup() else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0, 2, 3, 4).forEach { value ->
                QuickMenuChoiceChip(
                    text = if (value == 0) "Off" else "${value}x",
                    selected = multiplier == value || (value == 0 && multiplier < 2),
                    accentColor = accentColor,
                    onClick = { onMultiplierChanged(value) },
                    modifier = Modifier.width(56.dp),
                    focusRequester = if (value == 0) focusRequester else null,
                )
            }
        }

        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Spacer(modifier = Modifier.height(4.dp))

                // ── Flow Scale ────────────────────────────────────────────
                QuickMenuAdjustmentRow(
                    title = stringResource(R.string.lsfg_flow_scale),
                    subtitle = stringResource(R.string.lsfg_flow_scale_desc),
                    valueText = String.format(java.util.Locale.US, "%.2f", flowScale),
                    progress = (flowScale - 0.25f) / 0.75f, // 0.25..1.0 → 0..1
                    onDecrease = {
                        val next = (flowScale - 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    onIncrease = {
                        val next = (flowScale + 0.05f).coerceIn(0.25f, 1.0f)
                        onFlowScaleChanged(String.format(java.util.Locale.US, "%.2f", next).toFloat())
                    },
                    accentColor = accentColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Performance Mode ──────────────────────────────────────
                QuickMenuToggleRow(
                    title = stringResource(R.string.lsfg_performance_mode),
                    subtitle = stringResource(R.string.lsfg_performance_mode_desc),
                    enabled = performanceMode,
                    onToggle = { onPerformanceModeChanged(!performanceMode) },
                    accentColor = accentColor,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // ── Present Mode (Mailbox / FIFO) ─────────────────────────
                QuickMenuSectionHeader(
                    title = stringResource(R.string.lsfg_present_mode),
                    subtitle = stringResource(R.string.lsfg_present_mode_desc),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("mailbox" to "Mailbox", "fifo" to "FIFO").forEach { (value, label) ->
                        QuickMenuChoiceChip(
                            text = label,
                            selected = presentMode == value,
                            accentColor = accentColor,
                            onClick = { onPresentModeChanged(value) },
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun ImmersiveQuickMenuTab(
    controls: app.gamenative.ui.screen.xr.ImmersiveControls,
    scrollState: ScrollState,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ── Passthrough ────────────────────────────────────────────────
        QuickMenuSectionHeader(
            title = stringResource(R.string.immersive_passthrough),
            subtitle = stringResource(R.string.immersive_passthrough_desc),
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.immersive_passthrough_toggle),
            enabled = controls.passthroughEnabled,
            onToggle = { controls.onPassthroughToggle(!controls.passthroughEnabled) },
            accentColor = accentColor,
            focusRequester = focusRequester,
        )

        Spacer(modifier = Modifier.height(4.dp))

        QuickMenuSectionHeader(
            title = stringResource(R.string.immersive_resize_mode_title),
            subtitle = stringResource(R.string.immersive_resize_mode_desc),
        )
        QuickMenuToggleRow(
            title = stringResource(R.string.immersive_resize_mode_toggle),
            enabled = controls.resizeModeEnabled,
            onToggle = { controls.onResizeModeToggle(!controls.resizeModeEnabled) },
            accentColor = accentColor,
        )

        if (controls.directRenderBlockedByEffects != null) {
            Spacer(modifier = Modifier.height(4.dp))
            QuickMenuSectionHeader(
                title = stringResource(R.string.immersive_direct_render_title),
                subtitle = stringResource(
                    if (controls.directRenderBlockedByEffects) {
                        R.string.immersive_reset_effects_status_blocked
                    } else {
                        R.string.immersive_reset_effects_status_ok
                    },
                ),
            )
            val resetInteractionSource = remember { MutableInteractionSource() }
            val resetIsFocused by resetInteractionSource.collectIsFocusedAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (resetIsFocused) {
                            accentColor.copy(alpha = 0.16f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                        },
                    )
                    .then(
                        if (resetIsFocused) {
                            Modifier.border(width = 2.dp, color = accentColor.copy(alpha = 0.7f), shape = RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        }
                    )
                    .selectable(
                        selected = false,
                        interactionSource = resetInteractionSource,
                        indication = null,
                        onClick = controls.onResetScreenEffects,
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.immersive_reset_effects_button),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.immersive_reset_effects_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun QuickMenuSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickMenuCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Flat mode keeps master's explicit focusable(); the immersive path drops it to
    // avoid a second focus target on the same element (see LocalImmersiveInputBypass).
    val inputBypass = LocalImmersiveInputBypass.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    Color.Transparent
                },
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier else Modifier.focusable(interactionSource = interactionSource),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.quick_menu_back),
            tint = if (isFocused) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuTabButton(
    icon: ImageVector,
    contentDescriptionResId: Int,
    selected: Boolean,
    accentColor: Color,
    onSelected: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val inputBypass = LocalImmersiveInputBypass.current
    LaunchedEffect(isFocused) {
        inputBypass.reportActivate(interactionSource, if (isFocused) onSelected else null)
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
            )
            .focusRing(interactionSource, shape, width = 2.dp)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (!inputBypass.active && it.isFocused && !selected) {
                    onSelected()
                }
            }
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelected,
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier else Modifier.focusable(interactionSource = interactionSource),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescriptionResId),
            tint = when {
                selected || isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuRailActionButton(
    item: QuickMenuItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Flat mode keeps master's explicit focusable(); the immersive path drops it to
    // avoid a second focus target on the same element (see LocalImmersiveInputBypass).
    val inputBypass = LocalImmersiveInputBypass.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.error
    }
    val shape = RoundedCornerShape(14.dp)

    Box(
        modifier = modifier
            .size(56.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                if (isFocused) {
                    accentColor.copy(alpha = 0.18f)
                } else {
                    accentColor.copy(alpha = 0.08f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier else Modifier.focusable(interactionSource = interactionSource),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = stringResource(item.labelResId),
            tint = if (isFocused) accentColor else accentColor.copy(alpha = 0.9f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun QuickMenuChoiceChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val inputBypass = LocalImmersiveInputBypass.current
    LaunchedEffect(isFocused) {
        inputBypass.reportActivate(interactionSource, if (isFocused) onClick else null)
    }

    Box(
        modifier = modifier
            .height(44.dp)
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) accentColor.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier else Modifier.focusable(interactionSource = interactionSource),
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
internal fun QuickMenuAdjustmentRow(
    title: String,
    valueText: String,
    progress: Float,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    var isAdjustmentLocked by remember { mutableStateOf(false) }
    val inputBypass = LocalImmersiveInputBypass.current
    LaunchedEffect(isFocused, isAdjustmentLocked) {
        inputBypass.reportAdjustment(interactionSource, if (isFocused && isAdjustmentLocked) (onDecrease to onIncrease) else null)
    }

    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged {
                if (!it.isFocused) {
                    if (isAdjustmentLocked) {
                        Timber.i("QuickMenu: row '%s' lost focus while locked — force-unlocking", title)
                    }
                    isAdjustmentLocked = false
                }
            }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when {
                        keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_A &&
                            (!inputBypass.active || !isAdjustmentLocked) -> {
                            isAdjustmentLocked = if (inputBypass.active) true else !isAdjustmentLocked
                            Timber.i("QuickMenu: row '%s' lock now %b", title, isAdjustmentLocked)
                            true
                        }

                        isAdjustmentLocked &&
                            (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                                (inputBypass.active && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK)) -> {
                            isAdjustmentLocked = false
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onDecrease()
                            true
                        }

                        isAdjustmentLocked && keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onIncrease()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (isAdjustmentLocked) {
                    Text(
                        text = "●",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                    )
                }
            }
        }

        if (subtitle != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickMenuAdjustmentButton(
                text = "-",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onDecrease,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )

                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onDecrease,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onIncrease,
                            ),
                    )
                }
            }

            QuickMenuAdjustmentButton(
                text = "+",
                rowIsFocused = isFocused,
                isAdjustmentLocked = isAdjustmentLocked,
                accentColor = accentColor,
                onClick = onIncrease,
            )
        }
    }
}

@Composable
private fun QuickMenuAdjustmentButton(
    text: String,
    rowIsFocused: Boolean,
    isAdjustmentLocked: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(44.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.25f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (rowIsFocused) 0.32f else 0.45f)
                },
            )
            .border(
                width = if (isAdjustmentLocked) 2.dp else 1.dp,
                color = if (isAdjustmentLocked) {
                    accentColor.copy(alpha = 0.9f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                },
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (isAdjustmentLocked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun QuickMenuToggleRow(
    title: String,
    enabled: Boolean,
    selectable: Boolean = true,
    onToggle: () -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Flat mode keeps master's explicit focusable(); the immersive path drops it to
    // avoid a second focus target on the same element (see LocalImmersiveInputBypass).
    val inputBypass = LocalImmersiveInputBypass.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    LaunchedEffect(isFocused, selectable) {
        inputBypass.reportActivate(interactionSource, if (isFocused && selectable) onToggle else null)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = { if (selectable) onToggle() },
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier else Modifier.focusable(interactionSource = interactionSource),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (!selectable) Modifier.alpha(0.5f) else Modifier)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Box(
            modifier = if (!selectable) Modifier.alpha(0.5f) else Modifier
        ) {
            QuickMenuSwitch(
                enabled = enabled,
                accentColor = accentColor,
            )
        }
    }
}

@Composable
private fun QuickMenuSwitch(
    enabled: Boolean,
    accentColor: Color,
) {
    Box(
        modifier = Modifier
            .width(56.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            )
            .border(
                width = 1.dp,
                color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                .background(Color.White, CircleShape),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickMenuDetailRow(
    title: String,
    subtitle: String,
    accentColor: Color,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.14f),
                            accentColor.copy(alpha = 0.06f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.8f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            onActivate()
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onActivate,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(iterations = Int.MAX_VALUE),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) accentColor.copy(alpha = 0.92f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickMenuItemRow(
    item: QuickMenuItem,
    isActive: Boolean = false,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    secondaryIcon: ImageVector? = null,
    secondaryContentDescriptionResId: Int? = null,
    onSecondaryClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Flat mode keeps master's explicit focusable(); the immersive path drops it to
    // avoid a second focus target on the same element (see LocalImmersiveInputBypass).
    val inputBypass = LocalImmersiveInputBypass.current
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isEnabled = item.enabled

    val accentColor = if (item.accentColor != Color.Unspecified) {
        item.accentColor
    } else {
        MaterialTheme.colorScheme.primary
    }

    val disabledAlpha = 0.4f
    val shape = RoundedCornerShape(12.dp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (isFocused && isEnabled) {
                    Modifier.background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.15f),
                                accentColor.copy(alpha = 0.05f),
                            ),
                        ),
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (isEnabled) {
                    Modifier.focusRing(interactionSource, shape, width = 2.dp)
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .selectable(
                selected = isFocused,
                enabled = isEnabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .then(
                // Flat mode keeps master's second focus target; immersive drops it.
                if (inputBypass.active) Modifier
                else Modifier.focusable(enabled = isEnabled, interactionSource = interactionSource),
            )
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(
                    if (isActive) {
                        Modifier.border(BorderStroke(2.dp, accentColor), CircleShape)
                    } else Modifier
                )
                .clip(CircleShape)
                .background(
                    when {
                        !isEnabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        isFocused || isActive -> accentColor.copy(alpha = 0.2f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = when {
                    !isEnabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = disabledAlpha)
                    isFocused || isActive -> accentColor
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }

        Text(
            text = stringResource(item.labelResId),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = disabledAlpha)
                isFocused -> accentColor
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )

        if (secondaryIcon != null && onSecondaryClick != null) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(role = Role.Button, onClick = onSecondaryClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = secondaryIcon,
                    contentDescription = stringResource(
                        secondaryContentDescriptionResId ?: R.string.gesture_settings_title,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = false,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun Preview_QuickMenu_WithController() {
    PluviaTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickMenu(
                isVisible = true,
                onDismiss = {},
                onItemSelected = { false },
                hasPhysicalController = true,
            )
        }
    }
}
